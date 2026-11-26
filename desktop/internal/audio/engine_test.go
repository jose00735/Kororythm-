package audio

import (
	"math"
	"testing"
	"time"
)

func pull(e *Engine, frames int) []float32 {
	out := make([]float32, 0, frames*2)
	buf := make([]float32, 1024)
	for len(out) < frames*2 {
		e.Read(buf)
		out = append(out, buf...)
	}
	return out[:frames*2]
}

func waitPreload(t *testing.T, e *Engine) {
	t.Helper()
	for i := 0; i < 200; i++ {
		e.mu.Lock()
		ok := e.next != nil
		e.mu.Unlock()
		if ok {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("next song never preloaded")
}

func TestFlacDecodesSine(t *testing.T) {
	d, err := Open("testdata/a.flac")
	if err != nil {
		t.Fatal(err)
	}
	defer d.Close()
	if d.SampleRate() != 44100 || d.Channels() != 2 || d.Frames() != 4*44100 {
		t.Fatalf("format = %d Hz, %d ch, %d frames", d.SampleRate(), d.Channels(), d.Frames())
	}
	buf := make([]float32, 2*4410)
	n, _ := d.Read(buf)
	var peak float64
	for _, v := range buf[:n*2] {
		peak = math.Max(peak, math.Abs(float64(v)))
	}
	if peak < 0.05 || peak > 1 {
		t.Fatalf("peak %.3f, want a sine between 0.05 and 1", peak)
	}
}

func TestHiResFlacIsResampled(t *testing.T) {
	s, err := newSource("x", "testdata/hires.flac")
	if err != nil {
		t.Fatal(err)
	}
	defer s.close()
	if s.outLen != 2*OutRate {
		t.Fatalf("2 s at 48 kHz should be %d output frames, got %d", 2*OutRate, s.outLen)
	}
	total := 0
	buf := make([]float32, 2048)
	for {
		n, done := s.read(buf)
		total += n
		if done {
			break
		}
	}
	if math.Abs(float64(total-2*OutRate)) > 64 {
		t.Fatalf("rendered %d frames, want about %d", total, 2*OutRate)
	}
}

func TestMp3Decodes(t *testing.T) {
	in, err := Probe("testdata/t.mp3")
	if err != nil {
		t.Fatal(err)
	}
	if in.Codec != "MP3" || in.DurationMs < 2900 || in.DurationMs > 3100 {
		t.Fatalf("probe = %+v", in)
	}
}

// Two halves of one sine wave, played back to back, must join with no gap or click.
func TestGaplessHandOver(t *testing.T) {
	e := NewEngine()
	e.SetVolume(1)
	e.SetNextFunc(func() (string, string, bool) { return "tail", "testdata/tail.flac", true })
	if err := e.Play("head", "testdata/head.flac"); err != nil {
		t.Fatal(err)
	}
	waitPreload(t, e)
	e.SetNextFunc(nil)
	out := pull(e, 4*44100)
	// Find the largest jump between neighbouring samples; a gap or click shows up as a spike.
	var maxStep float64
	for i := 2; i < len(out); i += 2 {
		maxStep = math.Max(maxStep, math.Abs(float64(out[i]-out[i-2])))
	}
	// A 440 Hz sine at amplitude ~0.125 moves at most ~0.008 per sample.
	if maxStep > 0.02 {
		t.Fatalf("largest sample step %.4f: the songs did not join cleanly", maxStep)
	}
	var starts []string
	for len(e.events) > 0 {
		ev := <-e.events
		if ev.Kind == "start" {
			starts = append(starts, ev.ID)
		}
	}
	if len(starts) != 2 || starts[1] != "tail" {
		t.Fatalf("start events = %v, want [head tail]", starts)
	}
}

func TestSeekMovesPosition(t *testing.T) {
	e := NewEngine()
	if err := e.Play("a", "testdata/a.flac"); err != nil {
		t.Fatal(err)
	}
	if err := e.SeekMs(2500); err != nil {
		t.Fatal(err)
	}
	pull(e, 4410)
	st := e.Status()
	if st.PositionMs < 2550 || st.PositionMs > 2650 {
		t.Fatalf("position %d ms after seeking to 2500 and playing 100 ms", st.PositionMs)
	}
	if st.DurationMs != 4000 {
		t.Fatalf("duration %d ms, want 4000", st.DurationMs)
	}
}

func TestPauseRendersSilence(t *testing.T) {
	e := NewEngine()
	if err := e.Play("a", "testdata/a.flac"); err != nil {
		t.Fatal(err)
	}
	e.Pause()
	for _, v := range pull(e, 2048) {
		if v != 0 {
			t.Fatal("paused engine produced sound")
		}
	}
}

func TestEndOfQueueStops(t *testing.T) {
	e := NewEngine()
	if err := e.Play("h", "testdata/head.flac"); err != nil {
		t.Fatal(err)
	}
	pull(e, 3*44100)
	if e.Status().Playing {
		t.Fatal("engine still playing after the last song ended")
	}
	ended := false
	for len(e.events) > 0 {
		if (<-e.events).Kind == "ended" {
			ended = true
		}
	}
	if !ended {
		t.Fatal("no ended event")
	}
}
