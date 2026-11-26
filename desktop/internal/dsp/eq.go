package dsp

import (
	"math"
	"sync"
)

// Bands are the centre frequencies of the 10 equalizer sliders, the same as on Android.
var Bands = [10]float64{60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000}

const (
	bandQ          = 1.41
	MaxDb          = 12
	boostHeadroom  = -3.0
	boostLowHz     = 105.0
	boostLowDb     = 4.0
	boostHighHz    = 9000.0
	boostHighDb    = 3.0
	clipKnee       = 0.9
)

// EqSettings is everything on the EQ panel.
type EqSettings struct {
	Enabled bool        `json:"enabled"`
	Preamp  float64     `json:"preamp"`
	Bands   [10]float64 `json:"bands"`
	Boost   bool        `json:"boost"`
	Preset  string      `json:"preset"`
}

// Transparent reports whether the settings leave the sound untouched.
func (s EqSettings) Transparent() bool {
	if s.Boost {
		return false
	}
	if !s.Enabled {
		return true
	}
	if s.Preamp != 0 {
		return false
	}
	for _, b := range s.Bands {
		if b != 0 {
			return false
		}
	}
	return true
}

// Presets are the built-in EQ curves, in dB per band from 60 Hz to 16 kHz.
var Presets = []struct {
	Name  string
	Bands [10]float64
}{
	{"Flat", [10]float64{}},
	{"Bass", [10]float64{7, 6, 4, 2, 0, 0, 0, 0, 0, 0}},
	{"Rock", [10]float64{5, 3, -2, -4, -1, 2, 5, 6, 6, 6}},
	{"Pop", [10]float64{-1, 2, 4, 5, 3, 0, -1, -1, -1, -1}},
	{"Vocal", [10]float64{-3, -2, 0, 3, 5, 5, 3, 1, 0, -1}},
	{"Dance", [10]float64{6, 5, 2, 0, 0, -3, -4, -4, 0, 0}},
	{"Treble", [10]float64{0, 0, 0, 0, 0, 2, 5, 7, 8, 8}},
}

// PresetBands returns a preset's curve by name.
func PresetBands(name string) ([10]float64, bool) {
	for _, p := range Presets {
		if p.Name == name {
			return p.Bands, true
		}
	}
	return [10]float64{}, false
}

// Equalizer is the Winamp-style 10-band EQ with preamp and the optional "Boost"
// loudness curve. Update may be called from any goroutine while Process runs.
type Equalizer struct {
	mu         sync.Mutex
	rate, ch   int
	bands      [10]*Biquad
	active     [10]bool
	low, high  *Biquad
	preGain    float64
	boost      bool
	bypass     bool
	applied    *EqSettings
}

// NewEqualizer builds an EQ for interleaved audio at sampleRate with ch channels.
func NewEqualizer(sampleRate, ch int) *Equalizer {
	e := &Equalizer{rate: sampleRate, ch: ch, low: NewBiquad(ch), high: NewBiquad(ch), preGain: 1, bypass: true}
	for i := range e.bands {
		e.bands[i] = NewBiquad(ch)
	}
	return e
}

// Update applies new settings.
func (e *Equalizer) Update(s EqSettings) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.applied != nil && *e.applied == s {
		return
	}
	cp := s
	e.applied = &cp
	e.bypass = s.Transparent()
	guard := float64(e.rate) * 0.45
	for i, f := range Bands {
		db := 0.0
		if s.Enabled {
			db = s.Bands[i]
		}
		e.active[i] = db != 0 && f < guard
		if e.active[i] {
			e.bands[i].SetPeaking(e.rate, f, bandQ, db)
		}
	}
	e.boost = s.Boost
	gain := 0.0
	if s.Enabled {
		gain = s.Preamp
	}
	if e.boost {
		gain += boostHeadroom
		e.low.SetLowShelf(e.rate, boostLowHz, boostLowDb)
		e.high.SetHighShelf(e.rate, math.Min(boostHighHz, guard), boostHighDb)
	}
	e.preGain = math.Pow(10, gain/20)
}

// Process filters interleaved samples in place.
func (e *Equalizer) Process(buf []float32) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.bypass {
		return
	}
	for i := range buf {
		c := i % e.ch
		x := float64(buf[i]) * e.preGain
		if e.boost {
			x = e.low.Process(x, c)
			x = e.high.Process(x, c)
		}
		for b := range e.bands {
			if e.active[b] {
				x = e.bands[b].Process(x, c)
			}
		}
		buf[i] = float32(SoftClip(x))
	}
}

// Reset clears filter memory (after a seek or a track change).
func (e *Equalizer) Reset() {
	e.mu.Lock()
	defer e.mu.Unlock()
	for _, b := range e.bands {
		b.Reset()
	}
	e.low.Reset()
	e.high.Reset()
}

// Curve returns the EQ's total response in dB at each of the given frequencies,
// for drawing the line above the sliders.
func Curve(s EqSettings, sampleRate int, freqs []float64) []float64 {
	e := NewEqualizer(sampleRate, 1)
	e.Update(s)
	out := make([]float64, len(freqs))
	pre := 20 * math.Log10(e.preGain)
	for i, f := range freqs {
		db := pre
		if e.boost {
			db += e.low.Response(sampleRate, f) + e.high.Response(sampleRate, f)
		}
		for b := range e.bands {
			if e.active[b] {
				db += e.bands[b].Response(sampleRate, f)
			}
		}
		out[i] = db
	}
	return out
}

// SoftClip is linear below the knee and saturates smoothly to 1 above it.
func SoftClip(x float64) float64 {
	ax := math.Abs(x)
	if ax <= clipKnee {
		return x
	}
	over := (ax - clipKnee) / (1 - clipKnee)
	return math.Copysign(clipKnee+(1-clipKnee)*math.Tanh(over), x)
}
