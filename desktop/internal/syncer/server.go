package syncer

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jose00735/kororythm/desktop/internal/library"
)

// Event tells the UI what the sync is doing (it drives the animations).
type Event struct {
	Kind       string `json:"kind"` // code, paired, start, file, progress, received, done, error
	Device     string `json:"device,omitempty"`
	Dir        string `json:"dir,omitempty"` // "toPhone" | "toPc"
	Title      string `json:"title,omitempty"`
	Artist     string `json:"artist,omitempty"`
	ArtID      string `json:"artId,omitempty"`
	Done       int    `json:"done,omitempty"`
	Total      int    `json:"total,omitempty"`
	Bytes      int64  `json:"bytes,omitempty"`
	TotalBytes int64  `json:"totalBytes,omitempty"`
	Message    string `json:"message,omitempty"`
}

// Config wires the server to the app.
type Config struct {
	Dir     string // where cert, key and sync.json live
	Name    string // the PC's name shown on the phone
	Library *library.Library
	// Inbox is the folder songs from the phone are saved in ("Music/Kororythm Sync").
	Inbox string
	// Notify gets every event (may be nil).
	Notify func(Event)
	// FFmpeg returns the path of ffmpeg, or "" (then nothing is converted to 16-bit).
	FFmpeg func() string
	// Addr overrides ":47800" (tests).
	Addr string
}

// Server is the PC side of the sync.
type Server struct {
	cfg   Config
	st    *store
	cert  tls.Certificate
	fp    string
	ln    net.Listener
	udp   net.PacketConn
	http  *http.Server
	mu    sync.Mutex
	code  string
	exp   time.Time
	tries int
	sess  map[string]*session
	busy  string // device syncing now
	seen  time.Time
}

type session struct {
	dev      *Device
	plan     Plan
	toPhone  map[string]Transfer
	toPC     map[string]Transfer
	sent     map[string]bool
	received int
	bytes    int64
	total    int64
	files    int
	created  time.Time
	lastNote time.Time
	// the phone's playlists as it sent them, merged into the PC's at finish
	phoneLists []ManifestPlaylist
}

// New loads (or makes) the certificate and the paired phones. Call Start to listen.
func New(cfg Config) (*Server, error) {
	if cfg.Addr == "" {
		cfg.Addr = fmt.Sprintf(":%d", Port)
	}
	os.MkdirAll(cfg.Dir, 0o700)
	cert, fp, err := loadCert(cfg.Dir)
	if err != nil {
		return nil, err
	}
	return &Server{cfg: cfg, st: openStore(cfg.Dir), cert: cert, fp: fp, sess: map[string]*session{}}, nil
}

// FP is the certificate fingerprint; Short is the first 8 hex digits in two groups (shown in the UI).
func (s *Server) FP() string    { return s.fp }
func (s *Server) Short() string { return strings.ToUpper(s.fp[:4] + " " + s.fp[4:8]) }

// Addr is where the HTTPS server listens (after Start).
func (s *Server) Addr() string {
	if s.ln == nil {
		return ""
	}
	return s.ln.Addr().String()
}

func (s *Server) note(e Event) {
	if s.cfg.Notify != nil {
		s.cfg.Notify(e)
	}
}

// Start listens for phones (HTTPS) and answers discovery broadcasts (UDP).
func (s *Server) Start() error {
	ln, err := net.Listen("tcp", s.cfg.Addr)
	if err != nil {
		return err
	}
	s.ln = tls.NewListener(ln, &tls.Config{Certificates: []tls.Certificate{s.cert}, MinVersion: tls.VersionTLS12})
	s.http = &http.Server{Handler: s.Handler(), ReadHeaderTimeout: 15 * time.Second}
	go s.http.Serve(s.ln)
	if pc, err := net.ListenPacket("udp4", fmt.Sprintf(":%d", DiscoveryPort)); err == nil {
		s.udp = pc
		go s.answer(pc)
	} else {
		log.Printf("sync: discovery off: %v", err)
	}
	return nil
}

// Close stops listening.
func (s *Server) Close() {
	if s.udp != nil {
		s.udp.Close()
	}
	if s.http != nil {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		s.http.Shutdown(ctx)
		cancel()
	}
}

func (s *Server) hello() Hello {
	port := Port
	if s.ln != nil {
		if a, ok := s.ln.Addr().(*net.TCPAddr); ok {
			port = a.Port
		}
	}
	return Hello{App: "kororythm", V: Version, Name: s.cfg.Name, Port: port, FP: s.fp}
}

