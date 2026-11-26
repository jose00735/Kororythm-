// The iPod-style library browser: a stack of menus walked with a click wheel
// (or the mouse wheel and arrow keys). Used by the Pod window, which also opens
// over every other layout as the library browser (Ctrl+B).
import { api, cmd, winOp } from "./bridge.js";
import { el, fmt } from "./ui.js";
import { icon } from "./icons.js";

const LAYOUTS = [["classic", "Classic"], ["pod", "Pod"], ["prism", "Prism"], ["cover", "Cover Stage"]];
const SKINS = [["neon", "Neon"], ["hifi", "HiFi"], ["pod", "Pod"], ["chrome", "Chrome Deck"]];

export function podMenu(screen, { onNowPlaying, getState }) {
  const stack = [];
  const listEl = el("div", { class: "pm-list", role: "listbox" });
  const head = el("div", { class: "pm-title" });
  screen.append(head, listEl);

  const play = (tracks, i, source) => { cmd("playTracks", { ids: tracks.map((t) => t.id), i, source }); onNowPlaying(); };
  const songsMenu = (title, load, source) => ({
    title, load: async () => {
      const ts = await load();
      if (!ts.length) return [{ label: "No songs", disabled: true }];
      return [
        { label: "Shuffle", run: () => { cmd("playTracks", { ids: shuffle(ts.map((t) => t.id)), i: 0, source: `${source} (shuffled)` }); onNowPlaying(); } },
        ...ts.map((t, i) => ({ label: t.title, sub: t.artist, right: fmt(t.durationMs), run: () => play(ts, i, source), long: t })),
      ];
    },
  });

  const root = {
    title: "Kororythm", load: async () => [
      { label: "Music", open: musicMenu },
      { label: "Now Playing", run: () => onNowPlaying() },
      { label: "Shuffle Songs", run: async () => { const ts = await api("lib/songs"); cmd("playTracks", { ids: shuffle(ts.map((t) => t.id)), i: 0, source: "All songs (shuffled)" }); onNowPlaying(); } },
      { label: "Downloads", run: () => winOp("open", { name: "downloads" }) },
      { label: "Settings", open: settingsMenu },
    ],
  };
  const musicMenu = {
    title: "Music", load: async () => [
      { label: "Playlists", open: { title: "Playlists", load: async () => (await api("playlists")).map((p) => ({ label: p.name, right: String(p.songs), open: songsMenu(p.name, async () => (await api(`playlists/${p.id}`)).tracks, p.name) })) } },
      { label: "Artists", open: { title: "Artists", load: async () => (await api("lib/artists")).map((a) => ({ label: a.name, open: artistMenu(a.name) })) } },
      { label: "Albums", open: { title: "Albums", load: async () => (await api("lib/albums")).map((a) => ({ label: a.title, sub: a.artist, open: songsMenu(a.title, () => api(`lib/songs?album=${encodeURIComponent(a.key)}`), a.title) })) } },
      { label: "Songs", open: songsMenu("Songs", () => api("lib/songs"), "All songs") },
      { label: "Genres", open: { title: "Genres", load: async () => (await api("lib/genres")).map((g) => ({ label: g.name, right: String(g.songs), open: songsMenu(g.name, () => api(`lib/songs?genre=${encodeURIComponent(g.name)}`), g.name) })) } },
      { label: "Decades", open: { title: "Decades", load: async () => (await api("lib/decades")).map((d) => ({ label: `${d}s`, open: songsMenu(`${d}s`, () => api(`lib/songs?decade=${d}`), `${d}s`) })) } },
    ],
  };
  const artistMenu = (name) => ({
    title: name, load: async () => {
      const albums = await api(`lib/albums?artist=${encodeURIComponent(name)}`);
      return [
        { label: "All Songs", open: songsMenu(name, () => api(`lib/songs?artist=${encodeURIComponent(name)}`), name) },
        ...albums.map((a) => ({ label: a.title, sub: a.year ? String(a.year) : "", open: songsMenu(a.title, () => api(`lib/songs?album=${encodeURIComponent(a.key)}`), a.title) })),
      ];
    },
  });
  const settingsMenu = {
    title: "Settings", load: async () => {
      const st = getState() || {};
      return [
        { label: "Layout", open: { title: "Layout", load: async () => LAYOUTS.map(([id, n]) => ({ label: n, check: (getState()?.layout) === id, run: () => cmd("layout", { name: id }) })) } },
        { label: "Skin", open: { title: "Skin", load: async () => SKINS.map(([id, n]) => ({ label: n, check: (getState()?.skin) === id, run: () => { cmd("skin", { name: id }); refresh(); } })) } },
        { label: "Shuffle", right: st.shuffle ? "On" : "Off", run: () => { cmd("shuffle", { on: !getState()?.shuffle }); setTimeout(refresh, 120); } },
        { label: "Repeat", right: { off: "Off", all: "All", one: "One" }[st.repeat] || "Off", run: () => { cmd("repeat"); setTimeout(refresh, 120); } },
        { label: "Library window", run: () => winOp("toggle", { name: "library" }) },
      ];
    },
  };

  let items = [], sel = 0, busy = false;

  async function open(menu, dir = 1) {
    if (busy) return;
    busy = true;
    try {
      const its = await menu.load();
      stack.push({ menu, sel: 0 });
      items = its; sel = 0;
      render(dir);
    } finally { busy = false; }
  }
  async function refresh() {
    const top = stack[stack.length - 1];
    items = await top.menu.load();
    sel = Math.min(sel, items.length - 1);
    render(0);
  }
  function back() {
    if (stack.length <= 1) return false;
    stack.pop();
    const top = stack[stack.length - 1];
    top.menu.load().then((its) => { items = its; sel = top.sel; render(-1); });
    return true;
  }
  function render(dir) {
    const top = stack[stack.length - 1];
    head.textContent = top.menu.title;
    listEl.replaceChildren(...items.map((it, i) => el("div", {
      class: `pm-item ${i === sel ? "sel" : ""} ${it.disabled ? "off" : ""}`, role: "option", "aria-selected": String(i === sel),
      onclick: () => { sel = i; activate(); },
    }, el("span", { class: "pm-label" }, it.label, it.sub ? el("span", { class: "pm-sub" }, it.sub) : null),
      it.check ? el("span", { class: "pm-check", html: icon.check }) : null,
      it.right ? el("span", { class: "pm-right" }, it.right) : null,
      it.open ? el("span", { class: "pm-chev", "aria-hidden": "true" }) : null)));
    if (dir) {
      listEl.classList.remove("in-right", "in-left");
      void listEl.offsetWidth;
      listEl.classList.add(dir > 0 ? "in-right" : "in-left");
    }
    listEl.children[sel]?.scrollIntoView({ block: "nearest" });
  }
  function move(d) {
    if (!items.length) return;
    sel = Math.max(0, Math.min(items.length - 1, sel + d));
    stack[stack.length - 1].sel = sel;
    [...listEl.children].forEach((c, i) => { c.classList.toggle("sel", i === sel); c.setAttribute("aria-selected", String(i === sel)); });
    listEl.children[sel]?.scrollIntoView({ block: "nearest" });
  }
  function activate() {
    const it = items[sel];
    if (!it || it.disabled) return;
    stack[stack.length - 1].sel = sel;
    if (it.open) open(it.open, 1);
    else it.run?.();
  }
  /** Long press on a song: play next. */
  function alt() {
    const it = items[sel];
    if (it?.long) cmd("enqueue", { ids: [it.long.id], next: true });
  }

  open(root, 0);
  return { move, activate, back, alt, refresh, atRoot: () => stack.length <= 1 };
}

function shuffle(a) {
  const b = a.slice();
  for (let i = b.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [b[i], b[j]] = [b[j], b[i]]; }
  return b;
}
