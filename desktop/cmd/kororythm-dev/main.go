// kororythm-dev runs Kororythm's core and serves its windows to a web browser.
// It is for development and testing on any OS: open /dev.html to see every
// window on a fake desktop, or /index.html?w=player for one window. Sound is
// not played; the engine runs on a silent real-time clock.
package main

import (
	"flag"
	"log"
	"net/http"
	"os"

	"github.com/jose00735/kororythm/desktop/frontend"
	"github.com/jose00735/kororythm/desktop/internal/app"
	"github.com/jose00735/kororythm/desktop/internal/library"
	"github.com/jose00735/kororythm/desktop/internal/winmgr"
)

type nopHost struct{}

func (nopHost) SetBounds(string, winmgr.Rect) {}
func (nopHost) Show(string)                   {}
func (nopHost) Hide(string)                   {}

func main() {
	addr := flag.String("addr", "127.0.0.1:8765", "address to serve on")
	data := flag.String("data", os.TempDir()+"/kororythm-dev", "where to keep settings and the library")
	music := flag.String("music", "", "music folder to scan (optional)")
	flag.Parse()
	a, err := app.New(*data, nopHost{})
	if err != nil {
		log.Fatal(err)
	}
	defer a.Close()
	a.Win.SetScreens([]winmgr.Rect{{X: 0, Y: 0, W: 1600, H: 900}})
	if *music != "" {
		a.Lib.SetFolders([]string{*music})
		go func() { r := a.Lib.Scan(); log.Printf("scan: %+v", r) }()
	}
	_ = library.Supported
	log.Printf("Kororythm dev server on http://%s/dev.html", *addr)
	log.Fatal(http.ListenAndServe(*addr, a.Handler(frontend.FS)))
}