func (s *Server) answer(pc net.PacketConn) {
	buf := make([]byte, 256)
	for {
		n, from, err := pc.ReadFrom(buf)
		if err != nil {
			return
		}
		if strings.TrimSpace(string(buf[:n])) == DiscoverProbe {
			b, _ := json.Marshal(s.hello())
			pc.WriteTo(b, from)
		}
	}
}

// NewCode makes a fresh 6-digit pairing code, valid for five minutes.
func (s *Server) NewCode() string {
	n, _ := rand.Int(rand.Reader, big.NewInt(1_000_000))
	s.mu.Lock()
	s.code = fmt.Sprintf("%06d", n.Int64())
	s.exp = time.Now().Add(5 * time.Minute)
	s.tries = 0
	code := s.code
	s.mu.Unlock()
	s.note(Event{Kind: "code", Message: code})
	return code
}

// Code is the current pairing code ("" when none) and when it expires.
func (s *Server) Code() (string, time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if time.Now().After(s.exp) {
		return "", time.Time{}
	}
	return s.code, s.exp
}

// Devices lists the paired phones.
func (s *Server) Devices() []DeviceInfo { return s.st.list() }

// Forget unpairs a phone.
func (s *Server) Forget(id string) {
	s.st.mu.Lock()
	delete(s.st.Devices, id)
	s.st.save()
	s.st.mu.Unlock()
}

// Proof is HMAC-SHA256(code, label|nonce|fp) in hex: both sides prove they know the code.
func Proof(code, label, nonce, fp string) string {
	m := hmac.New(sha256.New, []byte(code))
	m.Write([]byte(label + "|" + nonce + "|" + fp))
	return hex.EncodeToString(m.Sum(nil))
}

