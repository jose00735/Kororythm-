// Package downloads saves audio from links (YouTube and the other sites yt-dlp
// supports) as tagged MP3s in the music library, like the Android Downloads
// screen. yt-dlp and ffmpeg are fetched from their GitHub releases the first
// time they are needed, so the installer stays small.
package downloads

import (
	"archive/zip"
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Format is what a download is saved as.
type Format string

const (
	MP3V0  Format = "mp3v0"  // ~245 kbps VBR, the Android default
	MP3320 Format = "mp3320" // 320 kbps CBR
)

// Entry is one video of a link (a playlist has many).
type Entry struct {
	ID       string `json:"id"`
	URL      string `json:"url"`
	Title    string `json:"title"`
	Uploader string `json:"uploader,omitempty"`
	Duration int    `json:"duration,omitempty"`
}

// Probe is what a link turned out to be.
type Probe struct {
	URL      string  `json:"url"`
	Title    string  `json:"title"`
	Playlist bool    `json:"playlist"`
	Entries  []Entry `json:"entries"`
}

// Job is one song being downloaded.
type Job struct {
	ID       string  `json:"id"`
	Title    string  `json:"title"`
	Status   string  `json:"status"` // queued, downloading, converting, done, failed, stopped
	Progress float64 `json:"progress"`
	Error    string  `json:"error,omitempty"`
}

// Tools reports whether yt-dlp and ffmpeg are ready.
type Tools struct {
	YtDlp      bool    `json:"ytdlp"`
	Ffmpeg     bool    `json:"ffmpeg"`
	Installing bool    `json:"installing"`
	Step       string  `json:"step,omitempty"`
	Progress   float64 `json:"progress"`
	Error      string  `json:"error,omitempty"`
}

// State is everything the Downloads window shows.
type State struct {
	Tools   Tools  `json:"tools"`
	Probing bool   `json:"probing"`
	Probe   *Probe `json:"probe,omitempty"`
	Error   string `json:"error,omitempty"`
	Jobs    []Job  `json:"jobs"`
	Running bool   `json:"running"`
	Folder  string `json:"folder"`
	Format  Format `json:"format"`
}

// Manager runs probes and downloads one at a time.
type Manager struct {
	mu       sync.Mutex
	toolsDir string
	outDir   string
	st       State
	cancel   context.CancelFunc
	onChange func(State)
	onLog    func(string)
	onDone   func(path string)
}

// New makes a manager keeping tools in toolsDir and saving songs in outDir.
func New(toolsDir, outDir string) *Manager {
	m := &Manager{toolsDir: toolsDir, outDir: outDir}
	m.st.Folder = outDir
	m.st.Format = MP3V0
	m.st.Jobs = []Job{}
	m.refreshTools()
	return m
}

// OnChange, OnLog and OnDone register callbacks (state, one log line, a finished file).
func (m *Manager) OnChange(f func(State)) { m.mu.Lock(); m.onChange = f; m.mu.Unlock() }
func (m *Manager) OnLog(f func(string))   { m.mu.Lock(); m.onLog = f; m.mu.Unlock() }
func (m *Manager) OnDone(f func(string))  { m.mu.Lock(); m.onDone = f; m.mu.Unlock() }

// State returns a copy of the current state.
func (m *Manager) State() State {
	m.mu.Lock()
	defer m.mu.Unlock()
	s := m.st
	s.Jobs = append([]Job{}, m.st.Jobs...)
	return s
}

func (m *Manager) update(f func(*State)) {
	m.mu.Lock()
	f(&m.st)
	cb := m.onChange
	s := m.st
	s.Jobs = append([]Job{}, m.st.Jobs...)
	m.mu.Unlock()
	if cb != nil {
		cb(s)
	}
}

func (m *Manager) log(format string, a ...any) {
	m.mu.Lock()
	cb := m.onLog
	m.mu.Unlock()
	if cb != nil {
		cb(fmt.Sprintf(format, a...))
	}
}

// ---- tools ----

func exe(name string) string {
	if runtime.GOOS == "windows" {
		return name + ".exe"
	}
	return name
}

// toolPath finds a tool in the tools folder, then on PATH.
func (m *Manager) toolPath(name string) string {
	p := filepath.Join(m.toolsDir, exe(name))
	if st, err := os.Stat(p); err == nil && st.Size() > 0 {
		return p
	}
	if p, err := exec.LookPath(name); err == nil {
		return p
	}
	return ""
}

func (m *Manager) refreshTools() {
	m.st.Tools.YtDlp = m.toolPath("yt-dlp") != ""
	m.st.Tools.Ffmpeg = m.toolPath("ffmpeg") != ""
}

const (
	ytdlpURL  = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
	ffmpegURL = "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"
)

// InstallTools downloads whatever is missing. It runs in the background.
func (m *Manager) InstallTools() {
	m.mu.Lock()
	if m.st.Tools.Installing {
		m.mu.Unlock()
		return
	}
	m.st.Tools.Installing = true
	m.st.Tools.Error = ""
	m.mu.Unlock()
	go func() {
		err := m.installTools()
		m.update(func(s *State) {
			s.Tools.Installing = false
			s.Tools.Step = ""
			if err != nil {
				s.Tools.Error = err.Error()
			}
			m.refreshTools()
		})
	}()
}

func (m *Manager) installTools() error {
	if runtime.GOOS != "windows" {
		return errors.New("install yt-dlp and ffmpeg with your package manager")
	}
	if err := os.MkdirAll(m.toolsDir, 0o755); err != nil {
		return err
	}
	if m.toolPath("yt-dlp") == "" {
		m.log("Downloading yt-dlp from %s", ytdlpURL)
		if err := m.fetch(ytdlpURL, filepath.Join(m.toolsDir, "yt-dlp.exe"), "yt-dlp"); err != nil {
			return fmt.Errorf("yt-dlp: %w", err)
		}
	}
	if m.toolPath("ffmpeg") == "" {
		m.log("Downloading ffmpeg from %s", ffmpegURL)
		zipPath := filepath.Join(m.toolsDir, "ffmpeg.zip")
		if err := m.fetch(ffmpegURL, zipPath, "ffmpeg"); err != nil {
			return fmt.Errorf("ffmpeg: %w", err)
		}
		defer os.Remove(zipPath)
		if err := extractBins(zipPath, m.toolsDir, "ffmpeg.exe", "ffprobe.exe"); err != nil {
			return fmt.Errorf("ffmpeg: %w", err)
		}
	}
	m.log("Tools ready in %s", m.toolsDir)
	return nil
}

type progressWriter struct {
	m          *Manager
	step       string
	done, size int64
	last       time.Time
}

func (p *progressWriter) Write(b []byte) (int, error) {
	p.done += int64(len(b))
	if time.Since(p.last) > 150*time.Millisecond {
		p.last = time.Now()
		frac := 0.0
		if p.size > 0 {
			frac = float64(p.done) / float64(p.size)
		}
		p.m.update(func(s *State) { s.Tools.Step = p.step; s.Tools.Progress = frac })
	}
	return len(b), nil
}

func (m *Manager) fetch(url, dst, step string) error {
	resp, err := http.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("download failed: %s", resp.Status)
	}
	tmp := dst + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	pw := &progressWriter{m: m, step: step, size: resp.ContentLength}
	if _, err := io.Copy(io.MultiWriter(f, pw), resp.Body); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	f.Close()
	return os.Rename(tmp, dst)
}

