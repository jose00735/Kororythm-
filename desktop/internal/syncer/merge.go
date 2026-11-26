package syncer

import (
	"path"
	"sort"
	"strings"
)

// PCTrack is a PC song with its fingerprint.
type PCTrack struct {
	ID       string
	FP       string
	Name     string
	Size     int64
	Title    string
	Artist   string
	Album    string
	Bits     int
	HasArt   bool
	Plays    int
	LastPlay int64 // ms
}

func key(name string) string { return strings.ToLower(strings.TrimSpace(name)) }

// safe makes a tag usable as a folder name.
func safe(s, fallback string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return fallback
	}
	r := strings.NewReplacer("/", "-", "\\", "-", ":", "-", "*", "-", "?", "", "\"", "'", "<", "(", ">", ")", "|", "-")
	s = strings.Trim(r.Replace(s), ". ")
	if s == "" {
		return fallback
	}
	if len(s) > 80 {
		s = s[:80]
	}
	return s
}

// Folder is where a song is kept on the other side: "Artist/Album".
func Folder(artist, album string) string {
	return path.Join(safe(artist, "Unknown artist"), safe(album, "Unknown album"))
}

// MergePlaylists decides, per playlist name, which side's copy wins: a playlist only one
// side has goes to the other; when both have it, the newer edit wins. Deleting a playlist
// on one side does not delete it on the other (it simply comes back).
func MergePlaylists(pc, phone []ManifestPlaylist) (forPhone, forPC []ManifestPlaylist) {
	pm := map[string]ManifestPlaylist{}
	for _, p := range phone {
		pm[key(p.Name)] = p
	}
	seen := map[string]bool{}
	for _, p := range pc {
		k := key(p.Name)
		seen[k] = true
		q, ok := pm[k]
		switch {
		case !ok:
			forPhone = append(forPhone, p)
		case same(p.Items, q.Items):
		case p.Modified >= q.Modified:
			forPhone = append(forPhone, p)
		default:
			forPC = append(forPC, q)
		}
	}
	for _, q := range phone {
		if !seen[key(q.Name)] {
			forPC = append(forPC, q)
		}
	}
	return
}

func same(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// MergePlays adds up the plays made on each side since the last sync ([base]).
func MergePlays(pc, phone, base int) int {
	if base > pc || base > phone { // forgotten pairing or a reset library
		base = min(pc, phone)
	}
	return pc + phone - base
}

// ComputePlan works out the files to move each way and the playlists the phone applies.
func ComputePlan(pc []PCTrack, pcLists []ManifestPlaylist, req StartRequest) Plan {
	onPhone := map[string]bool{}
	for _, t := range req.Tracks {
		onPhone[t.FP] = true
	}
	onPC := map[string]*PCTrack{}
	for i := range pc {
		onPC[pc[i].FP] = &pc[i]
	}
	var plan Plan
	o := req.Options

	// Which PC songs the phone should have.
	want := map[string]bool{}
	if len(o.Only) > 0 {
		only := map[string]bool{}
		for _, n := range o.Only {
			only[key(n)] = true
		}
		for _, l := range pcLists {
			if only[key(l.Name)] {
				for _, fp := range l.Items {
					want[fp] = true
				}
			}
		}
	}
	sorted := append([]PCTrack(nil), pc...)
	sort.Slice(sorted, func(i, j int) bool {
		a, b := sorted[i], sorted[j]
		if key(a.Artist) != key(b.Artist) {
			return key(a.Artist) < key(b.Artist)
		}
		if key(a.Album) != key(b.Album) {
			return key(a.Album) < key(b.Album)
		}
		return a.Name < b.Name
	})
	var used int64
	if !o.NoDownload {
		for _, t := range sorted {
			if onPhone[t.FP] || (len(o.Only) > 0 && !want[t.FP]) {
				continue
			}
			size := t.Size
			conv := o.MaxBits > 0 && t.Bits > o.MaxBits && strings.HasSuffix(strings.ToLower(t.Name), ".flac")
			if conv {
				size = size * int64(o.MaxBits) / int64(t.Bits)
			}
			if o.Budget > 0 && used+size > o.Budget {
				plan.Skipped++
				continue
			}
			used += size
			tr := Transfer{FP: t.FP, Name: t.Name, Folder: Folder(t.Artist, t.Album), Size: size, Title: t.Title, Artist: t.Artist, Album: t.Album, Convert: conv}
			if t.HasArt {
				tr.ArtID = t.ID
			}
			plan.ToPhone = append(plan.ToPhone, tr)
		}
	}
	if !o.NoUpload {
		for _, t := range req.Tracks {
			if onPC[t.FP] == nil {
				plan.ToPC = append(plan.ToPC, Transfer{FP: t.FP, Name: t.Name, Folder: Folder(t.Artist, t.Album), Size: t.Size, Title: t.Title, Artist: t.Artist, Album: t.Album})
			}
		}
	}

	// Playlists for the phone: only those whose songs it will have.
	will := map[string]bool{}
	for fp := range onPhone {
		will[fp] = true
	}
	for _, t := range plan.ToPhone {
		will[t.FP] = true
	}
	forPhone, _ := MergePlaylists(pcLists, req.Playlists)
	for _, l := range forPhone {
		if len(o.Only) > 0 && !contains(o.Only, l.Name) {
			continue
		}
		items := make([]string, 0, len(l.Items))
		for _, fp := range l.Items {
			if will[fp] {
				items = append(items, fp)
			}
		}
		if len(items) > 0 {
			plan.Playlists = append(plan.Playlists, ManifestPlaylist{Name: l.Name, Items: items, Modified: l.Modified})
		}
	}
	if plan.ToPhone == nil {
		plan.ToPhone = []Transfer{}
	}
	if plan.ToPC == nil {
		plan.ToPC = []Transfer{}
	}
	if plan.Playlists == nil {
		plan.Playlists = []ManifestPlaylist{}
	}
	return plan
}

func contains(list []string, name string) bool {
	for _, n := range list {
		if key(n) == key(name) {
			return true
		}
	}
	return false
}
