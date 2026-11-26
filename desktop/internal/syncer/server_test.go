package syncer

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"github.com/jose00735/kororythm/desktop/internal/library"
)

func fixture(name string) string {
	return filepath.Join("..", "..", "..", "protocol", "fixtures", name)
}

func copyFile(t *testing.T, src, dst string) {
	b, err := os.ReadFile(src)
	if err != nil {
		t.Fatal(err)
	}
	os.MkdirAll(filepath.Dir(dst), 0o755)
	if err := os.WriteFile(dst, b, 0o644); err != nil {
		t.Fatal(err)
	}
}

type phone struct {
	t      *testing.T
	base   string
	client *http.Client
	token  string
	seenFP string
}

func (p *phone) do(method, path string, body any, out any) int {
	var r io.Reader
	switch b := body.(type) {
	case nil:
	case []byte:
		r = bytes.NewReader(b)
	default:
		j, _ := json.Marshal(b)
		r = bytes.NewReader(j)
	}
	req, _ := http.NewRequest(method, p.base+path, r)
	if p.token != "" {
		req.Header.Set("Authorization", "Bearer "+p.token)
	}
	res, err := p.client.Do(req)
	if err != nil {
		p.t.Fatal(err)
	}
	defer res.Body.Close()
	if res.TLS != nil && len(res.TLS.PeerCertificates) > 0 {
		h := sha256.Sum256(res.TLS.PeerCertificates[0].Raw)
		p.seenFP = hex.EncodeToString(h[:])
	}
	if out != nil {
		if bp, ok := out.(*[]byte); ok {
			*bp, _ = io.ReadAll(res.Body)
		} else {
			json.NewDecoder(res.Body).Decode(out)
		}
	}
	return res.StatusCode
}

