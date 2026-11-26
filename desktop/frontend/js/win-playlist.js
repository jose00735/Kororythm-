import { el, fmt, textButton, titleBar, resizeGrip, contextMenu, ask } from "./ui.js";
import { cmd, winOp, api } from "./bridge.js";

// Consecutive runs of the queue that share an origin, as on Android:
// songs from the list you started, and songs you queued on the fly.
function runs(q) {
  const out = [];
  let start = 0;
  for (let i = 1; i <= q.length; i++) {
    if (i === q.length || !!q[i].queued !== !!q[start].queued) { if (start < i) out.push([start, i - 1]); start = i; }
  }
  return out;
}

export function playlistWindow(root) {
  let st = null;
  let selected = -1;
  let dragFrom = -1;
  const list = el("div", { class: "pl-list", role: "listbox", tabindex: "0", "aria-label": "Playlist" });
  const total = el("span", { class: "pl-total" });

  root.append(
    titleBar("PLAYLIST"),
    el("div", { class: "body pl-body" },
      el("div", { class: "lcd pl-lcd" }, list),
      el("div", { class: "pl-bar" },
        textButton("+ ADD", "Add songs from the library", () => winOp("open", { name: "library" }), "accent"),
        textButton("REM", "Remove the selected song", () => { if (selected >= 0) cmd("remove", { i: selected }); }),
        textButton("CLR", "Clear the playlist", () => cmd("clear")),
        textButton("SAVE", "Save this list as a playlist", async () => {
          if (!st?.queue?.length) return;
          const n = await ask("Save the current queue as", { input: true, ok: "SAVE" });
          if (n) api("playlists", { name: n, ids: st.queue.map((q) => q.id) });
        }),
        el("span", { class: "spacer" }),
        total,
        resizeGrip(),
      ),
    ),
  );

  function render() {
    const q = st?.queue || [];
    list.replaceChildren();
    if (!q.length) {
      list.append(el("div", { class: "pl-empty" },
        el("div", { class: "pl-empty-title" }, "The playlist is empty"),
        el("div", {}, "Open the library and double-click an album, or press + ADD.")));
      total.textContent = "0 songs";
      return;
    }
    for (const [a, b] of runs(q)) {
      const queued = !!q[a].queued;
      const label = !queued ? `FROM ${(st.source || "the playlist").toUpperCase()}`
        : (a > 0 && b === q.length - 1 && !q[a - 1].queued) ? "QUEUED BY YOU · AT THE END" : "QUEUED BY YOU · PLAYS NEXT";
      const n = b - a + 1;
      list.append(el("div", { class: `pl-head ${queued ? "queued" : ""}` },
        el("span", { class: "pl-mark" }), el("span", { class: "pl-head-label" }, label),
        el("span", { class: "pl-head-count" }, n === 1 ? "1 SONG" : `${n} SONGS`)));
      for (let i = a; i <= b; i++) list.append(row(q[i], i));
    }
    const ms = q.reduce((s, t) => s + (t.durationMs || 0), 0);
    total.textContent = `${q.length} songs · ${fmt(ms)}`;
    list.querySelector(".cur")?.scrollIntoView({ block: "nearest" });
  }

  function row(t, i) {
    const cur = i === st.cur;
    const r = el("div", {
      class: `pl-row ${cur ? "cur" : ""} ${i === selected ? "sel" : ""} ${t.missing ? "missing" : ""} ${t.queued ? "queued" : ""}`,
      role: "option", "aria-selected": String(i === selected), "data-i": String(i),
    },
      el("span", { class: "pl-n" }, `${i + 1}.`),
      el("span", { class: "pl-t" }, el("span", { class: "pl-title" }, t.title), el("span", { class: "pl-artist" }, t.artist || "")),
      el("span", { class: "pl-codec" }, t.codec || ""),
      el("span", { class: "pl-dur" }, fmt(t.durationMs)),
    );
    r.addEventListener("click", () => { selected = i; render(); });
    r.addEventListener("dblclick", () => cmd("playAt", { i }));
    r.addEventListener("contextmenu", (e) => {
      selected = i; render();
      contextMenu(e.clientX, e.clientY, [
        { label: "Play", run: () => cmd("playAt", { i }) },
        { label: "Move up", run: () => cmd("move", { i, j: Math.max(0, i - 1) }) },
        { label: "Move down", run: () => cmd("move", { i, j: Math.min(st.queue.length - 1, i + 1) }) },
        "-",
        { label: "Remove", run: () => cmd("remove", { i }) },
      ]);
    });
    // Drag a row up or down to reorder.
    r.addEventListener("pointerdown", (e) => { if (e.button === 0) dragFrom = i; });
    r.addEventListener("pointerup", () => {
      if (dragFrom >= 0 && dragFrom !== i) cmd("move", { i: dragFrom, j: i });
      dragFrom = -1;
    });
    return r;
  }

  list.addEventListener("keydown", (e) => {
    const n = st?.queue?.length || 0;
    if (!n) return;
    if (e.key === "ArrowDown") selected = Math.min(n - 1, selected + 1);
    else if (e.key === "ArrowUp") selected = Math.max(0, selected - 1);
    else if (e.key === "Enter" && selected >= 0) cmd("playAt", { i: selected });
    else if (e.key === "Delete" && selected >= 0) cmd("remove", { i: selected });
    else return;
    e.preventDefault();
    render();
    list.querySelector(".sel")?.scrollIntoView({ block: "nearest" });
  });
  addEventListener("pointerup", () => { dragFrom = -1; });

  return { state(s) { st = s; if (selected >= s.queue.length) selected = -1; render(); } };
}
