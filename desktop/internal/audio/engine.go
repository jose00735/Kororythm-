package audio

import (
	"errors"
	"io"
	"math"
	"sync"

	"github.com/jose00735/kororythm/desktop/internal/dsp"
)

// OutRate is the rate the engine renders at. Songs at other rates are resampled.
const OutRate = 44100

// source is one open song being read at the engine's output rate, in stereo.
type source struct {
	id      string
	dec     Decoder
	rs      *resampler
	tmp     []float32
	stereo  []float32
	eof     bool
	outPos  int64 // output frames produced since the start of the song
	outLen  int64 // song length in output frames (0 if unknown)
}

func newSource(id, path string) (*source, error) {
	d, err := Open(path)
	if err != nil {
		return nil, err
	}
	s := &source{id: id, dec: d, rs: newResampler(d.SampleRate(), OutRate)}
	if fr := d.Frames(); fr > 0 {
		s.outLen = fr * OutRate / int64(d.SampleRate())
	}
	return s, nil
}

func (s *source) close() { s.dec.Close() }

// toStereo converts n frames of decoder output into stereo.
func (s *source) toStereo(n int) []float32 {
	ch := s.dec.Channels()
	if cap(s.stereo) < n*2 {
		s.stereo = make([]float32, n*2)
	}
	st := s.stereo[:n*2]
	for i := 0; i < n; i++ {
		switch ch {
		case 1:
			st[2*i], st[2*i+1] = s.tmp[i], s.tmp[i]
		default:
			st[2*i], st[2*i+1] = s.tmp[i*ch], s.tmp[i*ch+1]
		}
	}
	return st
}

// read fills dst (stereo) and returns frames written; done is true at the end.
func (s *source) read(dst []float32) (int, bool) {
	want := len(dst) / 2
	got := 0
	ch := s.dec.Channels()
	for got < want {
		if s.rs.passthrough() {
			if s.eof {
				break
			}
			need := want - got
			if cap(s.tmp) < need*ch {
				s.tmp = make([]float32, need*ch)
			}
			n, err := s.dec.Read(s.tmp[:need*ch])
			copy(dst[got*2:], s.toStereo(n))
			got += n
			if err != nil {
				s.eof = true
			}
			continue
		}
		if s.rs.available() == 0 {
			if s.eof {
				break
			}
			const chunk = 2048
			if cap(s.tmp) < chunk*ch {
				s.tmp = make([]float32, chunk*ch)
			}
			n, err := s.dec.Read(s.tmp[:chunk*ch])
			s.rs.push(s.toStereo(n))
			if err != nil {
				s.eof = true
				// Flush the kernel's tail with silence.
				s.rs.push(make([]float32, 2*s.rs.taps*2))
			}
			continue
		}
		got += s.rs.pull(dst[got*2 : want*2])
	}
	s.outPos += int64(got)
	return got, got < want
}

// Event is something the engine reports to the player.
type Event struct {
	Kind string // "start", "ended", "error"
	ID   string
	Err  error
}

// NextFunc gives the engine the song after the current one, for gapless
// playback. ok is false when the queue ends.
type NextFunc func() (id, path string, ok bool)

// Engine mixes the current song (and the next one, during a crossfade), applies
// the equalizer and volume, and feeds the output device.
type Engine struct {
	mu        sync.Mutex
	cur, next *source
	playing   bool
	volume    float64
	xfade     int64 // crossfade length in output frames; 0 = gapless
	eq        *dsp.Equalizer
	spec      *dsp.Spectrum
	nextFn    NextFunc
	events    chan Event
	mixBuf    []float32
	preloading bool
}

// NewEngine makes an idle engine. Events arrive on Events().
func NewEngine() *Engine {
	return &Engine{
		volume: 0.8,
		eq:     dsp.NewEqualizer(OutRate, 2),
		spec:   dsp.NewSpectrum(OutRate),
		events: make(chan Event, 32),
	}
}

// Events delivers track starts, ends and errors.
func (e *Engine) Events() <-chan Event { return e.events }

func (e *Engine) emit(ev Event) {
	select {
	case e.events <- ev:
	default:
	}
}

// SetNextFunc sets how the engine finds the following song.
func (e *Engine) SetNextFunc(f NextFunc) {
	e.mu.Lock()
	e.nextFn = f
	e.mu.Unlock()
}

// Play starts a song now, replacing whatever was playing.
func (e *Engine) Play(id, path string) error {
	s, err := newSource(id, path)
	if err != nil {
		e.emit(Event{Kind: "error", ID: id, Err: err})
		return err
	}
	e.mu.Lock()
	if e.cur != nil {
		e.cur.close()
	}
	if e.next != nil {
		e.next.close()
		e.next = nil
	}
	e.cur = s
	e.playing = true
	e.eq.Reset()
	e.mu.Unlock()
	e.emit(Event{Kind: "start", ID: id})
	e.preload()
	return nil
}

// RefreshNext drops the preloaded next song and asks again (the queue changed).
func (e *Engine) RefreshNext() {
	e.mu.Lock()
	if e.next != nil && !e.fading() {
		e.next.close()
		e.next = nil
	}
	e.mu.Unlock()
	e.preload()
}

// fading reports whether a crossfade is under way. Caller holds mu.
func (e *Engine) fading() bool {
	return e.xfade > 0 && e.cur != nil && e.next != nil && e.cur.outLen > 0 && e.cur.outLen-e.cur.outPos <= e.xfade
}

