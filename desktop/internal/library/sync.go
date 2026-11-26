package library

import (
	"os"
)

// Tracks returns a copy of every track, for the phone sync.
func (l *Library) Tracks() []Track {
	l.mu.RLock()
	defer l.mu.RUnlock()
	out := make([]Track, 0, len(l.tracks))
	for _, t := range l.tracks {
		out = append(out, *t)
	}
	return out
}

// AddFile reads a song that was just written (a synced file) and adds it to the library.
func (l *Library) AddFile(path string) (*Track, error) {
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	t, err := ReadTrack(path, info)
	if err != nil {
		return nil, err
	}
	l.put([]*Track{t}, nil)
	return t, nil
}

// SetFingerprint caches a song's audio fingerprint.
func (l *Library) SetFingerprint(id, fp string) {
	l.update(id, func(t *Track) { t.Fingerprint = fp })
}

// SetPlays sets a song's play count (after merging with a phone's).
func (l *Library) SetPlays(id string, plays int, last int64) {
	l.update(id, func(t *Track) {
		t.Plays = plays
		if last > t.LastPlayed {
			t.LastPlayed = last
		}
	})
}

func (l *Library) update(id string, f func(*Track)) {
	l.mu.Lock()
	t, ok := l.tracks[id]
	if ok {
		cp := *t
		f(&cp)
		l.tracks[id] = &cp
	}
	l.mu.Unlock()
	if ok {
		l.changed()
	}
}
