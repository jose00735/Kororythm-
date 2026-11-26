package winmgr

import "testing"

type fakeHost struct{ b map[string]Rect }

func (f *fakeHost) SetBounds(n string, r Rect) { f.b[n] = r }
func (f *fakeHost) Show(string)                {}
func (f *fakeHost) Hide(string)                {}

func stack() (*Manager, *fakeHost) {
	h := &fakeHost{b: map[string]Rect{}}
	m := New(h, map[string]State{
		"player":   {Rect: Rect{100, 100, 460, 200}, Open: true},
		"eq":       {Rect: Rect{100, 300, 460, 200}, Open: true},
		"playlist": {Rect: Rect{100, 500, 460, 320}, Open: true},
		"library":  {Rect: Rect{900, 100, 640, 600}, Open: true},
	})
	m.SetScreens([]Rect{{0, 0, 1920, 1040}})
	return m, h
}

func TestPlayerDragsDockedWindows(t *testing.T) {
	m, _ := stack()
	m.DragStart("player")
	m.DragMove(50, 30)
	m.DragEnd()
	if r, _ := m.Bounds("playlist"); r.X != 150 || r.Y != 530 {
		t.Fatalf("playlist at %v, want it moved with the player to (150,530)", r)
	}
	if r, _ := m.Bounds("library"); r.X != 900 {
		t.Fatalf("library (not docked) moved to %v", r)
	}
}

func TestWindowSnapsToNeighbour(t *testing.T) {
	m, _ := stack()
	// Drag the library so its left edge comes within 8 px of the player's right edge.
	m.DragStart("library")
	m.DragMove(560+8-900, 0)
	m.DragEnd()
	if r, _ := m.Bounds("library"); r.X != 560 {
		t.Fatalf("library left edge at %d, want snapped to 560", r.X)
	}
	// Now it is docked, so dragging the player takes it along.
	m.DragStart("player")
	m.DragMove(10, 0)
	m.DragEnd()
	if r, _ := m.Bounds("library"); r.X != 570 {
		t.Fatalf("docked library at %d after player moved, want 570", r.X)
	}
}

func TestSnapsToScreenEdge(t *testing.T) {
	m, _ := stack()
	m.DragStart("library")
	m.DragMove(1920-640-900-7, -95) // 7 px from the right edge, 5 px from the top
	m.DragEnd()
	if r, _ := m.Bounds("library"); r.X+r.W != 1920 || r.Y != 0 {
		t.Fatalf("library at %v, want flush with the top-right corner", r)
	}
}

func TestShadeLiftsWindowsBelow(t *testing.T) {
	m, _ := stack()
	if !m.Shade("eq") {
		t.Fatal("eq should be shaded")
	}
	if r, _ := m.Bounds("eq"); r.H != ShadeHeight {
		t.Fatalf("shaded eq height %d", r.H)
	}
	if r, _ := m.Bounds("playlist"); r.Y != 300+ShadeHeight {
		t.Fatalf("playlist at y=%d, want it to follow the shaded eq up", r.Y)
	}
	m.Shade("eq")
	if r, _ := m.Bounds("playlist"); r.Y != 500 {
		t.Fatalf("playlist at y=%d after un-shading, want 500", r.Y)
	}
}

func TestRestoreKeepsWindowsOnScreen(t *testing.T) {
	m, _ := stack()
	m.Restore(map[string]State{"library": {Rect: Rect{5000, 3000, 640, 600}, Open: true}})
	if r, _ := m.Bounds("library"); r.X > 1920-640 || r.Y > 1040 {
		t.Fatalf("library restored off screen at %v", r)
	}
}

func TestLayoutsShowTheirWindows(t *testing.T) {
	h := &shownHost{fakeHost: fakeHost{b: map[string]Rect{}}, shown: map[string]bool{}}
	m := New(h, map[string]State{
		"player": {Rect: Rect{0, 0, 460, 180}, Open: true},
		"eq":     {Rect: Rect{0, 180, 460, 196}, Open: true},
		"pod":    {Rect: Rect{600, 0, 330, 560}},
		"prism":  {Rect: Rect{100, 100, 980, 640}},
		"library": {Rect: Rect{500, 0, 600, 600}, Open: true},
	})
	m.SetLayout("prism")
	if h.shown["player"] || h.shown["eq"] || !h.shown["prism"] || !h.shown["library"] {
		t.Fatalf("prism layout shows %v", h.shown)
	}
	m.SetOpen("pod", true) // iPod browser over Prism
	if !h.shown["pod"] {
		t.Fatal("pod browser should float over prism")
	}
	m.SetLayout("classic")
	if !h.shown["player"] || !h.shown["eq"] || h.shown["prism"] || h.shown["pod"] {
		t.Fatalf("classic layout shows %v", h.shown)
	}
}

type shownHost struct {
	fakeHost
	shown map[string]bool
}

func (s *shownHost) Show(n string) { s.shown[n] = true }
func (s *shownHost) Hide(n string) { s.shown[n] = false }
