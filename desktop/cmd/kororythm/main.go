//go:build windows

// Kororythm for Windows: the Classic layout as real floating windows that snap
// and dock like Winamp, with the Go audio engine underneath.
package main

import (
	_ "embed"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sync"

	"github.com/jose00735/kororythm/desktop/frontend"
	"github.com/jose00735/kororythm/desktop/internal/app"
	"github.com/jose00735/kororythm/desktop/internal/settings"
	"github.com/jose00735/kororythm/desktop/internal/winmgr"
	"github.com/wailsapp/wails/v3/pkg/application"
	"github.com/wailsapp/wails/v3/pkg/events"
)

//go:embed icon.png
var trayIcon []byte

var titles = map[string]string{
	"player": "Kororythm", "eq": "Kororythm Equalizer", "playlist": "Kororythm Playlist", "library": "Kororythm Library",
	"pod": "Kororythm", "prism": "Kororythm", "cover": "Kororythm", "downloads": "Kororythm Downloads", "sync": "Kororythm Sync",
}

// Each layout's own window gets a taskbar button; the side windows don't.
var onTaskbar = map[string]bool{"player": true, "pod": true, "prism": true, "cover": true}

// Windows drawn as objects need a see-through background.
var transparent = map[string]bool{"pod": true, "cover": true, "prism": true}

// host moves the Wails windows for the window manager.
type host struct {
	mu   sync.Mutex
	wins map[string]*application.WebviewWindow
}

func (h *host) get(n string) *application.WebviewWindow {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.wins[n]
}

func (h *host) SetBounds(n string, r winmgr.Rect) {
	if w := h.get(n); w != nil {
		old := w.Bounds()
		w.SetBounds(application.Rect{X: r.X, Y: r.Y, Width: r.W, Height: r.H})
		if old.Width != r.W || old.Height != r.H {
			applyShape(n, w)
		}
	}
}
func (h *host) Show(n string) {
	if w := h.get(n); w != nil {
		w.Show()
		applyShape(n, w)
	}
}
func (h *host) Hide(n string) {
	if w := h.get(n); w != nil {
		w.Hide()
	}
}

