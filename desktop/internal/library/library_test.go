package library

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func makeSong(t *testing.T, path, title, artist, album, genre, date, track string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
		"-metadata", "title="+title, "-metadata", "artist="+artist, "-metadata", "album="+album,
		"-metadata", "genre="+genre, "-metadata", "date="+date, "-metadata", "track="+track, path)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Skipf("ffmpeg unavailable: %v %s", err, out)
	}
}

func TestScanQueriesAndRescan(t *testing.T) {
	dir := t.TempDir()
	music := filepath.Join(dir, "Music")
	makeSong(t, filepath.Join(music, "Grid Pilots", "Runways", "02.flac"), "Lightcycle Lullaby", "Grid Pilots", "Runways", "Synthwave", "1987", "2")
	makeSong(t, filepath.Join(music, "Grid Pilots", "Runways", "01.flac"), "Neon Runway", "Grid Pilots", "Runways", "Synthwave", "1987", "1")
	makeSong(t, filepath.Join(music, "Static Coast", "x.mp3"), "Analog Horizon", "Static Coast", "Tides", "Ambient", "1994", "1")

	l := Open(filepath.Join(dir, "lib.json"))
	l.SetFolders([]string{music})
	res := l.Scan()
	if res.Added != 3 || res.Failed != 0 {
		t.Fatalf("scan = %+v, want 3 added", res)
	}
	album := l.Songs(Filter{Artist: "Grid Pilots"})
	if len(album) != 2 || album[0].Title != "Neon Runway" || album[0].TrackNo != 1 {
		t.Fatalf("album order wrong: %v", album)
	}
	if album[0].Codec != "FLAC" || album[0].DurationMs < 900 {
		t.Fatalf("format not read: %+v", album[0])
	}
	if got := l.Artists(); len(got) != 2 || got[0].Name != "Grid Pilots" || got[0].Songs != 2 {
		t.Fatalf("artists = %+v", got)
	}
	if got := l.Genres(); len(got) != 2 {
		t.Fatalf("genres = %+v", got)
	}
	if got := l.Decades(); len(got) != 2 || got[0] != 1990 {
		t.Fatalf("decades = %v", got)
	}
	if got := l.Songs(Filter{Query: "horizon"}); len(got) != 1 || got[0].Codec != "MP3" {
		t.Fatalf("search = %v", got)
	}

	// A second scan with nothing changed reads nothing; a deleted file disappears.
	if res := l.Scan(); res.Added+res.Updated+res.Removed != 0 {
		t.Fatalf("unchanged rescan = %+v", res)
	}
	os.Remove(filepath.Join(music, "Static Coast", "x.mp3"))
	if res := l.Scan(); res.Removed != 1 || l.Count() != 2 {
		t.Fatalf("rescan after delete = %+v, count %d", res, l.Count())
	}

	// Saved and reloaded, the catalogue is the same.
	if err := l.Save(); err != nil {
		t.Fatal(err)
	}
	if l2 := Open(filepath.Join(dir, "lib.json")); l2.Count() != 2 || len(l2.Folders()) != 1 {
		t.Fatalf("reloaded %d songs, %d folders", l2.Count(), len(l2.Folders()))
	}
}

func TestPlaylistsRefuseDuplicates(t *testing.T) {
	l := Open(filepath.Join(t.TempDir(), "lib.json"))
	id, err := l.CreatePlaylist(" Night Drive ", []string{"a", "b", "a"})
	if err != nil {
		t.Fatal(err)
	}
	if n, _ := l.AddToPlaylist(id, []string{"b", "c"}); n != 1 {
		t.Fatalf("added %d, want 1 (b was already there)", n)
	}
	p, _, _ := l.Playlist(id)
	if p.Name != "Night Drive" || len(p.Tracks) != 3 || p.Tracks[2] != "c" {
		t.Fatalf("playlist = %+v", p)
	}
	if _, err := l.CreatePlaylist("  ", nil); err == nil {
		t.Fatal("an empty name should be refused")
	}
	l.Save()
	if l2 := Open(l.file); len(l2.Playlists()) != 1 {
		t.Fatal("playlist not saved")
	}
}
