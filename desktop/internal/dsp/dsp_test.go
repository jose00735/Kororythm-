package dsp

import (
	"math"
	"testing"
)

func TestFlatEqIsTransparent(t *testing.T) {
	e := NewEqualizer(44100, 2)
	e.Update(EqSettings{Enabled: true})
	buf := []float32{0.5, -0.25, 0.1, 0.2}
	want := append([]float32(nil), buf...)
	e.Process(buf)
	for i := range buf {
		if buf[i] != want[i] {
			t.Fatalf("flat EQ changed sample %d: %v -> %v", i, want[i], buf[i])
		}
	}
}

func TestPeakingGainAtCentre(t *testing.T) {
	f := NewBiquad(1)
	f.SetPeaking(44100, 1000, bandQ, 6)
	if got := f.Response(44100, 1000); math.Abs(got-6) > 0.01 {
		t.Fatalf("gain at centre = %.3f dB, want 6", got)
	}
	if got := f.Response(44100, 50); math.Abs(got) > 0.5 {
		t.Fatalf("gain far away = %.3f dB, want ~0", got)
	}
}

func TestBassPresetLiftsLowSine(t *testing.T) {
	bands, _ := PresetBands("Bass")
	e := NewEqualizer(44100, 1)
	e.Update(EqSettings{Enabled: true, Bands: bands, Preamp: -8})
	rms := func(freq float64, eq bool) float64 {
		buf := make([]float32, 44100)
		for i := range buf {
			buf[i] = float32(0.1 * math.Sin(2*math.Pi*freq*float64(i)/44100))
		}
		if eq {
			e.Reset()
			e.Process(buf)
		}
		var s float64
		for _, v := range buf[22050:] {
			s += float64(v) * float64(v)
		}
		return math.Sqrt(s / 22050)
	}
	lift60 := 20 * math.Log10(rms(60, true)/rms(60, false))
	lift8k := 20 * math.Log10(rms(8000, true)/rms(8000, false))
	if lift60-lift8k < 5 {
		t.Fatalf("bass preset should lift 60 Hz well above 8 kHz: %.1f vs %.1f dB", lift60, lift8k)
	}
}

func TestSoftClipBounds(t *testing.T) {
	for _, x := range []float64{-5, -1, -0.5, 0, 0.5, 0.95, 1, 5} {
		y := SoftClip(x)
		if math.Abs(y) > 1 {
			t.Fatalf("SoftClip(%v) = %v, beyond 1", x, y)
		}
		if math.Abs(x) <= clipKnee && y != x {
			t.Fatalf("SoftClip changed %v below the knee", x)
		}
	}
}

func TestSpectrumFindsTone(t *testing.T) {
	s := NewSpectrum(44100)
	buf := make([]float32, fftSize*2)
	for i := 0; i < fftSize; i++ {
		v := float32(0.5 * math.Sin(2*math.Pi*1000*float64(i)/44100))
		buf[2*i], buf[2*i+1] = v, v
	}
	s.Write(buf, 2)
	bars := s.Bars(64)
	best := 0
	for i := range bars {
		if bars[i] > bars[best] {
			best = i
		}
	}
	lo, hi := math.Log(40), math.Log(16000)
	centre := math.Exp(lo + (hi-lo)*(float64(best)+0.5)/64)
	if centre < 800 || centre > 1250 {
		t.Fatalf("loudest bar at %.0f Hz, want ~1 kHz", centre)
	}
}
