package player

import (
	"encoding/json"
	"sync"
	"time"

	"github.com/jose00735/kororythm/desktop/internal/audio"
	"github.com/jose00735/kororythm/desktop/internal/dsp"
	"github.com/jose00735/kororythm/desktop/internal/library"
	"github.com/jose00735/kororythm/desktop/internal/settings"
)

// Entry is one row of the playlist window.
type Entry struct {
	ID         string `json:"id"`
	Title      string `json:"title"`
	Artist     string `json:"artist"`
	DurationMs int64  `json:"durationMs"`
	Codec      string `json:"codec"`
	Queued     bool   `json:"queued,omitempty"`
	Missing    bool   `json:"missing,omitempty"`
}

// State is what the windows draw.
type State struct {
	Playing    bool           `json:"playing"`
	PositionMs int64          `json:"positionMs"`
	DurationMs int64          `json:"durationMs"`
	Volume     float64        `json:"volume"`
	Shuffle    bool           `json:"shuffle"`
	Repeat     string         `json:"repeat"`
	Cur        int            `json:"cur"`
	Source     string         `json:"source,omitempty"`
	Track      *library.Track `json:"track,omitempty"`
	Queue      []Entry        `json:"queue"`
	Eq         dsp.EqSettings `json:"eq"`
	Skin       string         `json:"skin"`
	Layout     string         `json:"layout"`
	Crossfade  int            `json:"crossfadeMs"`
	AlwaysTop  bool           `json:"alwaysOnTop"`
	Songs      int            `json:"songs"`
	Scanning   bool           `json:"scanning"`
	Message    string         `json:"message,omitempty"`
}

// Player is the music player: queue + engine + settings.
type Player struct {
	mu       sync.Mutex
	lib      *library.Library
	eng      *audio.Engine
	set      *settings.Store
	q        Queue
	pending  int
	message  string
	onChange func()
}

// New wires a player to a library, engine and settings, restoring the last queue.
func New(lib *library.Library, eng *audio.Engine, set *settings.Store) *Player {
	p := &Player{lib: lib, eng: eng, set: set, pending: -1}
	p.q.Cur = -1
	p.q.Repeat = RepeatOff
	d := set.Get()
	if len(d.Queue) > 0 {
		_ = json.Unmarshal(d.Queue, &p.q)
		p.q.reshuffle()
	}
	eng.SetVolume(d.Volume)
	eng.SetEq(d.Eq)
	eng.SetCrossfade(d.Crossfade)
	eng.SetNextFunc(p.nextForEngine)
	go p.events()
	return p
}

// OnChange registers a callback for state changes.
func (p *Player) OnChange(f func()) { p.mu.Lock(); p.onChange = f; p.mu.Unlock() }

func (p *Player) changed() {
	p.mu.Lock()
	f := p.onChange
	b, _ := json.Marshal(&p.q)
	p.mu.Unlock()
	p.set.Update(func(d *settings.Data) { d.Queue = b })
	if f != nil {
		f()
	}
}

func (p *Player) nextForEngine() (string, string, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for tries := 0; tries < len(p.q.Items); tries++ {
		i, ok := p.q.PeekNext()
		if !ok {
			p.pending = -1
			return "", "", false
		}
		if t, ok := p.lib.Get(p.q.Items[i].ID); ok {
			p.pending = i
			return t.ID, t.Path, true
		}
		return "", "", false
	}
	return "", "", false
}

