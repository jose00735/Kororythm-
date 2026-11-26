//go:build !windows

package audio

import (
	"sync/atomic"
	"time"
)

// clockOutput pulls from the engine in real time without a sound device. It is
// used by the developer server on Linux, so positions and the visualizer move.
type clockOutput struct{ stop atomic.Bool }

// OpenOutput starts a silent real-time clock that drives e.
func OpenOutput(e *Engine) (Output, error) {
	o := &clockOutput{}
	go func() {
		const tick = 20 * time.Millisecond
		buf := make([]float32, OutRate*2*int(tick/time.Millisecond)/1000)
		t := time.NewTicker(tick)
		defer t.Stop()
		for range t.C {
			if o.stop.Load() {
				return
			}
			e.Read(buf)
		}
	}()
	return o, nil
}

func (o *clockOutput) Close() error { o.stop.Store(true); return nil }