func TestSyncJourney(t *testing.T) {
	tmp := t.TempDir()
	music := filepath.Join(tmp, "Music")
	copyFile(t, fixture("a.flac"), filepath.Join(music, "Grid Pilots", "a.flac"))
	copyFile(t, fixture("t.mp3"), filepath.Join(music, "t.mp3"))
	lib := library.Open(filepath.Join(tmp, "library.json"))
	lib.SetFolders([]string{music})
	lib.Scan()
	if lib.Count() != 2 {
		t.Fatalf("library has %d songs", lib.Count())
	}
	var events []Event
	srv, err := New(Config{Dir: filepath.Join(tmp, "sync"), Name: "TEST-PC", Library: lib, Inbox: filepath.Join(music, "Kororythm Sync"),
		Addr: "127.0.0.1:0", Notify: func(e Event) { events = append(events, e) }})
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Start(); err != nil {
		t.Fatal(err)
	}
	defer srv.Close()

	ph := &phone{t: t, base: "https://" + srv.Addr(), client: &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}}
	var hello Hello
	ph.do("GET", "/v1/hello", nil, &hello)
	if hello.FP != srv.FP() || ph.seenFP != srv.FP() || hello.Name != "TEST-PC" {
		t.Fatalf("hello %+v seen %s", hello, ph.seenFP)
	}

	// pairing: no code → refused; wrong code → refused; right code → token, and the PC proves itself
	nonce := "00112233445566778899aabbccddeeff"
	req := PairRequest{Device: "phone-1", Name: "Pixel", Nonce: nonce, Proof: Proof("123456", "kororythm-pair", nonce, ph.seenFP)}
	if c := ph.do("POST", "/v1/pair", req, nil); c != 403 {
		t.Fatal("pairing without a code", c)
	}
	code := srv.NewCode()
	wrong := "000000"
	if code == wrong {
		wrong = "111111"
	}
	req.Proof = Proof(wrong, "kororythm-pair", nonce, ph.seenFP)
	if c := ph.do("POST", "/v1/pair", req, nil); c != 403 {
		t.Fatal("wrong code accepted", c)
	}
	req.Proof = Proof(code, "kororythm-pair", nonce, ph.seenFP)
	var pr PairResponse
	if c := ph.do("POST", "/v1/pair", req, &pr); c != 200 || pr.Token == "" {
		t.Fatal("pairing failed", c)
	}
	if pr.Proof != Proof(code, "kororythm-server", nonce, ph.seenFP) {
		t.Fatal("PC proof does not check out")
	}
	if c := ph.do("POST", "/v1/sync/start", StartRequest{}, nil); c != 401 {
		t.Fatal("start without token", c)
	}
	ph.token = pr.Token

	fpA, _ := Fingerprint(fixture("a.flac"))
	fpT, _ := Fingerprint(fixture("t-retagged.mp3"))
	fpN, _ := Fingerprint(fixture("a-nomd5.flac"))
	nInfo, _ := os.Stat(fixture("a-nomd5.flac"))
	start := StartRequest{
		Device: "phone-1",
		Tracks: []ManifestTrack{
			{FP: fpT, Name: "t.mp3", Size: 1, Title: "Retagged", Plays: 3},
			{FP: fpN, Name: "night.flac", Size: nInfo.Size(), Title: "Night", Artist: "Phone Band", Album: "Pocket"},
		},
		Playlists: []ManifestPlaylist{{Name: "Phone mix", Items: []string{fpN, fpT}, Modified: 5}},
	}
	var plan Plan
	if c := ph.do("POST", "/v1/sync/start", start, &plan); c != 200 {
		t.Fatal("start", c)
	}
	if len(plan.ToPhone) != 1 || plan.ToPhone[0].FP != fpA || len(plan.ToPC) != 1 || plan.ToPC[0].FP != fpN {
		t.Fatalf("plan %+v", plan)
	}
	if plan.ToPC[0].Folder != "Phone Band/Pocket" {
		t.Fatal("folder", plan.ToPC[0].Folder)
	}

	// download in two parts (resume with Range)
	want, _ := os.ReadFile(fixture("a.flac"))
	r1, _ := http.NewRequest("GET", ph.base+"/v1/files/"+fpA+"?s="+plan.Session, nil)
	r1.Header.Set("Authorization", "Bearer "+ph.token)
	r1.Header.Set("Range", "bytes=1000-")
	res, err := ph.client.Do(r1)
	if err != nil || res.StatusCode != 206 {
		t.Fatal("range", err, res.StatusCode)
	}
	tail, _ := io.ReadAll(res.Body)
	res.Body.Close()
	var whole []byte
	ph.do("GET", "/v1/files/"+fpA+"?s="+plan.Session, nil, &whole)
	if !bytes.Equal(whole, want) || !bytes.Equal(tail, want[1000:]) {
		t.Fatal("downloaded bytes differ")
	}

	// upload in two parts, with a wrong offset refused
	up, _ := os.ReadFile(fixture("a-nomd5.flac"))
	if c := ph.do("PUT", fmt.Sprintf("/v1/upload/%s?s=%s&offset=10", fpN, plan.Session), up[10:], nil); c != 409 {
		t.Fatal("bad offset accepted", c)
	}
	var off map[string]int64
	ph.do("PUT", fmt.Sprintf("/v1/upload/%s?s=%s&offset=0", fpN, plan.Session), up[:20000], &off)
	ph.do("GET", "/v1/upload/"+fpN, nil, &off)
	if off["offset"] != 20000 {
		t.Fatal("offset", off)
	}
	if c := ph.do("PUT", fmt.Sprintf("/v1/upload/%s?s=%s&offset=20000", fpN, plan.Session), up[20000:], &off); c != 200 {
		t.Fatal("upload", c)
	}
	if _, err := os.Stat(filepath.Join(music, "Kororythm Sync", "Phone Band", "Pocket", "night.flac")); err != nil {
		t.Fatal("uploaded file not placed", err)
	}

	var fin FinishResponse
	ph.do("POST", "/v1/sync/finish", FinishRequest{Session: plan.Session, Plays: map[string]PlayCount{fpT: {Plays: 3, LastPlayed: 1000}}}, &fin)
	if fin.Plays[fpT].Plays != 3 || fin.Received != 1 || fin.Sent != 1 {
		t.Fatalf("finish %+v", fin)
	}
	if lib.Count() != 3 {
		t.Fatal("uploaded song not in the library", lib.Count())
	}
	var mix *library.Playlist
	for _, p := range lib.AllPlaylists() {
		if p.Name == "Phone mix" {
			p := p
			mix = &p
		}
	}
	if mix == nil || len(mix.Tracks) != 2 {
		t.Fatalf("phone playlist not on the PC: %+v", mix)
	}

	// second sync: one more play on the PC, two more on the phone → 3 + 1 + 2 = 6
	for _, tr := range lib.Tracks() {
		if tr.Fingerprint == fpT {
			lib.Played(tr.ID)
		}
	}
	ph.do("POST", "/v1/sync/start", StartRequest{Device: "phone-1", Tracks: start.Tracks}, &plan)
	if len(plan.ToPC) != 0 || len(plan.ToPhone) != 1 { // a.flac again: this fake phone did not keep it
		t.Fatalf("second plan %+v", plan)
	}
	ph.do("POST", "/v1/sync/finish", FinishRequest{Session: plan.Session, Plays: map[string]PlayCount{fpT: {Plays: 5}}}, &fin)
	if fin.Plays[fpT].Plays != 6 {
		t.Fatal("merged plays", fin.Plays[fpT])
	}
	kinds := map[string]bool{}
	for _, e := range events {
		kinds[e.Kind] = true
	}
	for _, k := range []string{"code", "paired", "start", "plan", "file", "received", "done"} {
		if !kinds[k] {
			t.Error("no event", k)
		}
	}
}