func (e *Engine) preload() {
	e.mu.Lock()
	if e.preloading || e.next != nil || e.cur == nil || e.nextFn == nil {
		e.mu.Unlock()
		return
	}
	e.preloading = true
	fn := e.nextFn
	e.mu.Unlock()
	go func() {
		id, path, ok := fn()
		var s *source
		if ok {
			var err error
			if s, err = newSource(id, path); err != nil {
				e.emit(Event{Kind: "error", ID: id, Err: err})
			}
		}
		e.mu.Lock()
		e.preloading = false
		if s != nil {
			if e.next == nil && e.cur != nil {
				e.next = s
			} else {
				s.close()
			}
		}
		e.mu.Unlock()
	}()
}

// Pause, Resume, Toggle and Stop control playback.
func (e *Engine) Pause()  { e.mu.Lock(); e.playing = false; e.mu.Unlock() }
func (e *Engine) Resume() { e.mu.Lock(); e.playing = e.cur != nil; e.mu.Unlock() }
func (e *Engine) Toggle() { e.mu.Lock(); e.playing = !e.playing && e.cur != nil; e.mu.Unlock() }
func (e *Engine) Stop() {
	e.mu.Lock()
	if e.cur != nil {
		e.cur.close()
		e.cur = nil
	}
	if e.next != nil {
		e.next.close()
		e.next = nil
	}
	e.playing = false
	e.mu.Unlock()
	e.spec.Silence()
}

// Seek jumps within the current song.
func (e *Engine) SeekMs(ms int64) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.cur == nil {
		return errors.New("nothing playing")
	}
	rate := int64(e.cur.dec.SampleRate())
	if err := e.cur.dec.SeekFrame(ms * rate / 1000); err != nil {
		return err
	}
	e.cur.rs = newResampler(int(rate), OutRate)
	e.cur.eof = false
	e.cur.outPos = ms * OutRate / 1000
	e.eq.Reset()
	return nil
}

// SetVolume sets the output level, 0..1.
func (e *Engine) SetVolume(v float64) {
	e.mu.Lock()
	e.volume = math.Max(0, math.Min(1, v))
	e.mu.Unlock()
}

// SetCrossfade sets the crossfade length; 0 means gapless hand-over.
func (e *Engine) SetCrossfade(ms int) {
	e.mu.Lock()
	e.xfade = int64(ms) * OutRate / 1000
	e.mu.Unlock()
}

// SetEq applies equalizer settings.
func (e *Engine) SetEq(s dsp.EqSettings) { e.eq.Update(s) }

// Bars returns the visualizer's bar heights.
func (e *Engine) Bars(n int) []float64 { return e.spec.Bars(n) }

// Status reports what is playing.
type Status struct {
	ID         string
	Playing    bool
	PositionMs int64
	DurationMs int64
	Volume     float64
}

// Status returns the current state.
func (e *Engine) Status() Status {
	e.mu.Lock()
	defer e.mu.Unlock()
	st := Status{Playing: e.playing, Volume: e.volume}
	if e.cur != nil {
		st.ID = e.cur.id
		st.PositionMs = e.cur.outPos * 1000 / OutRate
		st.DurationMs = e.cur.outLen * 1000 / OutRate
	}
	return st
}

// Read renders interleaved stereo float samples for the output device.
// Silence is returned while paused, so the device stays open.
func (e *Engine) Read(out []float32) {
	e.mu.Lock()
	if !e.playing || e.cur == nil {
		e.mu.Unlock()
		clear(out)
		e.spec.Write(out, 2)
		return
	}
	var started []string
	ended := false
	frames := len(out) / 2
	done := 0
	for done < frames && e.cur != nil {
		seg := out[done*2 : frames*2]
		// Crossfade: mix the next song in over the last xfade frames.
		if e.fading() {
			left := int(e.cur.outLen - e.cur.outPos)
			n := min(len(seg)/2, max(left, 1))
			a := seg[:n*2]
			got, fin := e.cur.read(a)
			clear(a[got*2:])
			if cap(e.mixBuf) < n*2 {
				e.mixBuf = make([]float32, n*2)
			}
			b := e.mixBuf[:n*2]
			gb, _ := e.next.read(b)
			clear(b[gb*2:])
			for i := 0; i < n; i++ {
				t := 1 - float64(e.cur.outLen-e.cur.outPos+int64(n-i))/float64(e.xfade)
				t = math.Max(0, math.Min(1, t))
				// Equal-power curve keeps the loudness steady through the fade.
				ga, gb := float32(math.Cos(t*math.Pi/2)), float32(math.Sin(t*math.Pi/2))
				a[2*i] = a[2*i]*ga + b[2*i]*gb
				a[2*i+1] = a[2*i+1]*ga + b[2*i+1]*gb
			}
			done += n
			if fin || e.cur.outPos >= e.cur.outLen {
				e.cur.close()
				e.cur, e.next = e.next, nil
				started = append(started, e.cur.id)
			}
			continue
		}
		got, fin := e.cur.read(seg)
		done += got
		if !fin {
			continue
		}
		// End of song: hand over without a gap if the next one is ready.
		e.cur.close()
		e.cur = nil
		if e.next != nil {
			e.cur, e.next = e.next, nil
			started = append(started, e.cur.id)
		} else {
			ended = true
		}
	}
	clear(out[done*2:])
	if e.cur == nil {
		e.playing = false
	}
	vol := float32(e.volume * e.volume) // perceptual curve
	e.mu.Unlock()

	e.eq.Process(out)
	for i := range out {
		out[i] *= vol
	}
	e.spec.Write(out, 2)
	for _, id := range started {
		e.emit(Event{Kind: "start", ID: id})
	}
	if len(started) > 0 {
		e.preload()
	}
	if ended {
		e.emit(Event{Kind: "ended"})
	}
}

// ErrNoDevice is returned when no output device can be opened.
var ErrNoDevice = errors.New("no audio output device")

var _ = io.EOF
