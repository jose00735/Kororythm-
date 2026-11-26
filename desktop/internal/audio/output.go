package audio

// Output plays what the engine renders.
type Output interface {
	Close() error
}

// render is the callback an output pulls samples from.
type render func([]float32)
