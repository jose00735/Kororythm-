package app

import (
	"encoding/json"
	"fmt"
	"io/fs"
	"math"
	"net/http"
	"strconv"
	"strings"

	"github.com/jose00735/kororythm/desktop/internal/audio"
	"github.com/jose00735/kororythm/desktop/internal/downloads"
	"github.com/jose00735/kororythm/desktop/internal/dsp"
	"github.com/jose00735/kororythm/desktop/internal/library"
	"github.com/jose00735/kororythm/desktop/internal/settings"
	"github.com/jose00735/kororythm/desktop/internal/winmgr"
)

// Handler serves the windows' pages from ui and the /api routes.
func (a *App) Handler(ui fs.FS) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/state", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, a.Player.State()) })
	mux.HandleFunc("POST /api/cmd", a.cmd)
	mux.HandleFunc("GET /api/events", a.sse)
	mux.HandleFunc("GET /api/lib/artists", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, a.Lib.Artists()) })
	mux.HandleFunc("GET /api/lib/albums", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, a.Lib.Albums(r.URL.Query().Get("artist")))
	})
	mux.HandleFunc("GET /api/lib/genres", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, a.Lib.Genres()) })
	mux.HandleFunc("GET /api/lib/decades", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, a.Lib.Decades()) })
	mux.HandleFunc("GET /api/lib/songs", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		dec, _ := strconv.Atoi(q.Get("decade"))
		writeJSON(w, a.Lib.Songs(library.Filter{Artist: q.Get("artist"), Album: q.Get("album"), Genre: q.Get("genre"), Query: q.Get("q"), Decade: dec}))
	})
	mux.HandleFunc("GET /api/lib/folders", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, a.Lib.Folders()) })
	mux.HandleFunc("POST /api/lib/folders", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Folders []string `json:"folders"`
			Pick    bool     `json:"pick"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		folders := body.Folders
		if body.Pick {
			if a.Hooks.PickFolder == nil {
				http.Error(w, "folder picker not available", http.StatusNotImplemented)
				return
			}
			p, err := a.Hooks.PickFolder()
			if err != nil || p == "" {
				writeJSON(w, a.Lib.Folders())
				return
			}
			folders = append(a.Lib.Folders(), p)
		}
		a.Lib.SetFolders(folders)
		go a.Lib.Scan()
		writeJSON(w, a.Lib.Folders())
	})
	mux.HandleFunc("POST /api/lib/scan", func(w http.ResponseWriter, r *http.Request) {
		go a.Lib.Scan()
		writeJSON(w, map[string]bool{"ok": true})
	})
	mux.HandleFunc("GET /api/art/{id}", func(w http.ResponseWriter, r *http.Request) {
		b, mime, ok := a.Lib.Art(r.PathValue("id"))
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", mime)
		w.Header().Set("Cache-Control", "max-age=3600")
		w.Write(b)
	})
	mux.HandleFunc("GET /api/eq/presets", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, dsp.Presets) })
	mux.HandleFunc("POST /api/eq/curve", func(w http.ResponseWriter, r *http.Request) {
		var s dsp.EqSettings
		_ = json.NewDecoder(r.Body).Decode(&s)
		freqs := make([]float64, 96)
		for i := range freqs {
			freqs[i] = 30 * math.Pow(18000.0/30, float64(i)/float64(len(freqs)-1))
		}
		writeJSON(w, dsp.Curve(s, audio.OutRate, freqs))
	})
	mux.HandleFunc("GET /api/win", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, a.winStates()) })
	mux.HandleFunc("GET /api/layout", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, map[string]string{"layout": a.Win.Layout()}) })
	mux.HandleFunc("POST /api/win", a.win)
	mux.HandleFunc("GET /api/win/bounds", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, a.Win.States()) })
	// Playlists
	mux.HandleFunc("GET /api/playlists", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, a.Lib.Playlists()) })
	mux.HandleFunc("GET /api/playlists/{id}", func(w http.ResponseWriter, r *http.Request) {
		p, ts, ok := a.Lib.Playlist(r.PathValue("id"))
		if !ok {
			http.NotFound(w, r)
			return
		}
		if ts == nil {
			ts = []*library.Track{}
		}
		writeJSON(w, map[string]any{"playlist": p, "tracks": ts})
	})
	mux.HandleFunc("POST /api/playlists", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Name string   `json:"name"`
			IDs  []string `json:"ids"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		id, err := a.Lib.CreatePlaylist(b.Name, b.IDs)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		writeJSON(w, map[string]string{"id": id})
	})
	mux.HandleFunc("POST /api/playlists/{id}/{op}", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			IDs  []string `json:"ids"`
			Name string   `json:"name"`
			I    int      `json:"i"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		id := r.PathValue("id")
		switch r.PathValue("op") {
		case "add":
			n, err := a.Lib.AddToPlaylist(id, b.IDs)
			if err != nil {
				http.Error(w, err.Error(), http.StatusNotFound)
				return
			}
			writeJSON(w, map[string]int{"added": n, "already": len(b.IDs) - n})
		case "remove":
			a.Lib.RemoveFromPlaylist(id, b.I)
			writeJSON(w, map[string]bool{"ok": true})
		case "rename":
			a.Lib.RenamePlaylist(id, b.Name)
			writeJSON(w, map[string]bool{"ok": true})
		case "delete":
			a.Lib.DeletePlaylist(id)
			writeJSON(w, map[string]bool{"ok": true})
		default:
			http.NotFound(w, r)
		}
	})

	// Downloads
	mux.HandleFunc("GET /api/dl", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, a.DL.State()) })
	mux.HandleFunc("POST /api/dl/{op}", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			URL    string           `json:"url"`
			IDs    []string         `json:"ids"`
			Format downloads.Format `json:"format"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		switch r.PathValue("op") {
		case "tools":
			a.DL.InstallTools()
		case "probe":
			a.DL.ProbeURL(b.URL)
		case "start":
			if b.Format == "" {
				b.Format = downloads.MP3V0
			}
			if err := a.DL.Start(b.IDs, b.Format); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
		case "stop":
			a.DL.Stop()
		case "clear":
			a.DL.ClearFinished()
		default:
			http.NotFound(w, r)
			return
		}
		writeJSON(w, a.DL.State())
	})

	mux.HandleFunc("GET /api/about", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]string{"version": Version})
	})
	if ui != nil {
		mux.Handle("/", http.FileServerFS(ui))
	}
	return mux
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(v)
}