func extractBins(zipPath, dir string, names ...string) error {
	z, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer z.Close()
	want := map[string]bool{}
	for _, n := range names {
		want[n] = true
	}
	found := 0
	for _, f := range z.File {
		base := filepath.Base(f.Name)
		if !want[base] {
			continue
		}
		r, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.Create(filepath.Join(dir, base))
		if err != nil {
			r.Close()
			return err
		}
		_, err = io.Copy(out, r)
		r.Close()
		out.Close()
		if err != nil {
			return err
		}
		found++
	}
	if found == 0 {
		return errors.New("ffmpeg.exe not found in the archive")
	}
	return nil
}

// ---- probing ----

// command builds a tool command that does not flash a console window on Windows.
var command = func(ctx context.Context, name string, args ...string) *exec.Cmd {
	return exec.CommandContext(ctx, name, args...)
}

// ProbeURL asks yt-dlp what a link is (one video or a playlist). It can be stopped with Stop.
func (m *Manager) ProbeURL(url string) {
	url = strings.TrimSpace(url)
	yt := m.toolPath("yt-dlp")
	if yt == "" {
		m.update(func(s *State) { s.Error = "yt-dlp is not installed yet" })
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	m.mu.Lock()
	if m.cancel != nil {
		m.cancel()
	}
	m.cancel = cancel
	m.mu.Unlock()
	m.update(func(s *State) { s.Probing = true; s.Probe = nil; s.Error = "" })
	go func() {
		defer cancel()
		args := []string{"-J", "--flat-playlist", "--playlist-end", "200", "--no-warnings", url}
		if !regexp.MustCompile(`[?&]list=`).MatchString(url) {
			args = append([]string{"--no-playlist"}, args...)
		}
		m.log("$ yt-dlp %s", strings.Join(args, " "))
		cmd := command(ctx, yt, args...)
		var stderr strings.Builder
		cmd.Stderr = &stderr
		out, err := cmd.Output()
		if ctx.Err() != nil {
			m.update(func(s *State) { s.Probing = false; s.Error = "Stopped" })
			return
		}
		if err != nil {
			msg := lastLine(stderr.String())
			m.log("%s", stderr.String())
			m.update(func(s *State) { s.Probing = false; s.Error = "Couldn't read that link: " + msg })
			return
		}
		p, err := parseProbe(out, url)
		if err != nil {
			m.update(func(s *State) { s.Probing = false; s.Error = err.Error() })
			return
		}
		m.log("Found %d item(s): %s", len(p.Entries), p.Title)
		m.update(func(s *State) { s.Probing = false; s.Probe = p })
	}()
}

func lastLine(s string) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	return strings.TrimPrefix(lines[len(lines)-1], "ERROR: ")
}

