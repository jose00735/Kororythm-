// Package app wires Kororythm's parts together and serves the API the windows
// use. The same code runs inside the Windows app (through Wails) and in the
// developer server on Linux (through a normal web server).
package app

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/jose00735/kororythm/desktop/internal/audio"
	"github.com/jose00735/kororythm/desktop/internal/downloads"
	"github.com/jose00735/kororythm/desktop/internal/library"
	"github.com/jose00735/kororythm/desktop/internal/player"
	"github.com/jose00735/kororythm/desktop/internal/settings"
	"github.com/jose00735/kororythm/desktop/internal/syncer"
	"github.com/jose00735/kororythm/desktop/internal/winmgr"
)

// Version is shown in the About box and the installer.
var Version = "2.2.0"

// Windows of the Classic layout, with their default places.
var Windows = map[string]winmgr.State{
	"player":    {Rect: winmgr.Rect{X: 120, Y: 90, W: 460, H: 180}, Open: true},
	"eq":        {Rect: winmgr.Rect{X: 120, Y: 270, W: 460, H: 196}, Open: true},
	"playlist":  {Rect: winmgr.Rect{X: 120, Y: 466, W: 460, H: 330}, Open: true},
	"library":   {Rect: winmgr.Rect{X: 580, Y: 90, W: 680, H: 706}, Open: false},
	"pod":       {Rect: winmgr.Rect{X: 160, Y: 80, W: 340, H: 580}},
	"prism":     {Rect: winmgr.Rect{X: 120, Y: 70, W: 1000, H: 660}},
	"cover":     {Rect: winmgr.Rect{X: 180, Y: 40, W: 400, H: 800}},
	"downloads": {Rect: winmgr.Rect{X: 640, Y: 120, W: 600, H: 560}},
	"sync":      {Rect: winmgr.Rect{X: 660, Y: 140, W: 560, H: 620}},
}

// Hooks are what only the desktop shell can do.
type Hooks struct {
	Quit       func()
	Minimize   func()
	PickFolder func() (string, error)
	AlwaysTop  func(bool)
}

// App is the running player.
type App struct {
	Lib     *library.Library
	Eng     *audio.Engine
	Player  *player.Player
	Set     *settings.Store
	Win     *winmgr.Manager
	Hub     *Hub
	DL      *downloads.Manager
	Sync    *syncer.Server
	Cable   *syncer.Cable
	Hooks   Hooks
	out     audio.Output
	stop    chan struct{}
	once    sync.Once
	syncMu  sync.Mutex
	syncOn  bool
	syncLog []syncer.Event
}

// New opens the library and settings in dir and starts the audio device.
func New(dir string, host winmgr.Host) (*App, error) {
	set := settings.Open(filepath.Join(dir, "settings.json"))
	lib := library.Open(filepath.Join(dir, "library.json"))
	if len(lib.Folders()) == 0 {
		if home, err := os.UserHomeDir(); err == nil {
			music := filepath.Join(home, "Music")
			if st, err := os.Stat(music); err == nil && st.IsDir() {
				lib.SetFolders([]string{music})
			}
		}
	}
	eng := audio.NewEngine()
	a := &App{Lib: lib, Eng: eng, Set: set, Hub: NewHub(), stop: make(chan struct{})}
	a.Player = player.New(lib, eng, set)
	a.Win = winmgr.New(host, Windows)
	saved := map[string]winmgr.State{}
	for n, w := range set.Get().Windows {
		saved[n] = winmgr.State{Rect: winmgr.Rect{X: w.X, Y: w.Y, W: w.W, H: w.H}, Open: w.Open, Shaded: w.Shaded}
	}
	a.Win.Restore(saved)
	if d := set.Get(); d.Layout != "" {
		a.Win.SetLayout(d.Layout)
	}

	// Downloads go to Music\Kororythm Downloads, which the library watches.
	home, _ := os.UserHomeDir()
	dlDir := filepath.Join(home, "Music", "Kororythm Downloads")
	a.DL = downloads.New(filepath.Join(dir, "tools"), dlDir)
	a.DL.OnChange(func(st downloads.State) { a.Hub.Send("dl", st) })
	a.DL.OnLog(func(line string) { a.Hub.Send("dlLog", line) })
	a.DL.OnDone(func(string) {
		a.ensureWatched(dlDir)
		go lib.Scan()
	})
	// Songs from the phone go to Music\Kororythm Sync.
	syncDir := filepath.Join(home, "Music", "Kororythm Sync")
	pcName, _ := os.Hostname()
	if s, err := syncer.New(syncer.Config{
		Dir: filepath.Join(dir, "sync"), Name: pcName, Library: lib, Inbox: syncDir, FFmpeg: a.DL.FFmpegPath,
		Notify: func(e syncer.Event) {
			if e.Kind == "received" {
				a.ensureWatched(syncDir)
			}
			a.syncMu.Lock()
			if e.Kind != "progress" {
				a.syncLog = append(a.syncLog, e)
				if len(a.syncLog) > 40 {
					a.syncLog = a.syncLog[len(a.syncLog)-40:]
				}
			}
			a.syncMu.Unlock()
			a.Hub.Send("sync", e)
		},
	}); err == nil {
		a.Sync = s
	}
	a.Cable = &syncer.Cable{ADB: a.DL.ADBPath, Install: a.DL.InstallADB, Port: syncer.Port,
		Notify: func(st syncer.CableState) { a.Hub.Send("syncState", a.syncState()) }}
	a.Win.OnSaved(func(st map[string]winmgr.State) {
		set.Update(func(d *settings.Data) {
			for n, s := range st {
				d.Windows[n] = settings.Win{X: s.X, Y: s.Y, W: s.W, H: s.H, Open: s.Open, Shaded: s.Shaded}
			}
		})
		a.Hub.Send("win", a.winStates())
	})
	a.Player.OnChange(a.pushState)
	lib.OnChange(a.pushState)
	out, err := audio.OpenOutput(eng)
	if err != nil {
		return nil, err
	}
	a.out = out
	go a.ticker()
	go lib.Scan()
	_ = lib.Watch(a.stop)
	return a, nil
}