// Command is one action from a window.
type Command struct {
	Op     string          `json:"op"`
	I      int             `json:"i"`
	J      int             `json:"j"`
	Ms     int64           `json:"ms"`
	V      float64         `json:"v"`
	On     bool            `json:"on"`
	IDs    []string        `json:"ids"`
	Next   bool            `json:"next"`
	Source string          `json:"source"`
	Name   string          `json:"name"`
	Eq     *dsp.EqSettings `json:"eq"`
}

func (a *App) cmd(w http.ResponseWriter, r *http.Request) {
	var c Command
	if err := json.NewDecoder(r.Body).Decode(&c); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if err := a.Do(c); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}

// Do runs a command (also used by the tray menu).
func (a *App) Do(c Command) error {
	p := a.Player
	switch c.Op {
	case "toggle":
		p.Toggle()
	case "play":
		p.Play()
	case "pause":
		p.Pause()
	case "stop":
		p.Stop()
	case "next":
		p.Next()
	case "prev":
		p.Prev()
	case "seek":
		p.SeekMs(c.Ms)
	case "volume":
		p.SetVolume(c.V)
	case "shuffle":
		p.SetShuffle(c.On)
	case "repeat":
		p.CycleRepeat()
	case "playAt":
		p.PlayAt(c.I)
	case "remove":
		p.Remove(c.I)
	case "move":
		p.Move(c.I, c.J)
	case "clear":
		p.Clear()
	case "playTracks":
		p.PlayTracks(c.IDs, c.I, c.Source)
	case "enqueue":
		p.Enqueue(c.IDs, c.Next)
	case "eq":
		if c.Eq == nil {
			return fmt.Errorf("missing eq")
		}
		s := *c.Eq
		for i := range s.Bands {
			s.Bands[i] = math.Max(-dsp.MaxDb, math.Min(dsp.MaxDb, s.Bands[i]))
		}
		s.Preamp = math.Max(-dsp.MaxDb, math.Min(dsp.MaxDb, s.Preamp))
		p.SetEq(s)
	case "preset":
		bands, ok := dsp.PresetBands(c.Name)
		if !ok {
			return fmt.Errorf("unknown preset %q", c.Name)
		}
		s := p.State().Eq
		s.Bands, s.Preset, s.Enabled = bands, c.Name, true
		p.SetEq(s)
	case "crossfade":
		p.SetCrossfade(int(c.Ms))
	case "skin":
		if !validSkin(c.Name) {
			return fmt.Errorf("unknown skin %q", c.Name)
		}
		p.SetLook(func(d *settings.Data) { d.Skin = c.Name })
	case "layout":
		if _, ok := winmgr.Layouts[c.Name]; !ok {
			return fmt.Errorf("unknown layout %q", c.Name)
		}
		p.SetLook(func(d *settings.Data) { d.Layout = c.Name })
		a.Win.SetLayout(c.Name)
	case "alwaysTop":
		p.SetLook(func(d *settings.Data) { d.AlwaysTop = c.On })
		if a.Hooks.AlwaysTop != nil {
			a.Hooks.AlwaysTop(c.On)
		}
	default:
		return fmt.Errorf("unknown command %q", c.Op)
	}
	return nil
}

