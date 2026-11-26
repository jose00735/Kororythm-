// Package player owns the play queue and ties it to the audio engine: play,
// play next / add to end, shuffle, repeat and the state every window shows.
package player

import "math/rand/v2"

// Repeat modes.
const (
	RepeatOff = "off"
	RepeatAll = "all"
	RepeatOne = "one"
)

// Item is one entry of the queue. Queued marks songs added on the fly
// (play next / add to end) rather than from the list that was started.
type Item struct {
	ID     string `json:"id"`
	Queued bool   `json:"queued,omitempty"`
}

// Queue is the ordered list of songs, the current position and the play order.
type Queue struct {
	Items   []Item `json:"items"`
	Cur     int    `json:"cur"`
	Source  string `json:"source,omitempty"`
	Shuffle bool   `json:"shuffle"`
	Repeat  string `json:"repeat"`
	order   []int  // play order over Items when shuffling
}

// Set replaces the queue with ids, starting at start.
func (q *Queue) Set(ids []string, start int, source string) {
	q.Items = q.Items[:0]
	for _, id := range ids {
		q.Items = append(q.Items, Item{ID: id})
	}
	q.Cur = clamp(start, len(q.Items))
	q.Source = source
	q.reshuffle()
}

func clamp(i, n int) int {
	if n == 0 {
		return -1
	}
	return max(0, min(i, n-1))
}

// Current returns the current item.
func (q *Queue) Current() (Item, bool) {
	if q.Cur < 0 || q.Cur >= len(q.Items) {
		return Item{}, false
	}
	return q.Items[q.Cur], true
}

// Enqueue adds songs on the fly. next = right after the current song, behind
// any songs already queued that way (so they keep the order picked); otherwise
// at the very end.
func (q *Queue) Enqueue(ids []string, next bool) {
	items := make([]Item, len(ids))
	for i, id := range ids {
		items[i] = Item{ID: id, Queued: true}
	}
	if !next || len(q.Items) == 0 {
		q.Items = append(q.Items, items...)
		if q.Cur < 0 {
			q.Cur = 0
		}
		q.reshuffle()
		return
	}
	at := q.Cur + 1
	for at < len(q.Items) && q.Items[at].Queued {
		at++
	}
	q.Items = append(q.Items[:at], append(items, q.Items[at:]...)...)
	q.reshuffleKeepingNext(at, len(items))
}

// Remove drops item i.
func (q *Queue) Remove(i int) {
	if i < 0 || i >= len(q.Items) {
		return
	}
	q.Items = append(q.Items[:i], q.Items[i+1:]...)
	if i < q.Cur || q.Cur >= len(q.Items) {
		q.Cur--
	}
	if len(q.Items) == 0 {
		q.Cur = -1
	}
	q.reshuffle()
}

// Move puts item from at position to.
func (q *Queue) Move(from, to int) {
	if from < 0 || from >= len(q.Items) || to < 0 || to >= len(q.Items) || from == to {
		return
	}
	it := q.Items[from]
	q.Items = append(q.Items[:from], q.Items[from+1:]...)
	q.Items = append(q.Items[:to], append([]Item{it}, q.Items[to:]...)...)
	switch {
	case q.Cur == from:
		q.Cur = to
	case from < q.Cur && to >= q.Cur:
		q.Cur--
	case from > q.Cur && to <= q.Cur:
		q.Cur++
	}
	q.reshuffle()
}

// Clear empties the queue.
func (q *Queue) Clear() { q.Items = nil; q.Cur = -1; q.Source = ""; q.order = nil }

// SetShuffle turns shuffle on or off; the current song stays where it is.
func (q *Queue) SetShuffle(on bool) { q.Shuffle = on; q.reshuffle() }

// CycleRepeat moves off → all → one → off.
func (q *Queue) CycleRepeat() string {
	switch q.Repeat {
	case RepeatAll:
		q.Repeat = RepeatOne
	case RepeatOne:
		q.Repeat = RepeatOff
	default:
		q.Repeat = RepeatAll
	}
	return q.Repeat
}

// reshuffle builds a new random order that starts at the current song.
func (q *Queue) reshuffle() {
	if !q.Shuffle {
		q.order = nil
		return
	}
	n := len(q.Items)
	q.order = make([]int, 0, n)
	if q.Cur >= 0 {
		q.order = append(q.order, q.Cur)
	}
	rest := make([]int, 0, n)
	for i := 0; i < n; i++ {
		if i != q.Cur {
			rest = append(rest, i)
		}
	}
	rand.Shuffle(len(rest), func(a, b int) { rest[a], rest[b] = rest[b], rest[a] })
	q.order = append(q.order, rest...)
}

// reshuffleKeepingNext reshuffles but makes the just-inserted "play next" songs
// come straight after the current one, even with shuffle on.
func (q *Queue) reshuffleKeepingNext(at, n int) {
	if !q.Shuffle {
		q.order = nil
		return
	}
	q.reshuffle()
	pinned := map[int]bool{}
	for i := at; i < at+n; i++ {
		pinned[i] = true
	}
	out := []int{q.order[0]}
	for i := at; i < at+n; i++ {
		out = append(out, i)
	}
	for _, i := range q.order[1:] {
		if !pinned[i] {
			out = append(out, i)
		}
	}
	q.order = out
}

// step returns the index after (dir=+1) or before (dir=-1) the current one,
// following shuffle and repeat. wrapOne makes Repeat One repeat the song.
func (q *Queue) step(dir int, auto bool) (int, bool) {
	n := len(q.Items)
	if n == 0 || q.Cur < 0 {
		return -1, false
	}
	if auto && q.Repeat == RepeatOne {
		return q.Cur, true
	}
	if q.Shuffle && len(q.order) == n {
		pos := 0
		for i, v := range q.order {
			if v == q.Cur {
				pos = i
			}
		}
		pos += dir
		if pos < 0 || pos >= n {
			if !q.wraps(auto) {
				return -1, false
			}
			pos = (pos + n) % n
		}
		return q.order[pos], true
	}
	i := q.Cur + dir
	if i < 0 || i >= n {
		if !q.wraps(auto) {
			return -1, false
		}
		i = (i + n) % n
	}
	return i, true
}

// wraps reports whether moving past either end goes round: always under Repeat
// All, and for a hand press under Repeat One.
func (q *Queue) wraps(auto bool) bool {
	return q.Repeat == RepeatAll || (!auto && q.Repeat == RepeatOne)
}

// PeekNext is the song that will play automatically after the current one.
func (q *Queue) PeekNext() (int, bool) { return q.step(1, true) }

// Next and Prev move by hand (Repeat One does not hold them back).
func (q *Queue) Next() (int, bool) { return q.step(1, false) }
func (q *Queue) Prev() (int, bool) { return q.step(-1, false) }
