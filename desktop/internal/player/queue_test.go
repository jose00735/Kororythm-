package player

import "testing"

func ids(q *Queue) []string {
	var out []string
	for _, it := range q.Items {
		s := it.ID
		if it.Queued {
			s += "+"
		}
		out = append(out, s)
	}
	return out
}

func eq(a, b []string) bool {
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

func TestPlayNextKeepsPickOrder(t *testing.T) {
	var q Queue
	q.Set([]string{"a", "b", "c"}, 0, "Night Drive")
	q.Enqueue([]string{"x"}, true)
	q.Enqueue([]string{"y"}, true)
	q.Enqueue([]string{"z"}, false)
	want := []string{"a", "x+", "y+", "b", "c", "z+"}
	if got := ids(&q); !eq(got, want) {
		t.Fatalf("queue = %v, want %v", got, want)
	}
	if i, _ := q.PeekNext(); q.Items[i].ID != "x" {
		t.Fatalf("next = %s, want x", q.Items[i].ID)
	}
}

func TestRepeatModes(t *testing.T) {
	var q Queue
	q.Set([]string{"a", "b"}, 1, "")
	if _, ok := q.PeekNext(); ok {
		t.Fatal("repeat off should stop at the end")
	}
	q.CycleRepeat() // all
	if i, ok := q.PeekNext(); !ok || i != 0 {
		t.Fatalf("repeat all should wrap to 0, got %d %v", i, ok)
	}
	q.CycleRepeat() // one
	if i, _ := q.PeekNext(); i != 1 {
		t.Fatalf("repeat one should replay 1, got %d", i)
	}
	if i, ok := q.Next(); !ok || i != 0 {
		t.Fatalf("Next by hand under repeat one should still move, got %d", i)
	}
}

func TestShuffleVisitsEverySongOnce(t *testing.T) {
	var q Queue
	q.Set([]string{"a", "b", "c", "d", "e", "f"}, 2, "")
	q.SetShuffle(true)
	seen := map[int]bool{q.Cur: true}
	for {
		i, ok := q.PeekNext()
		if !ok {
			break
		}
		if seen[i] {
			t.Fatalf("index %d played twice", i)
		}
		seen[i] = true
		q.Cur = i
	}
	if len(seen) != 6 {
		t.Fatalf("visited %d of 6", len(seen))
	}
}

func TestShufflePlayNextStillNext(t *testing.T) {
	var q Queue
	q.Set([]string{"a", "b", "c", "d"}, 0, "")
	q.SetShuffle(true)
	q.Enqueue([]string{"x"}, true)
	if i, _ := q.PeekNext(); q.Items[i].ID != "x" {
		t.Fatalf("with shuffle, play next gave %s", q.Items[i].ID)
	}
}

func TestRemoveAndMoveKeepCurrent(t *testing.T) {
	var q Queue
	q.Set([]string{"a", "b", "c", "d"}, 2, "")
	q.Remove(0)
	if it, _ := q.Current(); it.ID != "c" {
		t.Fatalf("current = %s after removing before it", it.ID)
	}
	q.Move(1, 0) // c to front
	if it, _ := q.Current(); it.ID != "c" || q.Cur != 0 {
		t.Fatalf("current = %s at %d after move", it.ID, q.Cur)
	}
}
