//go:build windows

package main

import (
	"golang.org/x/sys/windows"

	"github.com/wailsapp/wails/v3/pkg/application"
)

var (
	user32             = windows.NewLazySystemDLL("user32.dll")
	gdi32              = windows.NewLazySystemDLL("gdi32.dll")
	procSetWindowRgn   = user32.NewProc("SetWindowRgn")
	procGetDpiForWin   = user32.NewProc("GetDpiForWindow")
	procCreateRoundRgn = gdi32.NewProc("CreateRoundRectRgn")
)

// shape is how a window's outline is cut: an inset rectangle with round corners,
// matching what the page draws (the iPod body, the phone, the Prism frame).
type shape struct{ left, top, right, bottom, radius int }

var shapes = map[string]shape{
	"pod":   {6, 6, 6, 6, 34},
	"cover": {12, 8, 12, 8, 48},
	"prism": {0, 0, 0, 0, 8},
}

// applyShape cuts the window to its outline, so clicks in the transparent
// corners reach the desktop and the window reads as an object, not a box.
func applyShape(name string, w *application.WebviewWindow) {
	s, ok := shapes[name]
	if !ok || w == nil {
		return
	}
	hwnd := uintptr(w.NativeWindow())
	if hwnd == 0 {
		return
	}
	dpi, _, _ := procGetDpiForWin.Call(hwnd)
	if dpi == 0 {
		dpi = 96
	}
	px := func(v int) uintptr { return uintptr(v * int(dpi) / 96) }
	b := w.Bounds()
	rgn, _, _ := procCreateRoundRgn.Call(
		px(s.left), px(s.top), px(b.Width-s.right)+1, px(b.Height-s.bottom)+1,
		px(2*s.radius), px(2*s.radius))
	if rgn != 0 {
		procSetWindowRgn.Call(hwnd, rgn, 1) // the window owns the region from here on
	}
}