func parseProbe(b []byte, url string) (*Probe, error) {
	var o struct {
		Type       string  `json:"_type"`
		ID         string  `json:"id"`
		Title      string  `json:"title"`
		Uploader   string  `json:"uploader"`
		Channel    string  `json:"channel"`
		Duration   float64 `json:"duration"`
		WebpageURL string  `json:"webpage_url"`
		Entries    []struct {
			ID       string  `json:"id"`
			URL      string  `json:"url"`
			Title    string  `json:"title"`
			Uploader string  `json:"uploader"`
			Channel  string  `json:"channel"`
			Duration float64 `json:"duration"`
		} `json:"entries"`
	}
	if err := json.Unmarshal(b, &o); err != nil {
		return nil, fmt.Errorf("yt-dlp answered something unexpected: %w", err)
	}
	p := &Probe{URL: url, Title: o.Title}
	if o.Type == "playlist" {
		p.Playlist = true
		for _, e := range o.Entries {
			u := e.URL
			if u == "" || !strings.HasPrefix(u, "http") {
				u = "https://www.youtube.com/watch?v=" + e.ID
			}
			p.Entries = append(p.Entries, Entry{ID: e.ID, URL: u, Title: e.Title, Uploader: first(e.Uploader, e.Channel), Duration: int(e.Duration)})
		}
		return p, nil
	}
	u := o.WebpageURL
	if u == "" {
		u = url
	}
	p.Entries = []Entry{{ID: o.ID, URL: u, Title: o.Title, Uploader: first(o.Uploader, o.Channel), Duration: int(o.Duration)}}
	return p, nil
}

func first(a ...string) string {
	for _, s := range a {
		if s != "" {
			return s
		}
	}
	return ""
}

// ---- downloading ----

var progressRe = regexp.MustCompile(`\[download\]\s+([\d.]+)%`)

// Args returns yt-dlp's arguments for saving one entry as tagged MP3 in dir.
func Args(e Entry, f Format, dir, ffmpegDir string) []string {
	q := "0"
	if f == MP3320 {
		q = "320K"
	}
	args := []string{
		"--no-playlist", "--newline", "--no-warnings",
		"-f", "bestaudio/best",
		"-x", "--audio-format", "mp3", "--audio-quality", q,
		"--embed-metadata", "--embed-thumbnail", "--convert-thumbnails", "jpg",
		// "Artist - Title" video names become proper artist and title tags.
		"--parse-metadata", "title:%(artist)s - %(title)s",
		"--parse-metadata", "%(artist,uploader,channel)s:%(meta_artist)s",
		"-o", filepath.Join(dir, "%(artist,uploader,channel)s - %(title)s.%(ext)s"),
		"--print", "after_move:filepath",
	}
	if ffmpegDir != "" {
		args = append(args, "--ffmpeg-location", ffmpegDir)
	}
	return append(args, e.URL)
}

// Start downloads the chosen entries of the current probe, one after another.
func (m *Manager) Start(ids []string, f Format) error {
	m.mu.Lock()
	p := m.st.Probe
	running := m.st.Running
	m.mu.Unlock()
	if running {
		return errors.New("downloads are already running")
	}
	if p == nil {
		return errors.New("fetch a link first")
	}
	want := map[string]bool{}
	for _, id := range ids {
		want[id] = true
	}
	var todo []Entry
	for _, e := range p.Entries {
		if len(ids) == 0 || want[e.ID] {
			todo = append(todo, e)
		}
	}
	if len(todo) == 0 {
		return errors.New("choose at least one song")
	}
	yt := m.toolPath("yt-dlp")
	ff := m.toolPath("ffmpeg")
	if yt == "" || ff == "" {
		return errors.New("yt-dlp and ffmpeg are needed first")
	}
	if err := os.MkdirAll(m.outDir, 0o755); err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(context.Background())
	m.mu.Lock()
	m.cancel = cancel
	m.mu.Unlock()
	m.update(func(s *State) {
		s.Running = true
		s.Format = f
		for _, e := range todo {
			s.Jobs = append(s.Jobs, Job{ID: e.ID, Title: e.Title, Status: "queued"})
		}
	})
	go func() {
		defer cancel()
		for _, e := range todo {
			if ctx.Err() != nil {
				m.setJob(e.ID, func(j *Job) { j.Status = "stopped" })
				continue
			}
			m.run(ctx, yt, filepath.Dir(ff), e, f)
		}
		m.update(func(s *State) { s.Running = false })
	}()
	return nil
}

