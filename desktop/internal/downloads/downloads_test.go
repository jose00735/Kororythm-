package downloads

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestParsePlaylistProbe(t *testing.T) {
	b := []byte(`{"_type":"playlist","title":"Night Drive","entries":[
		{"id":"a1","url":"https://www.youtube.com/watch?v=a1","title":"Grid Pilots - Neon Runway","uploader":"Grid Pilots","duration":173},
		{"id":"b2","title":"Second","channel":"Static Coast","duration":200.5}]}`)
	p, err := parseProbe(b, "https://www.youtube.com/playlist?list=x")
	if err != nil {
		t.Fatal(err)
	}
	if !p.Playlist || len(p.Entries) != 2 || p.Entries[1].URL != "https://www.youtube.com/watch?v=b2" || p.Entries[1].Uploader != "Static Coast" {
		t.Fatalf("probe = %+v", p)
	}
}

func TestParseSingleProbe(t *testing.T) {
	b := []byte(`{"id":"v","title":"One song","uploader":"Me","duration":61,"webpage_url":"https://youtu.be/v"}`)
	p, err := parseProbe(b, "https://youtu.be/v")
	if err != nil || p.Playlist || len(p.Entries) != 1 || p.Entries[0].Duration != 61 {
		t.Fatalf("probe = %+v, %v", p, err)
	}
}

func TestArgsSaveTaggedMP3(t *testing.T) {
	a := strings.Join(Args(Entry{URL: "https://youtu.be/v"}, MP3V0, "/music", "/tools"), " ")
	for _, want := range []string{"--audio-format mp3", "--audio-quality 0", "--embed-metadata", "--embed-thumbnail", "--ffmpeg-location /tools", "https://youtu.be/v"} {
		if !strings.Contains(a, want) {
			t.Fatalf("args missing %q: %s", want, a)
		}
	}
	if !strings.Contains(strings.Join(Args(Entry{}, MP3320, "/m", ""), " "), "--audio-quality 320K") {
		t.Fatal("320 kbps format not passed")
	}
}

// A fake yt-dlp script proves the probe → download → done flow and progress parsing.
func TestDownloadFlowWithFakeTool(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("uses a shell script")
	}
	dir := t.TempDir()
	tools := filepath.Join(dir, "tools")
	os.MkdirAll(tools, 0o755)
	out := filepath.Join(dir, "out")
	script := `#!/bin/sh
for a in "$@"; do last="$a"; done
case "$*" in
  *-J*) echo '{"id":"v","title":"Song","uploader":"Me","duration":5,"webpage_url":"'$last'"}' ;;
  *) echo "[download]  10.0% of 1MiB"; echo "[download] 100.0% of 1MiB"; echo "[ExtractAudio] Destination: x"
     mkdir -p "` + out + `"; touch "` + out + `/Me - Song.mp3"; echo "` + out + `/Me - Song.mp3" ;;
esac
`
	os.WriteFile(filepath.Join(tools, "yt-dlp"), []byte(script), 0o755)
	os.WriteFile(filepath.Join(tools, "ffmpeg"), []byte("#!/bin/sh\n"), 0o755)
	m := New(tools, out)
	if !m.State().Tools.YtDlp || !m.State().Tools.Ffmpeg {
		t.Fatal("tools not found")
	}
	var got string
	m.OnDone(func(p string) { got = p })
	m.ProbeURL("https://youtu.be/v")
	wait(t, func() bool { return m.State().Probe != nil })
	if err := m.Start(nil, MP3V0); err != nil {
		t.Fatal(err)
	}
	wait(t, func() bool { return !m.State().Running })
	j := m.State().Jobs[0]
	if j.Status != "done" || j.Progress != 1 || !strings.HasSuffix(got, "Me - Song.mp3") {
		t.Fatalf("job = %+v, saved %q", j, got)
	}
}

func wait(t *testing.T, ok func() bool) {
	t.Helper()
	for i := 0; i < 300; i++ {
		if ok() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("timed out")
}