func validSkin(s string) bool {
	switch s {
	case "neon", "hifi", "pod", "chrome":
		return true
	}
	return false
}

type winCmd struct {
	Op   string `json:"op"`
	Name string `json:"name"`
	DX   int    `json:"dx"`
	DY   int    `json:"dy"`
	W    int    `json:"w"`
	H    int    `json:"h"`
}

func (a *App) win(w http.ResponseWriter, r *http.Request) {
	var c winCmd
	if err := json.NewDecoder(r.Body).Decode(&c); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	switch c.Op {
	case "dragStart":
		a.Win.DragStart(c.Name)
	case "dragMove":
		a.Win.DragMove(c.DX, c.DY)
	case "dragEnd":
		a.Win.DragEnd()
	case "shade":
		a.Win.Shade(c.Name)
	case "open":
		a.Win.SetOpen(c.Name, true)
	case "close":
		if c.Name == "player" {
			if a.Hooks.Quit != nil {
				go a.Hooks.Quit()
			}
			break
		}
		a.Win.SetOpen(c.Name, false)
	case "toggle":
		a.Win.SetOpen(c.Name, !a.Win.IsOpen(c.Name))
	case "resize":
		a.Win.Resize(c.Name, c.W, c.H)
	case "minimize":
		if a.Hooks.Minimize != nil {
			a.Hooks.Minimize()
		}
	case "quit":
		if a.Hooks.Quit != nil {
			go a.Hooks.Quit()
		}
	default:
		http.Error(w, "unknown window op", http.StatusBadRequest)
		return
	}
	writeJSON(w, a.winStates())
}

// sse streams events to a browser (developer server); the Windows app uses a Wails stream instead.
func (a *App) sse(w http.ResponseWriter, r *http.Request) {
	fl, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-store")
	c := a.Hub.Subscribe()
	defer a.Hub.Unsubscribe(c)
	first := mustJSON(map[string]any{"t": "state", "d": a.Player.State()})
	fmt.Fprintf(w, "data: %s\n\n", first)
	fl.Flush()
	for {
		select {
		case <-r.Context().Done():
			return
		case msg, ok := <-c:
			if !ok {
				return
			}
			if _, err := fmt.Fprintf(w, "data: %s\n\n", strings.TrimSpace(string(msg))); err != nil {
				return
			}
			fl.Flush()
		}
	}
}