func (m *Manager) setJob(id string, f func(*Job)) {
	m.update(func(s *State) {
		for i := len(s.Jobs) - 1; i >= 0; i-- {
			if s.Jobs[i].ID == id {
				f(&s.Jobs[i])
				return
			}
		}
	})
}

func (m *Manager) run(ctx context.Context, yt, ffDir string, e Entry, f Format) {
	args := Args(e, f, m.outDir, ffDir)
	m.log("$ yt-dlp %s", strings.Join(args, " "))
	m.setJob(e.ID, func(j *Job) { j.Status = "downloading" })
	cmd := command(ctx, yt, args...)
	stdout, _ := cmd.StdoutPipe()
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		m.setJob(e.ID, func(j *Job) { j.Status = "failed"; j.Error = err.Error() })
		return
	}
	var saved, lastErr string
	sc := bufio.NewScanner(stdout)
	for sc.Scan() {
		line := sc.Text()
		m.log("%s", line)
		if mm := progressRe.FindStringSubmatch(line); mm != nil {
			v, _ := strconv.ParseFloat(mm[1], 64)
			m.setJob(e.ID, func(j *Job) { j.Progress = v / 100 })
		}
		if strings.HasPrefix(line, "[ExtractAudio]") || strings.HasPrefix(line, "[EmbedThumbnail]") {
			m.setJob(e.ID, func(j *Job) { j.Status = "converting"; j.Progress = 1 })
		}
		if strings.HasPrefix(line, "ERROR:") {
			lastErr = strings.TrimPrefix(line, "ERROR: ")
		}
		if strings.HasSuffix(strings.ToLower(line), ".mp3") && !strings.HasPrefix(line, "[") {
			saved = line
		}
	}
	err := cmd.Wait()
	switch {
	case ctx.Err() != nil:
		m.setJob(e.ID, func(j *Job) { j.Status = "stopped" })
	case err != nil:
		if lastErr == "" {
			lastErr = err.Error()
		}
		m.setJob(e.ID, func(j *Job) { j.Status = "failed"; j.Error = lastErr })
	default:
		m.setJob(e.ID, func(j *Job) { j.Status = "done"; j.Progress = 1 })
		m.mu.Lock()
		cb := m.onDone
		m.mu.Unlock()
		if cb != nil && saved != "" {
			cb(saved)
		}
	}
}

// Stop cancels the probe or the downloads in progress.
func (m *Manager) Stop() {
	m.mu.Lock()
	if m.cancel != nil {
		m.cancel()
	}
	m.mu.Unlock()
}

// ClearFinished removes finished, failed and stopped jobs from the list.
func (m *Manager) ClearFinished() {
	m.update(func(s *State) {
		keep := s.Jobs[:0]
		for _, j := range s.Jobs {
			if j.Status == "queued" || j.Status == "downloading" || j.Status == "converting" {
				keep = append(keep, j)
			}
		}
		s.Jobs = keep
	})
}

// FFmpegPath is the installed ffmpeg ("" when it is not installed yet).
func (m *Manager) FFmpegPath() string { return m.toolPath("ffmpeg") }

// adbURL is Google's platform-tools for Windows (adb), used for syncing over a USB cable.
const adbURL = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"

// ADBPath is the installed adb ("" when it is not there).
func (m *Manager) ADBPath() string { return m.toolPath("adb") }

// InstallADB fetches adb from Google's platform-tools (about 7 MB), once.
func (m *Manager) InstallADB() error {
	if m.ADBPath() != "" {
		return nil
	}
	if runtime.GOOS != "windows" {
		return errors.New("install adb (Android platform-tools) with your package manager")
	}
	if err := os.MkdirAll(m.toolsDir, 0o755); err != nil {
		return err
	}
	m.log("Downloading adb from %s", adbURL)
	zipPath := filepath.Join(m.toolsDir, "platform-tools.zip")
	if err := m.fetch(adbURL, zipPath, "adb"); err != nil {
		return fmt.Errorf("adb: %w", err)
	}
	defer os.Remove(zipPath)
	return extractBins(zipPath, m.toolsDir, "adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll")
}
