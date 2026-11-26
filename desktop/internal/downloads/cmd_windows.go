//go:build windows

package downloads

import (
	"context"
	"os/exec"
	"syscall"
)

func init() {
	// Run yt-dlp and ffmpeg without flashing a console window.
	command = func(ctx context.Context, name string, args ...string) *exec.Cmd {
		c := exec.CommandContext(ctx, name, args...)
		c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
		return c
	}
}
