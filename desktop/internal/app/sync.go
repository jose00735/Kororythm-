package app

import (
	"net"
	"time"

	"github.com/jose00735/kororythm/desktop/internal/syncer"
)

// SyncState is what the Sync window shows.
type SyncState struct {
	Available bool                `json:"available"`
	Running   bool                `json:"running"`
	Name      string              `json:"name"`
	Key       string              `json:"key"` // short certificate fingerprint, to compare with the phone
	Addrs     []string            `json:"addrs"`
	Port      int                 `json:"port"`
	Code      string              `json:"code,omitempty"`
	CodeLeft  int                 `json:"codeLeft,omitempty"` // seconds
	Devices   []syncer.DeviceInfo `json:"devices"`
	Log       []syncer.Event      `json:"log"`
	Cable     syncer.CableState   `json:"cable"`
}

func (a *App) syncState() SyncState {
	st := SyncState{Devices: []syncer.DeviceInfo{}, Log: []syncer.Event{}, Addrs: localAddrs(), Port: syncer.Port}
	if a.Sync == nil {
		return st
	}
	a.syncMu.Lock()
	st.Running = a.syncOn
	st.Log = append(st.Log, a.syncLog...)
	a.syncMu.Unlock()
	st.Available = true
	if a.Cable != nil {
		st.Cable = a.Cable.State()
	}
	st.Key = a.Sync.Short()
	st.Name = a.Sync.Name()
	st.Devices = a.Sync.Devices()
	if code, exp := a.Sync.Code(); code != "" {
		st.Code, st.CodeLeft = code, int(time.Until(exp).Seconds())
	}
	return st
}

// localAddrs lists this PC's private IPv4 addresses (typed on the phone if discovery fails).
func localAddrs() []string {
	out := []string{}
	ifs, _ := net.Interfaces()
	for _, i := range ifs {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := i.Addrs()
		for _, a := range addrs {
			if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil && n.IP.IsPrivate() {
				out = append(out, n.IP.String())
			}
		}
	}
	return out
}
