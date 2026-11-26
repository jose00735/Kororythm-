// Package winmgr places Kororythm's floating windows the way Winamp did: they
// snap to each other and to the screen edges, windows docked to the player move
// with it, and any window can be "shaded" down to its title strip.
//
// It works in screen coordinates (device-independent pixels) and knows nothing
// about Wails, so it can be tested on its own; a Host applies the result.
package winmgr

import (
	"sort"
	"sync"
)

// Rect is a window's bounds.
type Rect struct{ X, Y, W, H int }

func (r Rect) right() int  { return r.X + r.W }
func (r Rect) bottom() int { return r.Y + r.H }

// Host moves real windows.
type Host interface {
	SetBounds(name string, r Rect)
	Show(name string)
	Hide(name string)
}

// Snap is how close (in pixels) an edge has to come before it snaps.
const Snap = 12

// ShadeHeight is a shaded window's height: just its title strip.
const ShadeHeight = 26

// Leader is the window that drags the docked group.
const Leader = "player"

type win struct {
	r      Rect
	fullH  int
	open   bool
	shaded bool
}

// Layouts say which windows belong to which layout. Windows not listed (the
// library, downloads, and the iPod browser) can float over any layout.
var Layouts = map[string][]string{
	"classic": {"player", "eq", "playlist"},
	"pod":     {"pod"},
	"prism":   {"prism"},
	"cover":   {"cover"},
}

// Main is each layout's own window, which is always shown in that layout.
var Main = map[string]string{"classic": "player", "pod": "pod", "prism": "prism", "cover": "cover"}

// Manager tracks the windows.
type Manager struct {
	mu     sync.Mutex
	layout string
	wins   map[string]*win
	order  []string
	screen []Rect // work areas of the monitors
	host   Host
	drag   *drag
	saved  func(map[string]State)
}

type drag struct {
	name  string
	group []string
	start map[string]Rect
}

// State is a window's place, for saving.
type State struct {
	Rect
	Open   bool
	Shaded bool
	FullH  int
}

// New makes a manager with the given windows' default places.
func New(host Host, defaults map[string]State) *Manager {
	m := &Manager{wins: map[string]*win{}, host: host, layout: "classic"}
	for name, s := range defaults {
		m.wins[name] = &win{r: s.Rect, fullH: max(s.FullH, s.H), open: s.Open, shaded: s.Shaded}
		m.order = append(m.order, name)
	}
	sort.Strings(m.order)
	return m
}

// OnSaved is called whenever windows settle in new places.
func (m *Manager) OnSaved(f func(map[string]State)) { m.mu.Lock(); m.saved = f; m.mu.Unlock() }

// SetScreens gives the monitors' work areas (desktop minus taskbar).
func (m *Manager) SetScreens(work []Rect) { m.mu.Lock(); m.screen = work; m.mu.Unlock() }

// Restore applies saved places, keeping each window on a visible screen.
func (m *Manager) Restore(saved map[string]State) {
	m.mu.Lock()
	for name, s := range saved {
		w, ok := m.wins[name]
		if !ok || s.W <= 0 {
			continue
		}
		w.r, w.open, w.shaded = s.Rect, s.Open, s.Shaded
		w.fullH = max(s.FullH, w.fullH)
		if !w.shaded && w.r.H > 0 {
			w.fullH = w.r.H
		}
		m.keepOnScreen(w)
	}
	m.mu.Unlock()
}

// keepOnScreen pulls a window back if its title strip is off every screen.
func (m *Manager) keepOnScreen(w *win) {
	if len(m.screen) == 0 {
		return
	}
	for _, s := range m.screen {
		if w.r.X+40 < s.right() && w.r.right()-40 > s.X && w.r.Y >= s.Y-4 && w.r.Y+ShadeHeight <= s.bottom() {
			return
		}
	}
	s := m.screen[0]
	w.r.X = min(max(w.r.X, s.X), s.right()-w.r.W)
	w.r.Y = min(max(w.r.Y, s.Y), s.bottom()-ShadeHeight)
}

