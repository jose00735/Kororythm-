package app

import (
	"encoding/json"
	"sync"
)

// Hub fans events out to every open window.
type Hub struct {
	mu   sync.Mutex
	subs map[chan []byte]bool
}

// NewHub makes an empty hub.
func NewHub() *Hub { return &Hub{subs: map[chan []byte]bool{}} }

// Subscribe returns a channel of JSON messages {"t": kind, "d": data}.
func (h *Hub) Subscribe() chan []byte {
	c := make(chan []byte, 64)
	h.mu.Lock()
	h.subs[c] = true
	h.mu.Unlock()
	return c
}

// Unsubscribe stops a subscription.
func (h *Hub) Unsubscribe(c chan []byte) {
	h.mu.Lock()
	if h.subs[c] {
		delete(h.subs, c)
		close(c)
	}
	h.mu.Unlock()
}

// Count is the number of listeners.
func (h *Hub) Count() int { h.mu.Lock(); defer h.mu.Unlock(); return len(h.subs) }

// Send delivers an event; slow listeners miss frames rather than block playback.
func (h *Hub) Send(kind string, data any) {
	b, err := json.Marshal(map[string]any{"t": kind, "d": data})
	if err != nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.subs {
		select {
		case c <- b:
		default:
		}
	}
}
