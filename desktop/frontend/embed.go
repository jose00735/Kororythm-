// Package frontend holds the windows' pages, embedded into the program.
package frontend

import "embed"

//go:embed index.html dev.html css js
var FS embed.FS