func (p *Player) events() {
	for ev := range p.eng.Events() {
		switch ev.Kind {
		case "start":
			p.mu.Lock()
			if cur, ok := p.q.Current(); !ok || cur.ID != ev.ID {
				if p.pending >= 0 && p.pending < len(p.q.Items) && p.q.Items[p.pending].ID == ev.ID {
					p.q.Cur = p.pending
				}
			}
			p.pending = -1
			p.message = ""
			p.mu.Unlock()
			// Count a play once the song has really been listened to for a bit.
			id := ev.ID
			time.AfterFunc(15*time.Second, func() {
				if st := p.eng.Status(); st.ID == id && st.PositionMs >= 10000 {
					p.lib.Played(id)
				}
			})
			p.changed()
		case "ended":
			p.changed()
		case "error":
			p.mu.Lock()
			p.message = "Can't play this file: " + ev.Err.Error()
			p.mu.Unlock()
			p.changed()
		}
	}
}

// playIndex starts item i now. Caller must not hold mu.
func (p *Player) playIndex(i int) {
	p.mu.Lock()
	if i < 0 || i >= len(p.q.Items) {
		p.mu.Unlock()
		return
	}
	p.q.Cur = i
	id := p.q.Items[i].ID
	p.mu.Unlock()
	t, ok := p.lib.Get(id)
	if !ok {
		p.mu.Lock()
		p.message = "That song is no longer in the library"
		p.mu.Unlock()
		p.changed()
		return
	}
	_ = p.eng.Play(t.ID, t.Path)
	p.changed()
}

// PlayTracks replaces the queue and plays from start.
func (p *Player) PlayTracks(ids []string, start int, source string) {
	p.mu.Lock()
	p.q.Set(ids, start, source)
	cur := p.q.Cur
	p.mu.Unlock()
	p.playIndex(cur)
}

// Enqueue adds songs on the fly (see Queue.Enqueue). If nothing is loaded, it starts playing.
func (p *Player) Enqueue(ids []string, next bool) {
	p.mu.Lock()
	empty := len(p.q.Items) == 0
	p.q.Enqueue(ids, next)
	p.mu.Unlock()
	if empty {
		p.playIndex(0)
		return
	}
	p.eng.RefreshNext()
	p.changed()
}

// PlayAt jumps to item i.
func (p *Player) PlayAt(i int) { p.playIndex(i) }

// Toggle plays or pauses; with nothing loaded it starts the queue.
func (p *Player) Toggle() {
	if p.eng.Status().ID == "" {
		p.mu.Lock()
		cur := p.q.Cur
		p.mu.Unlock()
		if cur < 0 {
			cur = 0
		}
		p.playIndex(cur)
		return
	}
	p.eng.Toggle()
	p.changed()
}

// Play resumes or starts.
func (p *Player) Play() {
	if p.eng.Status().ID == "" {
		p.Toggle()
		return
	}
	p.eng.Resume()
	p.changed()
}

// Pause pauses.
func (p *Player) Pause() { p.eng.Pause(); p.changed() }

// Stop stops and rewinds.
func (p *Player) Stop() { p.eng.Stop(); p.changed() }

// Next skips forward.
func (p *Player) Next() {
	p.mu.Lock()
	i, ok := p.q.Next()
	p.mu.Unlock()
	if ok {
		p.playIndex(i)
	}
}

// Prev restarts the song if it is past 3 s, otherwise goes back one.
func (p *Player) Prev() {
	if p.eng.Status().PositionMs > 3000 {
		_ = p.eng.SeekMs(0)
		p.changed()
		return
	}
	p.mu.Lock()
	i, ok := p.q.Prev()
	p.mu.Unlock()
	if ok {
		p.playIndex(i)
	}
}

// Seek jumps within the song.
func (p *Player) SeekMs(ms int64) { _ = p.eng.SeekMs(ms); p.changed() }

// SetVolume sets the volume 0..1.
func (p *Player) SetVolume(v float64) {
	p.eng.SetVolume(v)
	p.set.Update(func(d *settings.Data) { d.Volume = v })
	p.changed()
}

// Remove drops queue item i (skipping ahead if it is playing).
func (p *Player) Remove(i int) {
	p.mu.Lock()
	playing := i == p.q.Cur
	p.q.Remove(i)
	cur := p.q.Cur
	p.mu.Unlock()
	if playing {
		if cur >= 0 {
			p.playIndex(min(i, cur+1))
			return
		}
		p.eng.Stop()
	}
	p.eng.RefreshNext()
	p.changed()
}

