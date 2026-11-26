// Command kororythm-synctest runs the PC sync server on a small fixture library, so the
// Android client's tests can talk to the real thing (SyncClientInteropTest.kt). It prints
// one JSON line {"addr": "...", "code": "..."} and serves until stdin closes.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"

	"github.com/jose00735/kororythm/desktop/internal/library"
	"github.com/jose00735/kororythm/desktop/internal/syncer"
)

func main() {
	fixtures := flag.String("fixtures", "protocol/fixtures", "folder with a.flac and t.mp3")
	flag.Parse()
	tmp, err := os.MkdirTemp("", "korosync")
	if err != nil {
		log.Fatal(err)
	}
	defer os.RemoveAll(tmp)
	music := filepath.Join(tmp, "Music")
	for _, n := range []string{"a.flac", "t.mp3"} {
		b, err := os.ReadFile(filepath.Join(*fixtures, n))
		if err != nil {
			log.Fatal(err)
		}
		os.MkdirAll(music, 0o755)
		os.WriteFile(filepath.Join(music, n), b, 0o644)
	}
	lib := library.Open(filepath.Join(tmp, "library.json"))
	lib.SetFolders([]string{music})
	lib.Scan()
	srv, err := syncer.New(syncer.Config{Dir: filepath.Join(tmp, "sync"), Name: "TEST-PC", Library: lib,
		Inbox: filepath.Join(music, "Kororythm Sync"), Addr: "127.0.0.1:0"})
	if err != nil {
		log.Fatal(err)
	}
	if err := srv.Start(); err != nil {
		log.Fatal(err)
	}
	b, _ := json.Marshal(map[string]string{"addr": srv.Addr(), "code": srv.NewCode(), "fp": srv.FP()})
	fmt.Println(string(b))
	io.Copy(io.Discard, os.Stdin)
	ps := lib.AllPlaylists()
	fmt.Fprintf(os.Stderr, "songs=%d playlists=%d\n", lib.Count(), len(ps))
	srv.Close()
}