func TestMergePlaylistsAndBudget(t *testing.T) {
	pc := []ManifestPlaylist{{Name: "A", Items: []string{"1"}, Modified: 10}, {Name: "B", Items: []string{"1"}, Modified: 10}, {Name: "C", Items: []string{"1"}, Modified: 1}}
	ph := []ManifestPlaylist{{Name: "b", Items: []string{"2"}, Modified: 5}, {Name: "C", Items: []string{"2"}, Modified: 9}, {Name: "D", Items: []string{"3"}}}
	toPhone, toPC := MergePlaylists(pc, ph)
	if len(toPhone) != 2 || toPhone[0].Name != "A" || toPhone[1].Name != "B" {
		t.Fatalf("to phone %+v", toPhone)
	}
	if len(toPC) != 2 || toPC[0].Name != "C" || toPC[1].Name != "D" {
		t.Fatalf("to pc %+v", toPC)
	}
	if MergePlays(4, 5, 3) != 6 || MergePlays(2, 1, 9) != 2 {
		t.Fatal("plays")
	}
	tracks := []PCTrack{{FP: "x", Name: "x.flac", Size: 100, Bits: 24}, {FP: "y", Name: "y.mp3", Size: 100}, {FP: "z", Name: "z.flac", Size: 100}}
	plan := ComputePlan(tracks, []ManifestPlaylist{{Name: "Road", Items: []string{"x", "y"}}}, StartRequest{Options: Options{Only: []string{"road"}, Budget: 170, MaxBits: 16}})
	if len(plan.ToPhone) != 2 || !plan.ToPhone[0].Convert || plan.ToPhone[0].Size != 66 || plan.Skipped != 0 {
		t.Fatalf("selective plan %+v", plan)
	}
	plan = ComputePlan(tracks, nil, StartRequest{Options: Options{Budget: 150}})
	if len(plan.ToPhone) != 1 || plan.Skipped != 2 {
		t.Fatalf("budget plan %+v", plan)
	}
}