// Move reorders the queue.
func (p *Player) Move(from, to int) {
	p.mu.Lock()
	p.q.Move(from, to)
	p.mu.Unlock()
	p.eng.RefreshNext()
	p.changed()
}

// Clear empties the queue and stops.
func (p *Player) Clear() {
	p.mu.Lock()
	p.q.Clear()
	p.mu.Unlock()
	p.eng.Stop()
	p.changed()
}

// SetShuffle and CycleRepeat change the play order.
func (p *Player) SetShuffle(on bool) {
	p.mu.Lock()
	p.q.SetShuffle(on)
	p.mu.Unlock()
	p.eng.RefreshNext()
	p.changed()
}

func (p *Player) CycleRepeat() {
	p.mu.Lock()
	p.q.CycleRepeat()
	p.mu.Unlock()
	p.eng.RefreshNext()
	p.changed()
}

// SetEq applies and saves equalizer settings.
func (p *Player) SetEq(s dsp.EqSettings) {
	p.eng.SetEq(s)
	p.set.Update(func(d *settings.Data) { d.Eq = s })
	p.changed()
}

// SetCrossfade sets the crossfade (0 = gapless).
func (p *Player) SetCrossfade(ms int) {
	p.eng.SetCrossfade(ms)
	p.set.Update(func(d *settings.Data) { d.Crossfade = ms })
	p.changed()
}

// SetLook changes skin, layout or always-on-top.
func (p *Player) SetLook(f func(*settings.Data)) { p.set.Update(f); p.changed() }

// Bars returns visualizer bars.
func (p *Player) Bars(n int) []float64 { return p.eng.Bars(n) }

// Tick is the light-weight position update sent several times a second.
type Tick struct {
	Playing    bool  `json:"playing"`
	PositionMs int64 `json:"positionMs"`
	DurationMs int64 `json:"durationMs"`
	At         int64 `json:"at"`
}

// Tick returns the current position.
func (p *Player) Tick() Tick {
	st := p.eng.Status()
	return Tick{Playing: st.Playing, PositionMs: st.PositionMs, DurationMs: st.DurationMs, At: time.Now().UnixMilli()}
}

// State returns the full state for the windows.
func (p *Player) State() State {
	st := p.eng.Status()
	d := p.set.Get()
	p.mu.Lock()
	defer p.mu.Unlock()
	s := State{
		Playing: st.Playing, PositionMs: st.PositionMs, DurationMs: st.DurationMs, Volume: d.Volume,
		Shuffle: p.q.Shuffle, Repeat: p.q.Repeat, Cur: p.q.Cur, Source: p.q.Source,
		Eq: d.Eq, Skin: d.Skin, Layout: d.Layout, Crossfade: d.Crossfade, AlwaysTop: d.AlwaysTop,
		Songs: p.lib.Count(), Scanning: p.lib.Scanning(), Message: p.message, Queue: make([]Entry, 0, len(p.q.Items)),
	}
	if s.Repeat == "" {
		s.Repeat = RepeatOff
	}
	for _, it := range p.q.Items {
		e := Entry{ID: it.ID, Queued: it.Queued}
		if t, ok := p.lib.Get(it.ID); ok {
			e.Title, e.Artist, e.DurationMs, e.Codec = t.Title, t.Artist, t.DurationMs, t.Codec
		} else {
			e.Title, e.Missing = "Missing file", true
		}
		s.Queue = append(s.Queue, e)
	}
	if cur, ok := p.q.Current(); ok {
		if t, ok := p.lib.Get(cur.ID); ok {
			s.Track = t
			if s.DurationMs == 0 {
				s.DurationMs = t.DurationMs
			}
		}
	}
	return s
}
