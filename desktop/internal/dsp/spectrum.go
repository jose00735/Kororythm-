package dsp

import (
	"math"
	"math/cmplx"
	"sync"
)

const fftSize = 2048

// Spectrum keeps the last few thousand samples of what is playing and turns them
// into bar heights for the visualizers (log-spaced bands, 0..1).
type Spectrum struct {
	mu    sync.Mutex
	ring  [fftSize]float32
	pos   int
	rate  int
	win   [fftSize]float64
	peaks []float64
}

// NewSpectrum makes an analyser for audio at sampleRate.
func NewSpectrum(sampleRate int) *Spectrum {
	s := &Spectrum{rate: sampleRate}
	for i := range s.win { // Hann window
		s.win[i] = 0.5 - 0.5*math.Cos(2*math.Pi*float64(i)/float64(fftSize-1))
	}
	return s
}

// Write feeds interleaved stereo samples (mixed down to mono).
func (s *Spectrum) Write(buf []float32, channels int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := 0; i+channels-1 < len(buf); i += channels {
		var m float32
		for c := 0; c < channels; c++ {
			m += buf[i+c]
		}
		s.ring[s.pos] = m / float32(channels)
		s.pos = (s.pos + 1) % fftSize
	}
}

// Silence clears the buffer, so bars fall when playback stops.
func (s *Spectrum) Silence() {
	s.mu.Lock()
	s.ring = [fftSize]float32{}
	s.mu.Unlock()
}

// Bars returns n bar heights between 0 and 1, from 40 Hz to 16 kHz on a log scale.
func (s *Spectrum) Bars(n int) []float64 {
	s.mu.Lock()
	x := make([]complex128, fftSize)
	for i := 0; i < fftSize; i++ {
		x[i] = complex(float64(s.ring[(s.pos+i)%fftSize])*s.win[i], 0)
	}
	s.mu.Unlock()
	fft(x)
	bins := fftSize / 2
	mag := make([]float64, bins)
	for i := 0; i < bins; i++ {
		mag[i] = cmplx.Abs(x[i]) / float64(fftSize) * 4
	}
	out := make([]float64, n)
	lo, hi := math.Log(40), math.Log(16000)
	for b := 0; b < n; b++ {
		f0 := math.Exp(lo + (hi-lo)*float64(b)/float64(n))
		f1 := math.Exp(lo + (hi-lo)*float64(b+1)/float64(n))
		i0 := int(f0 / float64(s.rate) * fftSize)
		i1 := int(f1 / float64(s.rate) * fftSize)
		if i1 <= i0 {
			i1 = i0 + 1
		}
		var peak float64
		for i := i0; i < i1 && i < bins; i++ {
			peak = math.Max(peak, mag[i])
		}
		// -60 dB .. 0 dB onto 0..1, with a gentle tilt so highs are visible.
		db := 20*math.Log10(peak+1e-9) + 3*float64(b)/float64(n)*4
		out[b] = math.Max(0, math.Min(1, (db+60)/60))
	}
	return out
}

// fft is an in-place radix-2 Cooley–Tukey transform; len(x) must be a power of two.
func fft(x []complex128) {
	n := len(x)
	for i, j := 1, 0; i < n; i++ {
		bit := n >> 1
		for ; j&bit != 0; bit >>= 1 {
			j ^= bit
		}
		j ^= bit
		if i < j {
			x[i], x[j] = x[j], x[i]
		}
	}
	for size := 2; size <= n; size <<= 1 {
		step := cmplx.Exp(complex(0, -2*math.Pi/float64(size)))
		for start := 0; start < n; start += size {
			w := complex(1, 0)
			for k := 0; k < size/2; k++ {
				u := x[start+k]
				v := x[start+k+size/2] * w
				x[start+k] = u + v
				x[start+k+size/2] = u - v
				w *= step
			}
		}
	}
}
