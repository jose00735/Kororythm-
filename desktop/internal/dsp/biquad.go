// Package dsp holds Kororythm's signal processing: the 10-band equalizer and the
// spectrum analyser that drives the visualizers. It is a port of the Android
// app's dsp package, so both apps sound and look the same.
package dsp

import "math"

// Biquad is one second-order IIR filter (RBJ audio-EQ cookbook) in transposed
// direct form II, with its own state per channel.
type Biquad struct {
	b0, b1, b2, a1, a2 float64
	z1, z2             []float64
}

// NewBiquad returns a pass-through filter for the given number of channels.
func NewBiquad(channels int) *Biquad {
	return &Biquad{b0: 1, z1: make([]float64, channels), z2: make([]float64, channels)}
}

// SetPeaking makes the filter a peaking EQ at freq with quality q and gain in dB.
func (f *Biquad) SetPeaking(sampleRate int, freq, q, gainDb float64) {
	a := math.Pow(10, gainDb/40)
	w0 := 2 * math.Pi * freq / float64(sampleRate)
	alpha := math.Sin(w0) / (2 * q)
	cosw := math.Cos(w0)
	f.set(1+alpha*a, -2*cosw, 1-alpha*a, 1+alpha/a, -2*cosw, 1-alpha/a)
}

// SetLowShelf makes the filter a low shelf (slope 1) at freq.
func (f *Biquad) SetLowShelf(sampleRate int, freq, gainDb float64) { f.shelf(sampleRate, freq, gainDb, true) }

// SetHighShelf makes the filter a high shelf (slope 1) at freq.
func (f *Biquad) SetHighShelf(sampleRate int, freq, gainDb float64) { f.shelf(sampleRate, freq, gainDb, false) }

func (f *Biquad) shelf(sampleRate int, freq, gainDb float64, low bool) {
	a := math.Pow(10, gainDb/40)
	w0 := 2 * math.Pi * freq / float64(sampleRate)
	cosw := math.Cos(w0)
	alpha := math.Sin(w0) / 2 * math.Sqrt2
	t := 2 * math.Sqrt(a) * alpha
	if low {
		f.set(
			a*((a+1)-(a-1)*cosw+t), 2*a*((a-1)-(a+1)*cosw), a*((a+1)-(a-1)*cosw-t),
			(a+1)+(a-1)*cosw+t, -2*((a-1)+(a+1)*cosw), (a+1)+(a-1)*cosw-t,
		)
		return
	}
	f.set(
		a*((a+1)+(a-1)*cosw+t), -2*a*((a-1)+(a+1)*cosw), a*((a+1)+(a-1)*cosw-t),
		(a+1)-(a-1)*cosw+t, 2*((a-1)-(a+1)*cosw), (a+1)-(a-1)*cosw-t,
	)
}

func (f *Biquad) set(b0, b1, b2, a0, a1, a2 float64) {
	f.b0, f.b1, f.b2 = b0/a0, b1/a0, b2/a0
	f.a1, f.a2 = a1/a0, a2/a0
}

// Process filters one sample of channel ch.
func (f *Biquad) Process(x float64, ch int) float64 {
	y := f.b0*x + f.z1[ch]
	f.z1[ch] = f.b1*x - f.a1*y + f.z2[ch]
	f.z2[ch] = f.b2*x - f.a2*y
	return y
}

// Reset clears the filter's memory.
func (f *Biquad) Reset() {
	for i := range f.z1 {
		f.z1[i], f.z2[i] = 0, 0
	}
}

// Response returns the filter's gain in dB at freq, for drawing the EQ curve.
func (f *Biquad) Response(sampleRate int, freq float64) float64 {
	w := 2 * math.Pi * freq / float64(sampleRate)
	// H(e^jw) = (b0 + b1 e^-jw + b2 e^-2jw) / (1 + a1 e^-jw + a2 e^-2jw)
	nr := f.b0 + f.b1*math.Cos(w) + f.b2*math.Cos(2*w)
	ni := -f.b1*math.Sin(w) - f.b2*math.Sin(2*w)
	dr := 1 + f.a1*math.Cos(w) + f.a2*math.Cos(2*w)
	di := -f.a1*math.Sin(w) - f.a2*math.Sin(2*w)
	return 10 * math.Log10((nr*nr+ni*ni)/(dr*dr+di*di))
}