// ensureWatched adds dir to the library folders unless one of them already holds it.
func (a *App) ensureWatched(dir string) {
	for _, f := range a.Lib.Folders() {
		f = filepath.Clean(f)
		if strings.EqualFold(f, filepath.Clean(dir)) || strings.HasPrefix(strings.ToLower(dir), strings.ToLower(f)+string(filepath.Separator)) {
			return
		}
	}
	a.Lib.SetFolders(append(a.Lib.Folders(), dir))
}

// StartSync starts listening for phones (HTTPS on 47800, discovery on UDP 47801).
func (a *App) StartSync() error {
	a.syncMu.Lock()
	defer a.syncMu.Unlock()
	if a.Sync == nil || a.syncOn {
		return nil
	}
	if err := a.Sync.Start(); err != nil {
		return err
	}
	a.syncOn = true
	return nil
}

// Close stops audio and saves everything.
func (a *App) Close() {
	a.once.Do(func() {
		close(a.stop)
		if a.Sync != nil && a.syncOn {
			a.Sync.Close()
		}
		if a.out != nil {
			a.out.Close()
		}
		_ = a.Lib.Save()
		_ = a.Set.Save()
	})
}

var stateMu sync.Mutex
var stateTimer *time.Timer

// pushState sends the full state soon (coalescing bursts of changes).
func (a *App) pushState() {
	stateMu.Lock()
	defer stateMu.Unlock()
	if stateTimer != nil {
		return
	}
	stateTimer = time.AfterFunc(40*time.Millisecond, func() {
		stateMu.Lock()
		stateTimer = nil
		stateMu.Unlock()
		a.Hub.Send("state", a.Player.State())
	})
}

// ticker sends the position four times a second and the visualizer bars at ~30 fps.
func (a *App) ticker() {
	bars := time.NewTicker(33 * time.Millisecond)
	defer bars.Stop()
	n := 0
	quiet := 0
	for {
		select {
		case <-a.stop:
			return
		case <-bars.C:
		}
		if a.Hub.Count() == 0 {
			continue
		}
		n++
		t := a.Player.Tick()
		if n%8 == 0 {
			a.Hub.Send("tick", t)
		}
		// Keep sending a few frames after stopping so bars can fall to zero.
		if t.Playing {
			quiet = 0
		} else {
			quiet++
		}
		if quiet < 20 {
			a.Hub.Send("bars", a.Player.Bars(64))
		}
	}
}

type winInfo struct {
	Open   bool   `json:"open"`
	Shaded bool   `json:"shaded"`
	Layout string `json:"layout,omitempty"`
}

func (a *App) winStates() map[string]winInfo {
	out := map[string]winInfo{}
	for n, s := range a.Win.States() {
		out[n] = winInfo{Open: a.Win.IsOpen(n), Shaded: s.Shaded}
	}
	out["_"] = winInfo{Layout: a.Win.Layout()}
	return out
}

func mustJSON(v any) []byte { b, _ := json.Marshal(v); return b }