// Bounds returns a window's place.
func (m *Manager) Bounds(name string) (Rect, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	w, ok := m.wins[name]
	if !ok {
		return Rect{}, false
	}
	return w.r, true
}

// States returns every window's place.
func (m *Manager) States() map[string]State {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.statesLocked()
}

func (m *Manager) statesLocked() map[string]State {
	out := map[string]State{}
	for n, w := range m.wins {
		out[n] = State{Rect: w.r, Open: w.open, Shaded: w.shaded, FullH: w.fullH}
	}
	return out
}

func (m *Manager) persist() {
	if m.saved != nil {
		s := m.statesLocked()
		f := m.saved
		go f(s)
	}
}

// owner returns the layout a window belongs to, or "" for floating windows.
func owner(name string) string {
	for l, ws := range Layouts {
		for _, w := range ws {
			if w == name {
				return l
			}
		}
	}
	return ""
}

// visible reports whether a window shows in the current layout. Caller holds mu.
func (m *Manager) visible(name string) bool {
	w := m.wins[name]
	if w == nil {
		return false
	}
	if Main[m.layout] == name {
		return true
	}
	if o := owner(name); o != "" && o != m.layout {
		// The iPod window doubles as a library browser over the other layouts.
		return name == "pod" && w.open
	}
	return w.open
}

// Layout returns the current layout.
func (m *Manager) Layout() string { m.mu.Lock(); defer m.mu.Unlock(); return m.layout }

// SetLayout switches layout and shows or hides windows to match.
func (m *Manager) SetLayout(l string) {
	m.mu.Lock()
	if _, ok := Layouts[l]; !ok {
		m.mu.Unlock()
		return
	}
	m.layout = l
	if w := m.wins["pod"]; w != nil && l == "pod" {
		w.open = true
	} else if w != nil {
		w.open = false // the browser closes when leaving the iPod layout
	}
	m.mu.Unlock()
	m.ApplyAll()
	m.mu.Lock()
	m.persist()
	m.mu.Unlock()
}

// ApplyAll pushes every window's place to the host.
func (m *Manager) ApplyAll() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, n := range m.order {
		w := m.wins[n]
		m.host.SetBounds(n, w.r)
		if m.visible(n) {
			m.host.Show(n)
		} else {
			m.host.Hide(n)
		}
	}
}

// touches reports whether a and b share an edge (docked), within 1 px.
func touches(a, b Rect) bool {
	overlapY := a.Y < b.bottom() && b.Y < a.bottom()
	overlapX := a.X < b.right() && b.X < a.right()
	near := func(p, q int) bool { return p-q <= 1 && q-p <= 1 }
	return (overlapY && (near(a.right(), b.X) || near(b.right(), a.X))) ||
		(overlapX && (near(a.bottom(), b.Y) || near(b.bottom(), a.Y)))
}

// dockedTo returns the open windows joined (directly or through others) to name.
func (m *Manager) dockedTo(name string) []string {
	group := []string{name}
	in := map[string]bool{name: true}
	for i := 0; i < len(group); i++ {
		a := m.wins[group[i]]
		for _, n := range m.order {
			b := m.wins[n]
			if in[n] || !m.visible(n) {
				continue
			}
			if touches(a.r, b.r) {
				in[n] = true
				group = append(group, n)
			}
		}
	}
	return group
}

// DragStart begins moving a window. Dragging the player takes its docked windows along.
func (m *Manager) DragStart(name string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.wins[name]; !ok {
		return
	}
	d := &drag{name: name, start: map[string]Rect{}}
	if name == Leader && m.layout == "classic" {
		d.group = m.dockedTo(name)
	} else {
		d.group = []string{name}
	}
	for _, n := range d.group {
		d.start[n] = m.wins[n].r
	}
	m.drag = d
}

