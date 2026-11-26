// Package settings keeps Kororythm's preferences and window places between runs,
// in %APPDATA%\Kororythm\settings.json.
package settings

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/jose00735/kororythm/desktop/internal/dsp"
)

// Win is one window's place and state.
type Win struct {
	X      int  `json:"x"`
	Y      int  `json:"y"`
	W      int  `json:"w"`
	H      int  `json:"h"`
	Open   bool `json:"open"`
	Shaded bool `json:"shaded,omitempty"`
}

// Data is everything saved.
type Data struct {
	Volume     float64            `json:"volume"`
	Eq         dsp.EqSettings     `json:"eq"`
	Skin       string             `json:"skin"`
	Layout     string             `json:"layout"`
	Crossfade  int                `json:"crossfadeMs"`
	AlwaysTop  bool               `json:"alwaysOnTop"`
	Windows    map[string]Win     `json:"windows"`
	Queue      json.RawMessage    `json:"queue,omitempty"`
	ResumeMs   int64              `json:"resumeMs,omitempty"`
}

// Store loads and saves Data.
type Store struct {
	mu   sync.Mutex
	file string
	d    Data
	t    *time.Timer
}

// Dir is where Kororythm keeps its files.
func Dir() string {
	base, err := os.UserConfigDir()
	if err != nil {
		base = os.TempDir()
	}
	return filepath.Join(base, "Kororythm")
}

// Open reads settings from file, filling defaults.
func Open(file string) *Store {
	s := &Store{file: file, d: Data{Volume: 0.8, Skin: "neon", Layout: "classic", Eq: dsp.EqSettings{Enabled: true, Preset: "Flat"}}}
	if b, err := os.ReadFile(file); err == nil {
		_ = json.Unmarshal(b, &s.d)
	}
	if s.d.Windows == nil {
		s.d.Windows = map[string]Win{}
	}
	return s
}

// Get returns a copy of the settings.
func (s *Store) Get() Data {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.d
	d.Windows = make(map[string]Win, len(s.d.Windows))
	for k, v := range s.d.Windows {
		d.Windows[k] = v
	}
	return d
}

// Update changes settings and saves them shortly after.
func (s *Store) Update(f func(*Data)) {
	s.mu.Lock()
	f(&s.d)
	if s.t != nil {
		s.t.Stop()
	}
	s.t = time.AfterFunc(700*time.Millisecond, func() { _ = s.Save() })
	s.mu.Unlock()
}

// Save writes the file now.
func (s *Store) Save() error {
	s.mu.Lock()
	b, err := json.MarshalIndent(s.d, "", "  ")
	s.mu.Unlock()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(s.file), 0o755); err != nil {
		return err
	}
	tmp := s.file + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, s.file)
}
