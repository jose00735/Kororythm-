//go:build windows

package audio

import (
	"encoding/binary"
	"math"
	"time"

	"github.com/ebitengine/oto/v3"
)

// otoOutput plays through the Windows default device (shared-mode WASAPI).
type otoOutput struct {
	ctx    *oto.Context
	player *oto.Player
}

type otoReader struct {
	r   render
	buf []float32
}

func (o *otoReader) Read(p []byte) (int, error) {
	n := len(p) / 4
	if cap(o.buf) < n {
		o.buf = make([]float32, n)
	}
	b := o.buf[:n]
	o.r(b)
	for i, v := range b {
		binary.LittleEndian.PutUint32(p[4*i:], math.Float32bits(v))
	}
	return n * 4, nil
}

// OpenOutput starts the device and keeps pulling from e.
func OpenOutput(e *Engine) (Output, error) {
	ctx, ready, err := oto.NewContext(&oto.NewContextOptions{
		SampleRate:   OutRate,
		ChannelCount: 2,
		Format:       oto.FormatFloat32LE,
		BufferSize:   60 * time.Millisecond,
	})
	if err != nil {
		return nil, err
	}
	<-ready
	p := ctx.NewPlayer(&otoReader{r: e.Read})
	p.SetBufferSize(OutRate * 2 * 4 / 10) // ~100 ms
	p.Play()
	return &otoOutput{ctx: ctx, player: p}, nil
}

func (o *otoOutput) Close() error { return o.player.Close() }
