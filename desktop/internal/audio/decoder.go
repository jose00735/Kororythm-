// Package audio is Kororythm's playback engine on Windows: decoders, resampling,
// gapless hand-over between songs, crossfade, the equalizer and the output device.
package audio

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/hajimehoshi/go-mp3"
	"github.com/mewkiz/flac"
)

// Decoder turns a file into float samples.
type Decoder interface {
	// Read fills dst with interleaved samples and returns how many frames it wrote.
	// It returns io.EOF (possibly with frames > 0) at the end of the file.
	Read(dst []float32) (int, error)
	SampleRate() int
	Channels() int
	// Frames is the total length in frames, or 0 when unknown.
	Frames() int64
	// Seek moves to the given frame.
	SeekFrame(frame int64) error
	Close() error
}

// Open picks a decoder by file extension.
func Open(path string) (Decoder, error) {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".flac":
		return openFlac(path)
	case ".mp3":
		return openMp3(path)
	}
	return nil, fmt.Errorf("unsupported file type: %s", filepath.Ext(path))
}

// ---- FLAC ----

type flacDecoder struct {
	f      *os.File
	s      *flac.Stream
	ch     int
	scale  float32
	pend   []float32 // decoded but not yet returned, interleaved
	pendAt int
}

func openFlac(path string) (Decoder, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	s, err := flac.NewSeek(f)
	if err != nil {
		f.Close()
		return nil, err
	}
	ch := int(s.Info.NChannels)
	return &flacDecoder{f: f, s: s, ch: ch, scale: 1 / float32(int64(1)<<(s.Info.BitsPerSample-1))}, nil
}

func (d *flacDecoder) SampleRate() int { return int(d.s.Info.SampleRate) }
func (d *flacDecoder) Channels() int   { return d.ch }
func (d *flacDecoder) Frames() int64   { return int64(d.s.Info.NSamples) }
func (d *flacDecoder) Close() error    { return d.f.Close() }

func (d *flacDecoder) fill() error {
	fr, err := d.s.ParseNext()
	if err != nil {
		return err
	}
	n := int(fr.BlockSize)
	if cap(d.pend) < n*d.ch {
		d.pend = make([]float32, n*d.ch)
	}
	d.pend = d.pend[:n*d.ch]
	for c := 0; c < d.ch && c < len(fr.Subframes); c++ {
		smp := fr.Subframes[c].Samples
		for i := 0; i < n && i < len(smp); i++ {
			d.pend[i*d.ch+c] = float32(smp[i]) * d.scale
		}
	}
	d.pendAt = 0
	return nil
}

func (d *flacDecoder) Read(dst []float32) (int, error) {
	want := len(dst) / d.ch
	got := 0
	for got < want {
		if d.pendAt >= len(d.pend) {
			if err := d.fill(); err != nil {
				if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
					return got, io.EOF
				}
				return got, err
			}
		}
		n := copy(dst[got*d.ch:want*d.ch], d.pend[d.pendAt:])
		d.pendAt += n
		got += n / d.ch
	}
	return got, nil
}

func (d *flacDecoder) SeekFrame(frame int64) error {
	if frame < 0 {
		frame = 0
	}
	start, err := d.s.Seek(uint64(frame))
	if err != nil {
		return err
	}
	d.pend = d.pend[:0]
	d.pendAt = 0
	// The seek lands on the start of the frame holding the target; skip ahead.
	skip := int(frame - int64(start))
	if skip > 0 {
		if err := d.fill(); err != nil {
			return err
		}
		d.pendAt = min(skip*d.ch, len(d.pend))
	}
	return nil
}

// ---- MP3 ----

type mp3Decoder struct {
	f   *os.File
	d   *mp3.Decoder
	raw []byte
}

func openMp3(path string) (Decoder, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	d, err := mp3.NewDecoder(f)
	if err != nil {
		f.Close()
		return nil, err
	}
	return &mp3Decoder{f: f, d: d}, nil
}

func (m *mp3Decoder) SampleRate() int { return m.d.SampleRate() }
func (m *mp3Decoder) Channels() int   { return 2 }
func (m *mp3Decoder) Frames() int64 {
	if l := m.d.Length(); l > 0 {
		return l / 4
	}
	return 0
}
func (m *mp3Decoder) Close() error { return m.f.Close() }

func (m *mp3Decoder) Read(dst []float32) (int, error) {
	frames := len(dst) / 2
	if cap(m.raw) < frames*4 {
		m.raw = make([]byte, frames*4)
	}
	raw := m.raw[:frames*4]
	n, err := io.ReadFull(m.d, raw)
	n -= n % 4
	for i := 0; i < n/2; i++ {
		dst[i] = float32(int16(binary.LittleEndian.Uint16(raw[2*i:]))) / 32768
	}
	if errors.Is(err, io.ErrUnexpectedEOF) {
		err = io.EOF
	}
	return n / 4, err
}

func (m *mp3Decoder) SeekFrame(frame int64) error {
	_, err := m.d.Seek(frame*4, io.SeekStart)
	return err
}

// ---- Probing ----

// Info describes a file's audio format.
type Info struct {
	SampleRate int    `json:"sampleRate"`
	BitDepth   int    `json:"bitDepth"`
	Channels   int    `json:"channels"`
	DurationMs int64  `json:"durationMs"`
	Codec      string `json:"codec"`
	BitrateKb  int    `json:"bitrateKb"`
}

// Probe reads a file's format without decoding all of it. For MP3 it scans the
// frame headers once to get an exact length.
func Probe(path string) (Info, error) {
	st, err := os.Stat(path)
	if err != nil {
		return Info{}, err
	}
	switch strings.ToLower(filepath.Ext(path)) {
	case ".flac":
		f, err := os.Open(path)
		if err != nil {
			return Info{}, err
		}
		defer f.Close()
		s, err := flac.New(bufio.NewReader(f))
		if err != nil {
			return Info{}, err
		}
		in := Info{SampleRate: int(s.Info.SampleRate), BitDepth: int(s.Info.BitsPerSample), Channels: int(s.Info.NChannels), Codec: "FLAC"}
		if s.Info.SampleRate > 0 {
			in.DurationMs = int64(s.Info.NSamples) * 1000 / int64(s.Info.SampleRate)
		}
		if in.DurationMs > 0 {
			in.BitrateKb = int(st.Size() * 8 / in.DurationMs)
		}
		return in, nil
	case ".mp3":
		d, err := openMp3(path)
		if err != nil {
			return Info{}, err
		}
		defer d.Close()
		in := Info{SampleRate: d.SampleRate(), Channels: 2, Codec: "MP3"}
		if fr := d.Frames(); fr > 0 && in.SampleRate > 0 {
			in.DurationMs = fr * 1000 / int64(in.SampleRate)
		}
		if in.DurationMs > 0 {
			in.BitrateKb = int(st.Size() * 8 / in.DurationMs)
		}
		return in, nil
	}
	return Info{}, fmt.Errorf("unsupported file type")
}
