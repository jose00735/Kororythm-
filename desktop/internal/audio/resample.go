package audio

import "math"

// resampler converts interleaved stereo float audio from one rate to another
// with a windowed-sinc (Lanczos) kernel. When downsampling, the kernel is
// widened so it also acts as the anti-aliasing filter.
type resampler struct {
	in, out int
	step    float64 // input frames per output frame
	pos     float64 // read position in hist, in frames
	hist    []float32
	taps    int
	scale   float64
}

const lanczosA = 8

func newResampler(in, out int) *resampler {
	r := &resampler{in: in, out: out, step: float64(in) / float64(out), scale: 1}
	if r.step > 1 {
		r.scale = 1 / r.step
	}
	r.taps = int(math.Ceil(lanczosA / r.scale))
	// Start with silence before the first sample so the kernel has history.
	r.hist = make([]float32, 2*r.taps*2)
	r.pos = float64(r.taps)
	return r
}

// passthrough reports whether the rates match (no work to do).
func (r *resampler) passthrough() bool { return r.in == r.out }

func lanczos(x float64) float64 {
	if x == 0 {
		return 1
	}
	if x <= -lanczosA || x >= lanczosA {
		return 0
	}
	px := math.Pi * x
	return lanczosA * math.Sin(px) * math.Sin(px/lanczosA) / (px * px)
}

// push adds input frames (interleaved stereo).
func (r *resampler) push(src []float32) { r.hist = append(r.hist, src...) }

// available is how many output frames can be produced without more input.
func (r *resampler) available() int {
	have := float64(len(r.hist)/2-r.taps) - r.pos
	if have <= 0 {
		return 0
	}
	return int(have / r.step)
}

// pull writes up to len(dst)/2 output frames and returns how many it wrote.
func (r *resampler) pull(dst []float32) int {
	n := min(len(dst)/2, r.available())
	for o := 0; o < n; o++ {
		centre := r.pos
		base := int(math.Floor(centre))
		var l, rr, wsum float64
		for k := base - r.taps + 1; k <= base+r.taps; k++ {
			w := lanczos((centre - float64(k)) * r.scale)
			l += float64(r.hist[2*k]) * w
			rr += float64(r.hist[2*k+1]) * w
			wsum += w
		}
		if wsum != 0 {
			l /= wsum
			rr /= wsum
		}
		dst[2*o], dst[2*o+1] = float32(l), float32(rr)
		r.pos += r.step
	}
	// Drop history the kernel no longer needs.
	if drop := int(r.pos) - r.taps; drop > 4096 {
		r.hist = append(r.hist[:0], r.hist[2*drop:]...)
		r.pos -= float64(drop)
	}
	return n
}
