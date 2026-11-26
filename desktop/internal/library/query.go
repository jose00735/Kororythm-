package library

import (
	"sort"
	"strings"
)

// Artist is one row of the Artists list.
type Artist struct {
	Name   string `json:"name"`
	Albums int    `json:"albums"`
	Songs  int    `json:"songs"`
	ArtID  string `json:"artId,omitempty"`
}

// Album is one row of the Albums list.
type Album struct {
	Key    string `json:"key"`
	Title  string `json:"title"`
	Artist string `json:"artist"`
	Year   int    `json:"year,omitempty"`
	Songs  int    `json:"songs"`
	ArtID  string `json:"artId,omitempty"`
}

// Genre is one row of the Genres list.
type Genre struct {
	Name  string `json:"name"`
	Songs int    `json:"songs"`
}

// Filter narrows a song list.
type Filter struct {
	Artist string // matches artist or album artist
	Album  string // album key
	Genre  string
	Query  string // free text over title, artist, album
	Decade int    // e.g. 1990
}

func lower(s string) string { return strings.ToLower(strings.TrimSpace(s)) }

func albumOrder(a, b *Track) bool {
	if a.AlbumKey() != b.AlbumKey() {
		return a.AlbumKey() < b.AlbumKey()
	}
	if a.DiscNo != b.DiscNo {
		return a.DiscNo < b.DiscNo
	}
	if a.TrackNo != b.TrackNo {
		return a.TrackNo < b.TrackNo
	}
	return a.Path < b.Path
}

// Songs returns tracks matching f: albums in track order, otherwise by artist,
// album and track.
func (l *Library) Songs(f Filter) []*Track {
	q := lower(f.Query)
	var out []*Track
	for _, t := range l.all() {
		if f.Artist != "" && lower(t.Artist) != lower(f.Artist) && lower(t.AlbumArtist) != lower(f.Artist) {
			continue
		}
		if f.Album != "" && t.AlbumKey() != f.Album {
			continue
		}
		if f.Genre != "" && lower(t.Genre) != lower(f.Genre) {
			continue
		}
		if f.Decade != 0 && (t.Year < f.Decade || t.Year >= f.Decade+10) {
			continue
		}
		if q != "" && !strings.Contains(lower(t.Title+" "+t.Artist+" "+t.Album+" "+t.Genre), q) {
			continue
		}
		out = append(out, t)
	}
	if f.Album != "" || f.Artist != "" {
		sort.Slice(out, func(i, j int) bool { return albumOrder(out[i], out[j]) })
	} else {
		sort.Slice(out, func(i, j int) bool {
			if a, b := lower(out[i].Artist), lower(out[j].Artist); a != b {
				return a < b
			}
			return albumOrder(out[i], out[j])
		})
	}
	return out
}

// Artists lists artists by name.
func (l *Library) Artists() []Artist {
	type acc struct {
		a      Artist
		albums map[string]bool
	}
	m := map[string]*acc{}
	for _, t := range l.all() {
		name := t.AlbumArtist
		if name == "" {
			name = t.Artist
		}
		k := lower(name)
		a := m[k]
		if a == nil {
			a = &acc{a: Artist{Name: name}, albums: map[string]bool{}}
			m[k] = a
		}
		a.a.Songs++
		a.albums[t.AlbumKey()] = true
		if a.a.ArtID == "" && t.HasArt {
			a.a.ArtID = t.ID
		}
	}
	out := make([]Artist, 0, len(m))
	for _, a := range m {
		a.a.Albums = len(a.albums)
		out = append(out, a.a)
	}
	sort.Slice(out, func(i, j int) bool { return lower(out[i].Name) < lower(out[j].Name) })
	return out
}

// Albums lists albums, optionally only one artist's.
func (l *Library) Albums(artist string) []Album {
	m := map[string]*Album{}
	for _, t := range l.all() {
		if artist != "" && lower(t.Artist) != lower(artist) && lower(t.AlbumArtist) != lower(artist) {
			continue
		}
		k := t.AlbumKey()
		a := m[k]
		if a == nil {
			who := t.AlbumArtist
			if who == "" {
				who = t.Artist
			}
			a = &Album{Key: k, Title: t.Album, Artist: who, Year: t.Year}
			m[k] = a
		}
		a.Songs++
		if a.ArtID == "" && t.HasArt {
			a.ArtID = t.ID
		}
		if t.Year > 0 && (a.Year == 0 || t.Year < a.Year) {
			a.Year = t.Year
		}
	}
	out := make([]Album, 0, len(m))
	for _, a := range m {
		out = append(out, *a)
	}
	sort.Slice(out, func(i, j int) bool {
		if artist != "" && out[i].Year != out[j].Year {
			return out[i].Year < out[j].Year
		}
		if a, b := lower(out[i].Artist), lower(out[j].Artist); a != b {
			return a < b
		}
		return lower(out[i].Title) < lower(out[j].Title)
	})
	return out
}

// Genres lists genres with their song counts.
func (l *Library) Genres() []Genre {
	m := map[string]*Genre{}
	for _, t := range l.all() {
		if t.Genre == "" {
			continue
		}
		k := lower(t.Genre)
		if m[k] == nil {
			m[k] = &Genre{Name: t.Genre}
		}
		m[k].Songs++
	}
	out := make([]Genre, 0, len(m))
	for _, g := range m {
		out = append(out, *g)
	}
	sort.Slice(out, func(i, j int) bool { return lower(out[i].Name) < lower(out[j].Name) })
	return out
}

// Decades lists the decades present, newest first.
func (l *Library) Decades() []int {
	seen := map[int]bool{}
	for _, t := range l.all() {
		if t.Year > 1900 {
			seen[t.Year/10*10] = true
		}
	}
	var out []int
	for d := range seen {
		out = append(out, d)
	}
	sort.Sort(sort.Reverse(sort.IntSlice(out)))
	return out
}
