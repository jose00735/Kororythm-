package library

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sort"
	"strings"
	"time"
)

// Playlist is a saved list of songs, by track id.
type Playlist struct {
	ID       string   `json:"id"`
	Name     string   `json:"name"`
	Tracks   []string `json:"tracks"`
	Created  int64    `json:"created"`
	Modified int64    `json:"modified"`
}

// PlaylistInfo is a playlist without its songs, for lists.
type PlaylistInfo struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	Songs      int    `json:"songs"`
	DurationMs int64  `json:"durationMs"`
	ArtID      string `json:"artId,omitempty"`
	Modified   int64  `json:"modified"`
}

func newID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// Playlists lists playlists by name.
func (l *Library) Playlists() []PlaylistInfo {
	l.mu.RLock()
	defer l.mu.RUnlock()
	out := make([]PlaylistInfo, 0, len(l.playlists))
	for _, p := range l.playlists {
		in := PlaylistInfo{ID: p.ID, Name: p.Name, Modified: p.Modified}
		for _, id := range p.Tracks {
			if t, ok := l.tracks[id]; ok {
				in.Songs++
				in.DurationMs += t.DurationMs
				if in.ArtID == "" && t.HasArt {
					in.ArtID = t.ID
				}
			}
		}
		out = append(out, in)
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out
}

// Playlist returns one playlist's songs in order (missing files are skipped).
func (l *Library) Playlist(id string) (*Playlist, []*Track, bool) {
	l.mu.RLock()
	defer l.mu.RUnlock()
	p, ok := l.playlists[id]
	if !ok {
		return nil, nil, false
	}
	var ts []*Track
	for _, tid := range p.Tracks {
		if t, ok := l.tracks[tid]; ok {
			ts = append(ts, t)
		}
	}
	cp := *p
	return &cp, ts, true
}

// AllPlaylists returns copies of every playlist (for sync).
func (l *Library) AllPlaylists() []Playlist {
	l.mu.RLock()
	defer l.mu.RUnlock()
	out := make([]Playlist, 0, len(l.playlists))
	for _, p := range l.playlists {
		cp := *p
		cp.Tracks = append([]string(nil), p.Tracks...)
		out = append(out, cp)
	}
	return out
}

// CreatePlaylist makes a playlist with the given songs (duplicates dropped).
func (l *Library) CreatePlaylist(name string, ids []string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return "", errors.New("a playlist needs a name")
	}
	now := time.Now().Unix()
	p := &Playlist{ID: newID(), Name: name, Created: now, Modified: now}
	p.Tracks = dedupe(nil, ids)
	l.mu.Lock()
	l.playlists[p.ID] = p
	l.mu.Unlock()
	l.changed()
	return p.ID, nil
}

// PutPlaylist stores a playlist as given (used by sync).
func (l *Library) PutPlaylist(p Playlist) {
	l.mu.Lock()
	cp := p
	cp.Tracks = dedupe(nil, p.Tracks)
	l.playlists[p.ID] = &cp
	l.mu.Unlock()
	l.changed()
}

// AddToPlaylist appends songs that are not already in it and reports how many were added.
func (l *Library) AddToPlaylist(id string, ids []string) (int, error) {
	l.mu.Lock()
	p, ok := l.playlists[id]
	if !ok {
		l.mu.Unlock()
		return 0, errors.New("no such playlist")
	}
	before := len(p.Tracks)
	p.Tracks = dedupe(p.Tracks, ids)
	added := len(p.Tracks) - before
	if added > 0 {
		p.Modified = time.Now().Unix()
	}
	l.mu.Unlock()
	if added > 0 {
		l.changed()
	}
	return added, nil
}

// RemoveFromPlaylist drops the song at position i.
func (l *Library) RemoveFromPlaylist(id string, i int) {
	l.mu.Lock()
	if p, ok := l.playlists[id]; ok && i >= 0 && i < len(p.Tracks) {
		p.Tracks = append(p.Tracks[:i], p.Tracks[i+1:]...)
		p.Modified = time.Now().Unix()
	}
	l.mu.Unlock()
	l.changed()
}

// RenamePlaylist renames a playlist.
func (l *Library) RenamePlaylist(id, name string) {
	name = strings.TrimSpace(name)
	l.mu.Lock()
	if p, ok := l.playlists[id]; ok && name != "" {
		p.Name = name
		p.Modified = time.Now().Unix()
	}
	l.mu.Unlock()
	l.changed()
}

// DeletePlaylist removes a playlist (not its songs).
func (l *Library) DeletePlaylist(id string) {
	l.mu.Lock()
	delete(l.playlists, id)
	l.mu.Unlock()
	l.changed()
}

func dedupe(have, add []string) []string {
	seen := make(map[string]bool, len(have)+len(add))
	out := have[:len(have):len(have)]
	for _, id := range have {
		seen[id] = true
	}
	for _, id := range add {
		if id != "" && !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	return out
}

// Played counts a play of a song (called when it has played for a while).
func (l *Library) Played(id string) {
	l.mu.Lock()
	t, ok := l.tracks[id]
	if ok {
		cp := *t
		cp.Plays++
		cp.LastPlayed = time.Now().Unix()
		l.tracks[id] = &cp
	}
	l.mu.Unlock()
	if ok {
		l.changed()
	}
}
