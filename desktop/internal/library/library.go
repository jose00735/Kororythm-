// Package library keeps Kororythm's song catalogue on Windows: it scans music
// folders, reads tags and cover art, watches for changes and answers the
// browsing queries the windows need (artists, albums, genres, songs, search).
package library

import (
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Track is one song file.
type Track struct {
	ID          string `json:"id"`
	Path        string `json:"path"`
	Title       string `json:"title"`
	Artist      string `json:"artist"`
	Album       string `json:"album"`
	AlbumArtist string `json:"albumArtist,omitempty"`
	Genre       string `json:"genre,omitempty"`
	Year        int    `json:"year,omitempty"`
	TrackNo     int    `json:"trackNo,omitempty"`
	DiscNo      int    `json:"discNo,omitempty"`
	DurationMs  int64  `json:"durationMs"`
	SampleRate  int    `json:"sampleRate,omitempty"`
	BitDepth    int    `json:"bitDepth,omitempty"`
	BitrateKb   int    `json:"bitrateKb,omitempty"`
	Codec       string `json:"codec"`
	Size        int64  `json:"size"`
	ModTime     int64  `json:"modTime"`
	HasArt      bool   `json:"hasArt,omitempty"`
	AddedAt     int64  `json:"addedAt"`
	Plays       int    `json:"plays,omitempty"`
	LastPlayed  int64  `json:"lastPlayed,omitempty"`
	Fingerprint string `json:"fp,omitempty"`
}

// AlbumKey groups tracks into albums.
func (t *Track) AlbumKey() string {
	a := t.AlbumArtist
	if a == "" {
		a = t.Artist
	}
	return strings.ToLower(a + "\x00" + t.Album)
}

// IDFor gives a stable id for a file path.
func IDFor(path string) string {
	h := sha1.Sum([]byte(strings.ToLower(filepath.Clean(path))))
	return hex.EncodeToString(h[:8])
}

// Library is the catalogue, saved as one JSON file.
type Library struct {
	mu      sync.RWMutex
	file    string
	tracks    map[string]*Track
	playlists map[string]*Playlist
	folders   []string
	onChange func()
	scanning bool
	again    bool // a scan was asked for while one was running
	saveT   *time.Timer
}

type saved struct {
	Folders   []string    `json:"folders"`
	Tracks    []*Track    `json:"tracks"`
	Playlists []*Playlist `json:"playlists,omitempty"`
}

// Open loads the catalogue from file (or starts empty).
func Open(file string) *Library {
	l := &Library{file: file, tracks: map[string]*Track{}, playlists: map[string]*Playlist{}}
	if b, err := os.ReadFile(file); err == nil {
		var s saved
		if json.Unmarshal(b, &s) == nil {
			l.folders = s.Folders
			for _, t := range s.Tracks {
				l.tracks[t.ID] = t
			}
			for _, p := range s.Playlists {
				l.playlists[p.ID] = p
			}
		}
	}
	return l
}

// OnChange registers a callback for when tracks are added, changed or removed.
func (l *Library) OnChange(f func()) { l.mu.Lock(); l.onChange = f; l.mu.Unlock() }

func (l *Library) changed() {
	l.mu.Lock()
	f := l.onChange
	if l.saveT != nil {
		l.saveT.Stop()
	}
	l.saveT = time.AfterFunc(time.Second, func() { _ = l.Save() })
	l.mu.Unlock()
	if f != nil {
		f()
	}
}

// Save writes the catalogue atomically.
func (l *Library) Save() error {
	l.mu.RLock()
	s := saved{Folders: l.folders}
	for _, t := range l.tracks {
		s.Tracks = append(s.Tracks, t)
	}
	for _, p := range l.playlists {
		s.Playlists = append(s.Playlists, p)
	}
	l.mu.RUnlock()
	sort.Slice(s.Tracks, func(i, j int) bool { return s.Tracks[i].Path < s.Tracks[j].Path })
	b, err := json.Marshal(s)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(l.file), 0o755); err != nil {
		return err
	}
	tmp := l.file + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, l.file)
}

// Folders returns the watched music folders.
func (l *Library) Folders() []string {
	l.mu.RLock()
	defer l.mu.RUnlock()
	return append([]string(nil), l.folders...)
}

// SetFolders replaces the watched folders.
func (l *Library) SetFolders(f []string) {
	l.mu.Lock()
	l.folders = append([]string(nil), f...)
	l.mu.Unlock()
	l.changed()
}

// Get returns a track by id.
func (l *Library) Get(id string) (*Track, bool) {
	l.mu.RLock()
	defer l.mu.RUnlock()
	t, ok := l.tracks[id]
	return t, ok
}

// Count returns how many songs are in the library.
func (l *Library) Count() int {
	l.mu.RLock()
	defer l.mu.RUnlock()
	return len(l.tracks)
}

// Scanning reports whether a scan is running.
func (l *Library) Scanning() bool {
	l.mu.RLock()
	defer l.mu.RUnlock()
	return l.scanning
}

// put adds or replaces tracks in one go.
func (l *Library) put(ts []*Track, remove []string) {
	if len(ts) == 0 && len(remove) == 0 {
		return
	}
	l.mu.Lock()
	for _, t := range ts {
		if old, ok := l.tracks[t.ID]; ok {
			if t.AddedAt == 0 {
				t.AddedAt = old.AddedAt
			}
			if t.Plays == 0 {
				t.Plays, t.LastPlayed = old.Plays, old.LastPlayed
			}
		}
		if t.AddedAt == 0 {
			t.AddedAt = time.Now().Unix()
		}
		l.tracks[t.ID] = t
	}
	for _, id := range remove {
		delete(l.tracks, id)
	}
	l.mu.Unlock()
	l.changed()
}

// all returns every track (caller must not modify them).
func (l *Library) all() []*Track {
	l.mu.RLock()
	defer l.mu.RUnlock()
	out := make([]*Track, 0, len(l.tracks))
	for _, t := range l.tracks {
		out = append(out, t)
	}
	return out
}
