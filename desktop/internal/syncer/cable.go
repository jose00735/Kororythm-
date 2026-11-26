package syncer

import (
	"bufio"
	"context"
	"fmt"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// CablePhone is a phone on a USB cable, seen by adb.
type CablePhone struct {
	Serial string `json:"serial"`
	Model  string `json:"model,omitempty"`
	// State: "device" (ready), "unauthorized" (accept the prompt on the phone), "offline".
	State  string `json:"state"`
	Linked bool   `json:"linked"` // adb reverse is set: the phone reaches this PC at 127.0.0.1
}

// CableState is what the Sync window shows about the cable.
type CableState struct {
	On         bool         `json:"on"`
	Installing bool         `json:"installing,omitempty"`
	HaveADB    bool         `json:"haveAdb"`
	Phones     []CablePhone `json:"phones"`
	Error      string       `json:"error,omitempty"`
}

// Cable keeps `adb reverse tcp:47800 tcp:47800` set on every phone plugged in by USB
// (with USB debugging on), so the phone reaches the sync server through the cable even
// without Wi-Fi. USB tethering needs none of this: the phone then finds the PC by broadcast.
type Cable struct {
	ADB     func() string // path of adb, or ""
	Install func() error  // fetches adb
	Notify  func(CableState)
	Port    int

	mu     sync.Mutex
	st     CableState
	cancel context.CancelFunc
}

// State returns the current cable state.
func (c *Cable) State() CableState {
	c.mu.Lock()
	defer c.mu.Unlock()
	st := c.st
	st.HaveADB = c.ADB() != ""
	if st.Phones == nil {
		st.Phones = []CablePhone{}
	}
	return st
}

func (c *Cable) set(f func(*CableState)) {
	c.mu.Lock()
	f(&c.st)
	c.mu.Unlock()
	if c.Notify != nil {
		c.Notify(c.State())
	}
}

// Enable starts (or stops) watching for phones on USB, installing adb first if needed.
func (c *Cable) Enable(on bool) {
	c.mu.Lock()
	if c.cancel != nil {
		c.cancel()
		c.cancel = nil
	}
	c.mu.Unlock()
	if !on {
		c.set(func(s *CableState) { s.On, s.Phones, s.Error = false, nil, "" })
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	c.mu.Lock()
	c.cancel = cancel
	c.mu.Unlock()
	c.set(func(s *CableState) { s.On, s.Error = true, "" })
	go func() {
		if c.ADB() == "" {
			c.set(func(s *CableState) { s.Installing = true })
			err := c.Install()
			c.set(func(s *CableState) {
				s.Installing = false
				if err != nil {
					s.Error, s.On = err.Error(), false
				}
			})
			if err != nil {
				cancel()
				return
			}
		}
		for {
			c.poll(ctx)
			select {
			case <-ctx.Done():
				return
			case <-time.After(3 * time.Second):
			}
		}
	}()
}

func (c *Cable) run(ctx context.Context, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, c.ADB(), args...)
	hideWindow(cmd)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

// ParseDevices reads `adb devices -l`.
func ParseDevices(out string) []CablePhone {
	var list []CablePhone
	sc := bufio.NewScanner(strings.NewReader(out))
	for sc.Scan() {
		f := strings.Fields(sc.Text())
		if len(f) < 2 || f[0] == "List" || strings.HasPrefix(f[0], "*") {
			continue
		}
		p := CablePhone{Serial: f[0], State: f[1]}
		for _, kv := range f[2:] {
			if m, ok := strings.CutPrefix(kv, "model:"); ok {
				p.Model = strings.ReplaceAll(m, "_", " ")
			}
		}
		list = append(list, p)
	}
	return list
}

func (c *Cable) poll(ctx context.Context) {
	out, err := c.run(ctx, "devices", "-l")
	if err != nil {
		c.set(func(s *CableState) { s.Error = "adb: " + strings.TrimSpace(out+" "+err.Error()) })
		return
	}
	phones := ParseDevices(out)
	rule := fmt.Sprintf("tcp:%d", c.Port)
	for i, p := range phones {
		if p.State != "device" {
			continue
		}
		list, _ := c.run(ctx, "-s", p.Serial, "reverse", "--list")
		if !strings.Contains(list, rule) {
			if _, err := c.run(ctx, "-s", p.Serial, "reverse", rule, rule); err != nil {
				continue
			}
		}
		phones[i].Linked = true
	}
	c.set(func(s *CableState) { s.Phones, s.Error = phones, "" })
}
