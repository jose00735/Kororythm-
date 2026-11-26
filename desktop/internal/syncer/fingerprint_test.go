package syncer

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// The shared fixtures: the Android client must compute the same values (FingerprintTest.kt).
func TestFingerprintFixtures(t *testing.T) {
	dir := filepath.Join("..", "..", "..", "protocol", "fixtures")
	raw, err := os.ReadFile(filepath.Join(dir, "fingerprints.json"))
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]string{}
	if err := json.Unmarshal(raw, &want); err != nil {
		t.Fatal(err)
	}
	for name, fp := range want {
		got, err := Fingerprint(filepath.Join(dir, name))
		if err != nil {
			t.Fatal(name, err)
		}
		if got != fp {
			t.Errorf("%s: got %s want %s", name, got, fp)
		}
	}
	if want["t.mp3"] != want["t-retagged.mp3"] {
		t.Error("retagging an MP3 must not change its fingerprint")
	}
}