// Handler is the HTTP API (exported for tests).
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/hello", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, s.hello()) })
	mux.HandleFunc("POST /v1/pair", s.pair)
	mux.HandleFunc("POST /v1/sync/start", s.auth(s.start))
	mux.HandleFunc("GET /v1/files/{fp}", s.auth(s.download))
	mux.HandleFunc("GET /v1/upload/{fp}", s.auth(s.uploadOffset))
	mux.HandleFunc("PUT /v1/upload/{fp}", s.auth(s.upload))
	mux.HandleFunc("POST /v1/sync/finish", s.auth(s.finish))
	mux.HandleFunc("GET /v1/art/{id}", s.auth(func(w http.ResponseWriter, r *http.Request, d *Device) {
		b, mime, ok := s.cfg.Library.Art(r.PathValue("id"))
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", mime)
		w.Write(b)
	}))
	return mux
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func fail(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func (s *Server) auth(h func(http.ResponseWriter, *http.Request, *Device)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		d := s.st.byToken(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		if d == nil {
			fail(w, http.StatusUnauthorized, "not paired")
			return
		}
		h(w, r, d)
	}
}

func (s *Server) pair(w http.ResponseWriter, r *http.Request) {
	var req PairRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil || req.Device == "" || len(req.Nonce) < 16 {
		fail(w, http.StatusBadRequest, "bad request")
		return
	}
	s.mu.Lock()
	code, live := s.code, time.Now().Before(s.exp) && s.code != ""
	if live {
		s.tries++
		if s.tries > 5 {
			s.code, live = "", false
		}
	}
	s.mu.Unlock()
	if !live {
		fail(w, http.StatusForbidden, "no pairing code is showing on the PC")
		return
	}
	want := Proof(code, "kororythm-pair", req.Nonce, s.fp)
	if !hmac.Equal([]byte(want), []byte(strings.ToLower(req.Proof))) {
		fail(w, http.StatusForbidden, "wrong code")
		return
	}
	s.mu.Lock()
	s.code = "" // one phone per code
	s.mu.Unlock()
	name := strings.TrimSpace(req.Name)
	if name == "" {
		name = "Phone"
	}
	dev := &Device{ID: req.Device, Name: name, Token: randHex(32), Paired: time.Now().UnixMilli(), Base: map[string]int{}}
	s.st.mu.Lock()
	if old, ok := s.st.Devices[req.Device]; ok {
		dev.Base, dev.LastSync = old.Base, old.LastSync
	}
	s.st.Devices[req.Device] = dev
	s.st.save()
	s.st.mu.Unlock()
	s.note(Event{Kind: "paired", Device: name})
	writeJSON(w, PairResponse{Token: dev.Token, Name: s.cfg.Name, Proof: Proof(code, "kororythm-server", req.Nonce, s.fp)})
}

// pcTracks fingerprints the library (cached in the library after the first time).
func (s *Server) pcTracks() []PCTrack {
	lib := s.cfg.Library
	var out []PCTrack
	for _, t := range lib.Tracks() {
		fp := t.Fingerprint
		if fp == "" {
			var err error
			if fp, err = Fingerprint(t.Path); err != nil {
				continue
			}
			lib.SetFingerprint(t.ID, fp)
		}
		out = append(out, PCTrack{ID: t.ID, FP: fp, Name: filepath.Base(t.Path), Size: t.Size, Title: t.Title, Artist: t.Artist,
			Album: t.Album, Bits: t.BitDepth, HasArt: t.HasArt, Plays: t.Plays, LastPlay: t.LastPlayed * 1000})
	}
	return out
}

func (s *Server) pcPlaylists(byID map[string]string) []ManifestPlaylist {
	var out []ManifestPlaylist
	for _, p := range s.cfg.Library.AllPlaylists() {
		items := []string{}
		for _, id := range p.Tracks {
			if fp, ok := byID[id]; ok {
				items = append(items, fp)
			}
		}
		out = append(out, ManifestPlaylist{Name: p.Name, Items: items, Modified: p.Modified * 1000})
	}
	return out
}

func (s *Server) start(w http.ResponseWriter, r *http.Request, d *Device) {
	var req StartRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 64<<20)).Decode(&req); err != nil {
		fail(w, http.StatusBadRequest, "bad manifest")
		return
	}
	s.mu.Lock()
	if s.busy != "" && s.busy != d.ID && time.Since(s.seen) < 10*time.Minute {
		s.mu.Unlock()
		fail(w, http.StatusConflict, "another phone is syncing")
		return
	}
	s.busy, s.seen = d.ID, time.Now()
	s.mu.Unlock()
	s.note(Event{Kind: "start", Device: d.Name, Message: "Comparing libraries…"})

	pc := s.pcTracks()
	byID := map[string]string{}
	for _, t := range pc {
		byID[t.ID] = t.FP
	}
	if req.Options.MaxBits > 0 && (s.cfg.FFmpeg == nil || s.cfg.FFmpeg() == "") {
		req.Options.MaxBits = 0 // nothing to convert with: send the originals
	}
	plan := ComputePlan(pc, s.pcPlaylists(byID), req)
	plan.Session = randHex(16)
	plan.PCName = s.cfg.Name
	ss := &session{dev: d, plan: plan, phoneLists: req.Playlists, toPhone: map[string]Transfer{}, toPC: map[string]Transfer{}, sent: map[string]bool{}, created: time.Now()}
	for _, t := range plan.ToPhone {
		ss.toPhone[t.FP] = t
		ss.total += t.Size
	}
	for _, t := range plan.ToPC {
		ss.toPC[t.FP] = t
		ss.total += t.Size
	}
	ss.files = len(plan.ToPhone) + len(plan.ToPC)
	// The phone's newer playlists are applied at finish, once its songs are in.
	s.mu.Lock()
	s.sess[plan.Session] = ss
	s.mu.Unlock()
	s.note(Event{Kind: "plan", Device: d.Name, Total: ss.files, TotalBytes: ss.total,
		Message: fmt.Sprintf("%d to the phone · %d to the PC", len(plan.ToPhone), len(plan.ToPC))})
	writeJSON(w, plan)
}

func (s *Server) session(r *http.Request, d *Device) *session {
	s.mu.Lock()
	defer s.mu.Unlock()
	ss := s.sess[r.URL.Query().Get("s")]
	if ss == nil || ss.dev.ID != d.ID {
		return nil
	}
	return ss
}

func (s *Server) progress(ss *session, n int64, t Transfer, dir string) {
	s.mu.Lock()
	ss.bytes += n
	now := time.Now()
	s.seen = now
	send := now.Sub(ss.lastNote) > 200*time.Millisecond
	if send {
		ss.lastNote = now
	}
	done := len(ss.sent) + ss.received
	e := Event{Kind: "progress", Device: ss.dev.Name, Dir: dir, Title: t.Title, Artist: t.Artist, ArtID: t.ArtID,
		Done: done, Total: ss.files, Bytes: ss.bytes, TotalBytes: ss.total}
	s.mu.Unlock()
	if send {
		s.note(e)
	}
}

type counter struct {
	io.ReadSeeker
	f func(int64)
}

func (c *counter) Read(p []byte) (int, error) {
	n, err := c.ReadSeeker.Read(p)
	if n > 0 {
		c.f(int64(n))
	}
	return n, err
}