// DragMove moves the dragged window by (dx, dy) from where the drag began,
// snapping to other windows and screen edges.
func (m *Manager) DragMove(dx, dy int) {
	m.mu.Lock()
	defer m.mu.Unlock()
	d := m.drag
	if d == nil {
		return
	}
	lead := d.start[d.name]
	cand := Rect{lead.X + dx, lead.Y + dy, lead.W, m.wins[d.name].r.H}
	sx, sy := m.snap(cand, d.group)
	for _, n := range d.group {
		s := d.start[n]
		w := m.wins[n]
		w.r.X, w.r.Y = s.X+dx+sx, s.Y+dy+sy
		m.host.SetBounds(n, w.r)
	}
}

// DragEnd finishes a drag and saves places.
func (m *Manager) DragEnd() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.drag = nil
	m.persist()
}

// snap returns the nudge that lines r up with a nearby edge, ignoring the moving group.
func (m *Manager) snap(r Rect, group []string) (int, int) {
	moving := map[string]bool{}
	for _, n := range group {
		moving[n] = true
	}
	bestX, bestY := Snap+1, Snap+1
	try := func(best *int, want, have int) {
		if d := want - have; abs(d) < abs(*best) {
			*best = d
		}
	}
	for _, n := range m.order {
		o := m.wins[n]
		if moving[n] || !m.visible(n) {
			continue
		}
		b := o.r
		// Only snap to windows that are beside us on the other axis.
		if r.Y < b.bottom()+Snap && b.Y < r.bottom()+Snap {
			try(&bestX, b.right(), r.X)  // our left to their right
			try(&bestX, b.X, r.right())  // our right to their left
			try(&bestX, b.X, r.X)        // left edges line up
			try(&bestX, b.right(), r.right())
		}
		if r.X < b.right()+Snap && b.X < r.right()+Snap {
			try(&bestY, b.bottom(), r.Y) // our top to their bottom
			try(&bestY, b.Y, r.bottom()) // our bottom to their top
			try(&bestY, b.Y, r.Y)
			try(&bestY, b.bottom(), r.bottom())
		}
	}
	for _, s := range m.screen {
		try(&bestX, s.X, r.X)
		try(&bestX, s.right(), r.right())
		try(&bestY, s.Y, r.Y)
		try(&bestY, s.bottom(), r.bottom())
	}
	if abs(bestX) > Snap {
		bestX = 0
	}
	if abs(bestY) > Snap {
		bestY = 0
	}
	return bestX, bestY
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// Resize changes a window's size (the playlist and library can grow).
func (m *Manager) Resize(name string, w, h int) {
	m.mu.Lock()
	defer m.mu.Unlock()
	win, ok := m.wins[name]
	if !ok || win.shaded {
		return
	}
	win.r.W, win.r.H, win.fullH = max(w, 200), max(h, 80), max(h, 80)
	m.host.SetBounds(name, win.r)
	m.persist()
}

// Shade toggles a window between full height and its title strip. Windows
// docked below it move up or down with it, so the stack stays together.
func (m *Manager) Shade(name string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	w, ok := m.wins[name]
	if !ok {
		return false
	}
	below := []string{}
	for _, n := range m.dockedTo(name) {
		if n != name && m.wins[n].r.Y >= w.r.bottom()-1 {
			below = append(below, n)
		}
	}
	old := w.r.H
	if w.shaded {
		w.r.H = w.fullH
	} else {
		w.fullH = w.r.H
		w.r.H = ShadeHeight
	}
	w.shaded = !w.shaded
	delta := w.r.H - old
	m.host.SetBounds(name, w.r)
	for _, n := range below {
		b := m.wins[n]
		b.r.Y += delta
		m.host.SetBounds(n, b.r)
	}
	m.persist()
	return w.shaded
}

// SetOpen shows or hides a window.
func (m *Manager) SetOpen(name string, open bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	w, ok := m.wins[name]
	if !ok {
		return
	}
	w.open = open
	if m.visible(name) {
		m.host.SetBounds(name, w.r)
		m.host.Show(name)
	} else {
		m.host.Hide(name)
	}
	m.persist()
}

// IsOpen reports whether a window is showing.
func (m *Manager) IsOpen(name string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.visible(name)
}

// Shaded reports whether a window is shaded.
func (m *Manager) Shaded(name string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	w, ok := m.wins[name]
	return ok && w.shaded
}