func main() {
	dir := settings.Dir()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		log.Fatal(err)
	}
	logf, _ := os.OpenFile(filepath.Join(dir, "kororythm.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if logf != nil {
		log.SetOutput(logf)
	}

	h := &host{wins: map[string]*application.WebviewWindow{}}
	core, err := app.New(dir, h)
	if err != nil {
		log.Fatal(err)
	}
	defer core.Close()

	// /api goes to Kororythm; everything else (the pages and /wails/runtime.js)
	// to Wails' bundled file server.
	assets := http.NewServeMux()
	assets.Handle("/api/", core.Handler(nil))
	assets.Handle("/", application.BundledAssetFileServer(frontend.FS))

	var wapp *application.App
	wapp = application.New(application.Options{
		Name:        "Kororythm",
		Description: "FLAC and MP3 player",
		Assets:      application.AssetOptions{Handler: assets},
		SingleInstance: &application.SingleInstanceOptions{
			UniqueID: "com.koro.kororythm.desktop",
			OnSecondInstanceLaunch: func(application.SecondInstanceData) {
				if w := h.get("player"); w != nil {
					w.Show()
					w.Focus()
				}
			},
		},
		Windows: application.WindowsOptions{},
	})

	// Live events for every window: state, position ticks and visualizer bars.
	wapp.HandleStream("events", func(c *application.StreamConn) {
		defer c.Close()
		sub := core.Hub.Subscribe()
		defer core.Hub.Unsubscribe(sub)
		_ = c.SendJSON(map[string]any{"t": "state", "d": core.Player.State()})
		for {
			select {
			case <-c.Context().Done():
				return
			case msg, ok := <-sub:
				if !ok {
					return
				}
				if err := c.TrySend(msg); err != nil && c.Context().Err() != nil {
					return
				}
			}
		}
	})

	top := core.Set.Get().AlwaysTop
	for name, def := range app.Windows {
		r, _ := core.Win.Bounds(name)
		if r.W == 0 {
			r = def.Rect
		}
		bg := application.BackgroundTypeSolid
		if transparent[name] {
			bg = application.BackgroundTypeTransparent
		}
		w := wapp.Window.NewWithOptions(application.WebviewWindowOptions{
			BackgroundType:   bg,
			Name:             name,
			Title:            titles[name],
			URL:              "/index.html?w=" + name,
			Width:            r.W,
			Height:           r.H,
			X:                r.X,
			Y:                r.Y,
			InitialPosition:  application.WindowXY,
			Frameless:        true,
			DisableResize:    true,
			Hidden:           true,
			AlwaysOnTop:      top,
			BackgroundColour: application.NewRGB(4, 6, 11),
			Windows: application.WindowsWindow{
				// One taskbar button per layout, like Winamp: side windows don't show there.
				HiddenOnTaskbar: !onTaskbar[name],
				// The page draws its own outline; no system shadow box around shaped windows.
				DisableFramelessWindowDecorations: transparent[name] && name != "prism",
			},
		})
		n := name
		if onTaskbar[name] {
			w.RegisterHook(events.Common.WindowRestore, func(*application.WindowEvent) { core.Win.ApplyAll() })
			w.RegisterHook(events.Common.WindowUnMinimise, func(*application.WindowEvent) { core.Win.ApplyAll() })
		}
		// Alt+F4 on a side window hides it; on a layout's own window it quits.
		w.RegisterHook(events.Common.WindowClosing, func(e *application.WindowEvent) {
			if onTaskbar[n] && n != "pod" {
				return
			}
			if n == "pod" && core.Win.Layout() == "pod" {
				return
			}
			e.Cancel()
			core.Win.SetOpen(n, false)
		})
		h.mu.Lock()
		h.wins[name] = w
		h.mu.Unlock()
	}

	core.Hooks = app.Hooks{
		Quit: func() { wapp.Quit() },
		// Minimising the player tucks the side windows away (they have no taskbar
		// button); restoring it brings back the ones that were open.
		Minimize: func() {
			main := winmgr.Main[core.Win.Layout()]
			for n, w := range h.wins {
				if n != main {
					w.Hide()
				}
			}
			if p := h.get(main); p != nil {
				p.Minimise()
			}
		},
		PickFolder: func() (string, error) {
			return wapp.Dialog.OpenFile().
				SetTitle("Choose a folder with music").
				CanChooseDirectories(true).
				CanChooseFiles(false).
				PromptForSingleSelection()
		},
		AlwaysTop: func(on bool) {
			for _, w := range h.wins {
				w.SetAlwaysOnTop(on)
			}
		},
	}

	// Tray icon: music keeps playing with every window hidden.
	tray := wapp.SystemTray.New()
	tray.SetIcon(trayIcon)
	tray.SetTooltip("Kororythm")
	menu := application.NewMenu()
	menu.Add("Show Kororythm").OnClick(func(*application.Context) { core.Win.ApplyAll() })
	layouts := menu.AddSubmenu("Layout")
	for _, l := range [][2]string{{"classic", "Classic"}, {"pod", "Pod"}, {"prism", "Prism"}, {"cover", "Cover Stage"}} {
		id := l[0]
		layouts.Add(l[1]).OnClick(func(*application.Context) { _ = core.Do(app.Command{Op: "layout", Name: id}) })
	}
	menu.Add("iPod browser").OnClick(func(*application.Context) { core.Win.SetOpen("pod", !core.Win.IsOpen("pod")) })
	menu.Add("Downloads").OnClick(func(*application.Context) { core.Win.SetOpen("downloads", true) })
	menu.Add("Sync with phone").OnClick(func(*application.Context) { core.Win.SetOpen("sync", true) })
	menu.AddSeparator()
	menu.Add("Play / Pause").OnClick(func(*application.Context) { _ = core.Do(app.Command{Op: "toggle"}) })
	menu.Add("Next").OnClick(func(*application.Context) { _ = core.Do(app.Command{Op: "next"}) })
	menu.Add("Previous").OnClick(func(*application.Context) { _ = core.Do(app.Command{Op: "prev"}) })
	menu.AddSeparator()
	menu.Add("Quit").OnClick(func(*application.Context) { wapp.Quit() })
	tray.SetMenu(menu)
	tray.OnClick(func() {
		core.Win.ApplyAll()
		if w := h.get(winmgr.Main[core.Win.Layout()]); w != nil {
			w.Focus()
		}
	})

	wapp.Event.OnApplicationEvent(events.Common.ApplicationStarted, func(*application.ApplicationEvent) {
		var work []winmgr.Rect
		for _, s := range wapp.Screen.GetAll() {
			a := s.WorkArea
			work = append(work, winmgr.Rect{X: a.X, Y: a.Y, W: a.Width, H: a.Height})
		}
		core.Win.SetScreens(work)
		core.Win.ApplyAll()
		// Listen for phones straight away once one is paired (the Sync window starts it otherwise).
		if core.Sync != nil && len(core.Sync.Devices()) > 0 {
			if err := core.StartSync(); err != nil {
				log.Println("sync:", err)
			}
		}
	})

	if err := wapp.Run(); err != nil {
		log.Println(err)
	}
}
