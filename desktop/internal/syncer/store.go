package syncer

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Device is a paired phone.
type Device struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Token    string `json:"token"`
	Paired   int64  `json:"paired"`
	LastSync int64  `json:"lastSync,omitempty"`
	// Base: each song's play count when this phone last synced.
	Base map[string]int `json:"base,omitempty"`
}

// DeviceInfo is a Device without its secrets, for the UI.
type DeviceInfo struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Paired   int64  `json:"paired"`
	LastSync int64  `json:"lastSync,omitempty"`
}

type store struct {
	mu      sync.Mutex
	file    string
	Devices map[string]*Device `json:"devices"`
}

func openStore(dir string) *store {
	s := &store{file: filepath.Join(dir, "sync.json"), Devices: map[string]*Device{}}
	if b, err := os.ReadFile(s.file); err == nil {
		json.Unmarshal(b, s)
		if s.Devices == nil {
			s.Devices = map[string]*Device{}
		}
	}
	return s
}

func (s *store) save() error {
	b, _ := json.MarshalIndent(s, "", " ")
	tmp := s.file + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.file)
}

func (s *store) byToken(tok string) *Device {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, d := range s.Devices {
		if tok != "" && d.Token == tok {
			return d
		}
	}
	return nil
}

func (s *store) list() []DeviceInfo {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := []DeviceInfo{}
	for _, d := range s.Devices {
		out = append(out, DeviceInfo{d.ID, d.Name, d.Paired, d.LastSync})
	}
	return out
}

func randHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// loadCert reads the PC's certificate or makes one (ECDSA P-256, self-signed, 20 years).
func loadCert(dir string) (tls.Certificate, string, error) {
	cf, kf := filepath.Join(dir, "cert.pem"), filepath.Join(dir, "key.pem")
	if c, err := tls.LoadX509KeyPair(cf, kf); err == nil {
		return c, certFP(c.Certificate[0]), nil
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 62))
	host, _ := os.Hostname()
	tpl := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "Kororythm " + host},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().AddDate(20, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	kd, _ := x509.MarshalECPrivateKey(key)
	os.MkdirAll(dir, 0o700)
	os.WriteFile(cf, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600)
	os.WriteFile(kf, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kd}), 0o600)
	c, err := tls.LoadX509KeyPair(cf, kf)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	return c, certFP(der), nil
}

func certFP(der []byte) string {
	h := sha256.Sum256(der)
	return hex.EncodeToString(h[:])
}
