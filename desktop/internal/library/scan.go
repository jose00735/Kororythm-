package library

import (
	"bytes"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/dhowden/tag"
	"github.com/fsnotify/fsnotify"
	"github.com/jose00735/kororythm/desktop/internal/audio"
)

// Supported reports whether a file is a song Kororythm can play.
func Supported(path string) bool {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".flac", ".mp3":
		return true
	}
	return false
}

// ScanResult summarises a scan.
type ScanResult struct {
	Added, Updated, Removed, Failed int
}

// Scan walks the music folders, reads new and changed files and drops missing ones.
func (l *Library) Scan() ScanResult {
	l.mu.Lock()
	if l.scanning {
		// Run once more when the current scan ends, so new folders are not missed.
		l.again = true
		l.mu.Unlock()
		return ScanResult{}
	}
	l.scanning = true
	folders := append([]string(nil), l.folders...)
	known := make(map[string]*Track, len(l.tracks))
	for _, t := range l.tracks {
		known[t.ID] = t
	}
	l.mu.Unlock()
	defer func() {
		l.mu.Lock()
		l.scanning = false
		again := l.again
		l.again = false
		l.mu.Unlock()
		l.changed()
		if again {
			go l.Scan()
		}
	}()

	type job struct {
		path string
		info fs.FileInfo
	}
	seen := map[string]bool{}
	var todo []job
	for _, root := range folders {
		_ = filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
			if err != nil || d.IsDir() || !Supported(p) {
				return nil
			}
			info, err := d.Info()
			if err != nil {
				return nil
			}
			id := IDFor(p)
			seen[id] = true
			if t, ok := known[id]; ok && t.Size == info.Size() && t.ModTime == info.ModTime().Unix() {
				return nil
			}
			todo = append(todo, job{p, info})
			return nil
		})
	}
	var res ScanResult
	var mu sync.Mutex
	var batch []*Track
	jobs := make(chan job)
	var wg sync.WaitGroup
	for w := 0; w < max(2, runtime.NumCPU()/2); w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range jobs {
				t, err := ReadTrack(j.path, j.info)
				mu.Lock()
				if err != nil {
					res.Failed++
				} else {
					if _, ok := known[t.ID]; ok {
						res.Updated++
					} else {
						res.Added++
					}
					batch = append(batch, t)
					if len(batch) >= 200 { // show progress in big libraries
						b := batch
						batch = nil
						mu.Unlock()
						l.put(b, nil)
						continue
					}
				}
				mu.Unlock()
			}
		}()
	}
	for _, j := range todo {
		jobs <- j
	}
	close(jobs)
	wg.Wait()
	var gone []string
	for id := range known {
		if !seen[id] {
			gone = append(gone, id)
		}
	}
	res.Removed = len(gone)
	l.put(batch, gone)
	return res
}

// ReadTrack reads a file's tags and audio format.
func ReadTrack(path string, info fs.FileInfo) (*Track, error) {
	if info == nil {
		var err error
		if info, err = os.Stat(path); err != nil {
			return nil, err
		}
	}
	t := &Track{ID: IDFor(path), Path: path, Size: info.Size(), ModTime: info.ModTime().Unix()}
	if f, err := os.Open(path); err == nil {
		if m, err := tag.ReadFrom(f); err == nil {
			t.Title = strings.TrimSpace(m.Title())
			t.Artist = strings.TrimSpace(m.Artist())
			t.Album = strings.TrimSpace(m.Album())
			t.AlbumArtist = strings.TrimSpace(m.AlbumArtist())
			t.Genre = cleanGenre(m.Genre())
			t.Year = m.Year()
			t.TrackNo, _ = m.Track()
			t.DiscNo, _ = m.Disc()
			t.HasArt = m.Picture() != nil
		}
		f.Close()
	}
	in, err := audio.Probe(path)
	if err != nil {
		return nil, err
	}
	t.DurationMs, t.SampleRate, t.BitDepth, t.BitrateKb, t.Codec = in.DurationMs, in.SampleRate, in.BitDepth, in.BitrateKb, in.Codec
	if t.Title == "" {
		t.Title = strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
	}
	if t.Artist == "" {
		t.Artist = "Unknown artist"
	}
	if t.Album == "" {
		t.Album = filepath.Base(filepath.Dir(path))
	}
	if !t.HasArt {
		t.HasArt = folderArt(filepath.Dir(path)) != ""
	}
	return t, nil
}

// cleanGenre turns ID3 "(17)" style codes into names and trims noise.
func cleanGenre(g string) string {
	g = strings.TrimSpace(g)
	if strings.HasPrefix(g, "(") && strings.Contains(g, ")") {
		code := g[1:strings.Index(g, ")")]
		if n, err := strconv.Atoi(code); err == nil && n >= 0 && n < len(id3Genres) {
			return id3Genres[n]
		}
	}
	return g
}

var id3Genres = []string{"Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
	"New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska",
	"Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance",
	"Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise", "Alternative Rock", "Bass",
	"Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave",
	"Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
	"Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave",
	"Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock"}

func folderArt(dir string) string {
	for _, n := range []string{"cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png", "albumart.jpg"} {
		p := filepath.Join(dir, n)
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

// Art returns a song's cover image and its MIME type.
func (l *Library) Art(id string) ([]byte, string, bool) {
	t, ok := l.Get(id)
	if !ok {
		return nil, "", false
	}
	if f, err := os.Open(t.Path); err == nil {
		m, err := tag.ReadFrom(f)
		f.Close()
		if err == nil {
			if p := m.Picture(); p != nil && len(p.Data) > 0 {
				mime := p.MIMEType
				if mime == "" {
					mime = sniff(p.Data)
				}
				return p.Data, mime, true
			}
		}
	}
	if p := folderArt(filepath.Dir(t.Path)); p != "" {
		if b, err := os.ReadFile(p); err == nil {
			return b, sniff(b), true
		}
	}
	return nil, "", false
}

func sniff(b []byte) string {
	if bytes.HasPrefix(b, []byte{0x89, 'P', 'N', 'G'}) {
		return "image/png"
	}
	return "image/jpeg"
}

// Watch rescans shortly after files change in the music folders, until stop closes.
func (l *Library) Watch(stop <-chan struct{}) error {
	w, err := fsnotify.NewWatcher()
	if err != nil {
		return err
	}
	add := func() {
		for _, root := range l.Folders() {
			_ = filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
				if err == nil && d.IsDir() {
					_ = w.Add(p)
				}
				return nil
			})
		}
	}
	add()
	go func() {
		defer w.Close()
		var timer *time.Timer
		for {
			select {
			case <-stop:
				return
			case ev, ok := <-w.Events:
				if !ok {
					return
				}
				if ev.Has(fsnotify.Create) {
					if st, err := os.Stat(ev.Name); err == nil && st.IsDir() {
						_ = w.Add(ev.Name)
					}
				}
				if timer != nil {
					timer.Stop()
				}
				timer = time.AfterFunc(2*time.Second, func() { l.Scan() })
			case <-w.Errors:
			}
		}
	}()
	return nil
}