func (s *Server) download(w http.ResponseWriter, r *http.Request, d *Device) {
	ss := s.session(r, d)
	fp := r.PathValue("fp")
	if ss == nil {
		fail(w, http.StatusNotFound, "no such session")
		return
	}
	t, ok := ss.toPhone[fp]
	if !ok {
		fail(w, http.StatusNotFound, "not in this sync")
		return
	}
	var path string
	for _, tr := range s.cfg.Library.Tracks() {
		if tr.Fingerprint == fp {
			path = tr.Path
			break
		}
	}
	if path == "" {
		fail(w, http.StatusGone, "song is gone from the PC")
		return
	}
	if t.Convert {
		if conv, err := s.convert(path, fp); err == nil {
			path = conv
		}
	}
	f, err := os.Open(path)
	if err != nil {
		fail(w, http.StatusGone, err.Error())
		return
	}
	defer f.Close()
	st, _ := f.Stat()
	if r.Header.Get("Range") == "" {
		s.note(Event{Kind: "file", Device: d.Name, Dir: "toPhone", Title: t.Title, Artist: t.Artist, ArtID: t.ArtID})
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeContent(w, r, t.Name, st.ModTime(), &counter{f, func(n int64) { s.progress(ss, n, t, "toPhone") }})
	if pos, _ := f.Seek(0, io.SeekCurrent); pos >= st.Size() {
		s.mu.Lock()
		ss.sent[fp] = true
		s.mu.Unlock()
	}
}

// convert makes (once) a 16-bit FLAC copy for phones that asked for one.
func (s *Server) convert(path, fp string) (string, error) {
	dir := filepath.Join(s.cfg.Dir, "converted")
	os.MkdirAll(dir, 0o700)
	out := filepath.Join(dir, fp+".flac")
	if _, err := os.Stat(out); err == nil {
		return out, nil
	}
	tmp := out + ".tmp.flac"
	cmd := exec.Command(s.cfg.FFmpeg(), "-y", "-v", "error", "-i", path, "-map", "0", "-c:v", "copy", "-c:a", "flac",
		"-sample_fmt", "s16", "-af", "aresample=osf=s16:dither_method=triangular", tmp)
	hideWindow(cmd)
	if err := cmd.Run(); err != nil {
		os.Remove(tmp)
		return "", err
	}
	return out, os.Rename(tmp, out)
}

func (s *Server) partPath(fp string) string {
	return filepath.Join(s.cfg.Dir, "incoming", fp+".part")
}

func (s *Server) uploadOffset(w http.ResponseWriter, r *http.Request, d *Device) {
	var off int64
	if st, err := os.Stat(s.partPath(r.PathValue("fp"))); err == nil {
		off = st.Size()
	}
	writeJSON(w, map[string]int64{"offset": off})
}

func (s *Server) upload(w http.ResponseWriter, r *http.Request, d *Device) {
	ss := s.session(r, d)
	fp := r.PathValue("fp")
	if ss == nil {
		fail(w, http.StatusNotFound, "no such session")
		return
	}
	t, ok := ss.toPC[fp]
	if !ok {
		fail(w, http.StatusNotFound, "not in this sync")
		return
	}
	off, _ := strconv.ParseInt(r.URL.Query().Get("offset"), 10, 64)
	part := s.partPath(fp)
	os.MkdirAll(filepath.Dir(part), 0o700)
	var have int64
	if st, err := os.Stat(part); err == nil {
		have = st.Size()
	}
	if off != have {
		w.Header().Set("Upload-Offset", strconv.FormatInt(have, 10))
		fail(w, http.StatusConflict, "resume from "+strconv.FormatInt(have, 10))
		return
	}
	if off == 0 {
		s.note(Event{Kind: "file", Device: d.Name, Dir: "toPc", Title: t.Title, Artist: t.Artist})
	}
	f, err := os.OpenFile(part, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		fail(w, http.StatusInternalServerError, err.Error())
		return
	}
	_, err = io.Copy(f, &counterR{io.LimitReader(r.Body, t.Size-off+1), func(n int64) { s.progress(ss, n, t, "toPc") }})
	f.Close()
	if err != nil {
		fail(w, http.StatusBadRequest, "upload cut off")
		return
	}
	st, _ := os.Stat(part)
	if st.Size() < t.Size {
		writeJSON(w, map[string]int64{"offset": st.Size()})
		return
	}
	got, _ := Fingerprint(part)
	if st.Size() != t.Size || got != fp {
		os.Remove(part)
		fail(w, http.StatusUnprocessableEntity, "file arrived damaged; it will be sent again")
		return
	}
	dst, err := s.place(t)
	if err == nil {
		err = os.Rename(part, dst)
	}
	if err != nil {
		fail(w, http.StatusInternalServerError, err.Error())
		return
	}
	if tr, err := s.cfg.Library.AddFile(dst); err == nil {
		s.cfg.Library.SetFingerprint(tr.ID, fp)
	}
	s.mu.Lock()
	ss.received++
	s.mu.Unlock()
	s.note(Event{Kind: "received", Device: d.Name, Dir: "toPc", Title: t.Title, Artist: t.Artist})
	writeJSON(w, map[string]int64{"offset": st.Size()})
}

type counterR struct {
	r io.Reader
	f func(int64)
}

func (c *counterR) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	if n > 0 {
		c.f(int64(n))
	}
	return n, err
}

