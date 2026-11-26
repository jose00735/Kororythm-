package syncer

// Wire types of sync protocol v1. Times are Unix milliseconds. See protocol/SYNC.md.

const (
	// Version of the protocol spoken by this build.
	Version = 1
	// Port is the HTTPS port the PC listens on; DiscoveryPort the UDP port it answers on.
	Port          = 47800
	DiscoveryPort = 47801
	// DiscoverProbe is what a phone broadcasts to find PCs.
	DiscoverProbe = "KORORYTHM-DISCOVER 1"
)

// Hello describes a PC (discovery reply and GET /v1/hello).
type Hello struct {
	App  string `json:"app"`
	V    int    `json:"v"`
	Name string `json:"name"`
	Port int    `json:"port"`
	// FP is the SHA-256 (hex) of the PC's certificate: the phone pins it.
	FP string `json:"fp"`
}

// PairRequest proves the phone knows the code shown on the PC without sending it.
type PairRequest struct {
	Device string `json:"device"` // stable id the phone made up
	Name   string `json:"name"`   // "Pixel 8"
	Nonce  string `json:"nonce"`
	Proof  string `json:"proof"` // hex HMAC-SHA256(code, "kororythm-pair|"+nonce+"|"+certFP)
}

// PairResponse gives the phone its token, and proves the PC knows the code too.
type PairResponse struct {
	Token string `json:"token"`
	Name  string `json:"name"`
	Proof string `json:"proof"` // hex HMAC-SHA256(code, "kororythm-server|"+nonce+"|"+certFP)
}

// ManifestTrack is one song as a side has it.
type ManifestTrack struct {
	FP         string `json:"fp"`
	Name       string `json:"name"` // file name
	Size       int64  `json:"size"`
	Title      string `json:"title"`
	Artist     string `json:"artist,omitempty"`
	Album      string `json:"album,omitempty"`
	DurationMs int64  `json:"durationMs,omitempty"`
	Plays      int    `json:"plays,omitempty"`
	LastPlayed int64  `json:"lastPlayed,omitempty"`
}

// ManifestPlaylist is a playlist by name, its songs by fingerprint.
type ManifestPlaylist struct {
	Name     string   `json:"name"`
	Items    []string `json:"items"`
	Modified int64    `json:"modified"`
}

// Options say what this sync should move.
type Options struct {
	// NoDownload: don't copy the PC's songs to the phone. NoUpload: the other way.
	NoDownload bool `json:"noDownload,omitempty"`
	NoUpload   bool `json:"noUpload,omitempty"`
	// Only: copy to the phone just these PC playlists' songs (empty = everything).
	Only []string `json:"only,omitempty"`
	// Budget: stop adding songs for the phone past this many bytes (0 = no limit).
	Budget int64 `json:"budget,omitempty"`
	// MaxBits: songs above this bit depth come to the phone converted to 16-bit FLAC (0 = as is).
	MaxBits int `json:"maxBits,omitempty"`
}

// StartRequest is the phone's library plus what it wants.
type StartRequest struct {
	Device    string             `json:"device"`
	Tracks    []ManifestTrack    `json:"tracks"`
	Playlists []ManifestPlaylist `json:"playlists"`
	Options   Options            `json:"options"`
}

// Transfer is a file to move.
type Transfer struct {
	FP     string `json:"fp"`
	Name   string `json:"name"`
	Folder string `json:"folder,omitempty"` // "Artist/Album", where the phone should keep it
	Size   int64  `json:"size"`
	Title  string `json:"title"`
	Artist string `json:"artist,omitempty"`
	Album  string `json:"album,omitempty"`
	ArtID  string `json:"artId,omitempty"` // GET /v1/art/{artId} for the flying cover
	// Convert: the PC sends a 16-bit copy (size is then only an estimate).
	Convert bool `json:"convert,omitempty"`
}

// Plan is what the PC answers to a start: the files each way and the playlists to apply.
type Plan struct {
	Session string     `json:"session"`
	PCName  string     `json:"pcName"`
	ToPhone []Transfer `json:"toPhone"`
	ToPC    []Transfer `json:"toPc"`
	// Playlists the phone should create or replace once the files are in.
	Playlists []ManifestPlaylist `json:"playlists"`
	// Skipped: songs left out because of the budget.
	Skipped int `json:"skipped,omitempty"`
}

// PlayCount is one song's plays.
type PlayCount struct {
	Plays      int   `json:"plays"`
	LastPlayed int64 `json:"lastPlayed,omitempty"`
}

// FinishRequest closes a session with the phone's play counts.
type FinishRequest struct {
	Session string               `json:"session"`
	Plays   map[string]PlayCount `json:"plays"`
}

// FinishResponse has the merged counts the phone should store.
type FinishResponse struct {
	Plays    map[string]PlayCount `json:"plays"`
	Received int                  `json:"received"`
	Sent     int                  `json:"sent"`
}
