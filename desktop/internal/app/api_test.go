package app

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/jose00735/kororythm/desktop/internal/player"
	"github.com/jose00735/kororythm/desktop/internal/winmgr"
)

type nopHost struct{}

func (nopHost) SetBounds(string, winmgr.Rect) {}
func (nopHost) Show(string)                   {}
func (nopHost) Hide(string)                   {}

func copyFile(t *testing.T, from, to string) {
	b, err := os.ReadFile(from)
	if err != nil {
		t.Fatal(err)
	}
	os.MkdirAll(filepath.Dir(to), 0o755)
	if err := os.WriteFile(to, b, 0o644); err != nil {
		t.Fatal(err)
	}
}

// The windows' main journey through the API: scan, browse, play an album,
// queue a song to play next, change the EQ and drag a window.
func TestAPIJourney(t *testing.T) {
	dir := t.TempDir()
	music := filepath.Join(dir, "music")
	copyFile(t, "../audio/testdata/head.flac", filepath.Join(music, "a", "1.flac"))
	copyFile(t, "../audio/testdata/tail.flac", filepath.Join(music, "a", "2.flac"))
	copyFile(t, "../audio/testdata/t.mp3", filepath.Join(music, "b", "x.mp3"))

	a, err := New(filepath.Join(dir, "data"), nopHost{})
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	a.Lib.SetFolders([]string{music})
	a.Lib.Scan()
	for i := 0; i < 100 && (a.Lib.Count() < 3 || a.Lib.Scanning()); i++ {
		time.Sleep(20 * time.Millisecond)
	}
	srv := httptest.NewServer(a.Handler(nil))
	defer srv.Close()

	get := func(path string, v any) {
		r, err := http.Get(srv.URL + path)
		if err != nil || r.StatusCode != 200 {
			t.Fatalf("GET %s: %v %v", path, err, r.Status)
		}
		defer r.Body.Close()
		if err := json.NewDecoder(r.Body).Decode(v); err != nil {
			t.Fatal(err)
		}
	}
	post := func(path string, body any) int {
		b, _ := json.Marshal(body)
		r, err := http.Post(srv.URL+path, "application/json", bytes.NewReader(b))
		if err != nil {
			t.Fatal(err)
		}
		io.Copy(io.Discard, r.Body)
		r.Body.Close()
		return r.StatusCode
	}

	var songs []struct{ ID, Codec string }
	get("/api/lib/songs", &songs)
	if len(songs) != 3 {
		t.Fatalf("library has %d songs, want 3", len(songs))
	}
	var flacs, mp3 []string
	for _, s := range songs {
		if s.Codec == "FLAC" {
			flacs = append(flacs, s.ID)
		} else {
			mp3 = append(mp3, s.ID)
		}
	}
	if post("/api/cmd", map[string]any{"op": "playTracks", "ids": flacs, "i": 0, "source": "Test album"}) != 200 {
		t.Fatal("playTracks failed")
	}
	if post("/api/cmd", map[string]any{"op": "enqueue", "ids": mp3, "next": true}) != 200 {
		t.Fatal("enqueue failed")
	}
	var st player.State
	get("/api/state", &st)
	if len(st.Queue) != 3 || !st.Queue[1].Queued || st.Queue[1].Codec != "MP3" || st.Source != "Test album" {
		t.Fatalf("queue after play next = %+v", st.Queue)
	}
	if st.Track == nil || st.Track.ID != flacs[0] {
		t.Fatalf("not playing the first song: %+v", st.Track)
	}
	if post("/api/cmd", map[string]any{"op": "preset", "name": "Rock"}) != 200 {
		t.Fatal("preset failed")
	}
	if post("/api/cmd", map[string]any{"op": "nonsense"}) != 400 {
		t.Fatal("unknown command should be refused")
	}
	get("/api/state", &st)
	if st.Eq.Preset != "Rock" || st.Eq.Bands[0] != 5 {
		t.Fatalf("eq = %+v", st.Eq)
	}
	var curve []float64
	b, _ := json.Marshal(st.Eq)
	r, _ := http.Post(srv.URL+"/api/eq/curve", "application/json", bytes.NewReader(b))
	json.NewDecoder(r.Body).Decode(&curve)
	if len(curve) != 96 {
		t.Fatalf("curve has %d points", len(curve))
	}
	post("/api/win", map[string]any{"op": "dragStart", "name": "player"})
	post("/api/win", map[string]any{"op": "dragMove", "dx": 40, "dy": 0})
	post("/api/win", map[string]any{"op": "dragEnd"})
	if rr, _ := a.Win.Bounds("playlist"); rr.X != Windows["playlist"].X+40 {
		t.Fatalf("docked playlist did not follow the player: %+v", rr)
	}
	resp, _ := http.Get(srv.URL + "/api/art/nope")
	if resp.StatusCode != 404 {
		t.Fatalf("missing art should 404, got %d", resp.StatusCode)
	}
}