// place picks a free file name in the inbox for a song from the phone.
func (s *Server) place(t Transfer) (string, error) {
	dir := filepath.Join(s.cfg.Inbox, filepath.FromSlash(t.Folder))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	name := filepath.Base(strings.ReplaceAll(t.Name, "\\", "/"))
	if name == "." || name == "/" || name == "" {
		name = safe(t.Title, "song")
	}
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	dst := filepath.Join(dir, name)
	for i := 2; ; i++ {
		if _, err := os.Stat(dst); errors.Is(err, os.ErrNotExist) {
			return dst, nil
		}
		dst = filepath.Join(dir, fmt.Sprintf("%s (%d)%s", base, i, ext))
	}
}

func (s *Server) finish(w http.ResponseWriter, r *http.Request, d *Device) {
	var req FinishRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 16<<20)).Decode(&req); err != nil {
		fail(w, http.StatusBadRequest, "bad request")
		return
	}
	s.mu.Lock()
	ss := s.sess[req.Session]
	if ss == nil || ss.dev.ID != d.ID {
		s.mu.Unlock()
		fail(w, http.StatusNotFound, "no such session")
		return
	}
	delete(s.sess, req.Session)
	s.busy = ""
	s.mu.Unlock()

	lib := s.cfg.Library
	pc := s.pcTracks()
	byFP := map[string]PCTrack{}
	for _, t := range pc {
		byFP[t.FP] = t
	}
	// Plays: what each side played since the last sync, added up.
	s.st.mu.Lock()
	if d.Base == nil {
		d.Base = map[string]int{}
	}
	out := map[string]PlayCount{}
	for fp, ph := range req.Plays {
		t, ok := byFP[fp]
		if !ok {
			continue
		}
		merged := MergePlays(t.Plays, ph.Plays, d.Base[fp])
		last := max(t.LastPlay, ph.LastPlayed)
		d.Base[fp] = merged
		out[fp] = PlayCount{merged, last}
		if merged != t.Plays || last > t.LastPlay {
			lib.SetPlays(t.ID, merged, last/1000)
		}
	}
	for fp, t := range byFP { // songs the phone did not report: send the PC's count
		if _, ok := out[fp]; !ok && t.Plays > 0 {
			out[fp] = PlayCount{t.Plays, t.LastPlay}
			d.Base[fp] = t.Plays
		}
	}
	d.LastSync = time.Now().UnixMilli()
	s.st.save()
	s.st.mu.Unlock()

	s.applyPhonePlaylists(ss, byFP)
	s.note(Event{Kind: "done", Device: d.Name, Done: len(ss.sent) + ss.received, Total: ss.files, TotalBytes: ss.total,
		Message: fmt.Sprintf("%d to the phone · %d to the PC", len(ss.sent), ss.received)})
	writeJSON(w, FinishResponse{Plays: out, Received: ss.received, Sent: len(ss.sent)})
}

// applyPhonePlaylists stores the phone's playlists that won the merge.
func (s *Server) applyPhonePlaylists(ss *session, byFP map[string]PCTrack) {
	lib := s.cfg.Library
	byID := map[string]string{}
	for fp, t := range byFP {
		byID[t.ID] = fp
	}
	_, forPC := MergePlaylists(s.pcPlaylists(byID), ss.phoneLists)
	have := map[string]library.Playlist{}
	for _, p := range lib.AllPlaylists() {
		have[key(p.Name)] = p
	}
	for _, l := range forPC {
		ids := []string{}
		for _, fp := range l.Items {
			if t, ok := byFP[fp]; ok {
				ids = append(ids, t.ID)
			}
		}
		if len(ids) == 0 {
			continue
		}
		p, ok := have[key(l.Name)]
		if !ok {
			lib.CreatePlaylist(l.Name, ids)
			continue
		}
		p.Tracks = ids
		p.Modified = l.Modified / 1000
		lib.PutPlaylist(p)
	}
}

// Name is the PC name phones see.
func (s *Server) Name() string { return s.cfg.Name }
