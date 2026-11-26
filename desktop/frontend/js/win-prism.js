// Prism layout: a media-player window in the Windows 7 style — glass frame, a
// menu bar, a library tree on the left, the light show or a song list in the
// middle, and a glossy control bar with a round play button at the bottom.
import { el, fmt, iconButton, slider, contextMenu, ask } from "./ui.js";
import { cmd, winOp, api } from "./bridge.js";
import { icon, parrot } from "./icons.js";
import { createBrowser } from "./win-library.js";
import { prismEngine, energy } from "./prism.js";

const TREE = [
  ["now", "Now Playing"], ["-", "Library"], ["artists", "Artists"], ["albums", "Albums"], ["songs", "Songs"],
  ["playlists", "Playlists"], ["genres", "Genres"], ["decades", "Decades"],
];

export function prismWindow(root) {
  let st = null;
  let tick = { positionMs: 0, durationMs: 0, playing: false, at: Date.now() };
  let bars = new Array(64).fill(0);
  let view = "now";

  // ---- title bar with menus ----
  const menus = {
    File: () => [
      { label: "Add a music folder…", run: () => api("lib/folders", { pick: true }).catch(() => go("settings")) },
      { label: "Rescan music folders", run: () => api("lib/scan", {}) },
      { label: "Downloads…", run: () => winOp("open", { name: "downloads" }) },
      "-",
      { label: "Exit", run: () => winOp("quit") },
    ],
    View: () => [
      { label: "Now Playing", run: () => go("now") },
      { label: "Library", run: () => go("albums") },
      { label: "iPod browser (Ctrl+B)", run: () => winOp("toggle", { name: "pod" }) },
      "-",
      ...[["classic", "Classic layout"], ["pod", "Pod layout"], ["cover", "Cover Stage layout"]].map(([id, l]) => ({ label: l, run: () => cmd("layout", { name: id }) })),
      "-",
      ...[["neon", "Neon"], ["hifi", "HiFi"], ["pod", "Pod"], ["chrome", "Chrome Deck"]].map(([id, l]) => ({ label: `Skin: ${l}${st?.skin === id ? "  (current)" : ""}`, run: () => cmd("skin", { name: id }) })),
    ],
    Play: () => [
      { label: st?.playing ? "Pause" : "Play", run: () => cmd("toggle") },
      { label: "Stop", run: () => cmd("stop") },
      { label: "Previous", run: () => cmd("prev") },
      { label: "Next", run: () => cmd("next") },
      "-",
      { label: `Shuffle: ${st?.shuffle ? "on" : "off"}`, run: () => cmd("shuffle", { on: !st?.shuffle }) },
      { label: `Repeat: ${st?.repeat || "off"}`, run: () => cmd("repeat") },
      "-",
      { label: "Save the queue as a playlist…", run: async () => { const n = await ask("Save the current queue as", { input: true, ok: "SAVE" }); if (n && st?.queue?.length) api("playlists", { name: n, ids: st.queue.map((q) => q.id) }); } },
    ],
    Tools: () => [
      { label: "Equalizer…", run: () => toggleEq() },
      ...[["Flat"], ["Bass"], ["Rock"], ["Pop"], ["Vocal"], ["Dance"], ["Treble"]].map(([n]) => ({ label: `EQ preset: ${n}`, run: () => cmd("preset", { name: n }) })),
      "-",
      { label: "Settings…", run: () => go("settings") },
    ],
    Help: () => [
      { label: "Keyboard: Space play/pause · Ctrl+B iPod browser · Ctrl+1–4 layouts", run: () => {} },
      { label: "About Kororythm", run: async () => { const a = await api("about"); ask(`Kororythm for Windows ${a.version}\nFLAC and MP3 player · GPL-3.0`, { ok: "OK" }); } },
    ],
  };
  const menuBar = el("div", { class: "pr-menubar", role: "menubar" }, Object.keys(menus).map((m) =>
    el("button", { class: "pr-menu", role: "menuitem", onclick: (e) => { const r = e.currentTarget.getBoundingClientRect(); contextMenu(r.left, r.bottom, menus[m]()); } }, m)));
  const title = el("div", { class: "pr-title" },
    el("span", { class: "pr-logo", html: parrot }), el("span", { class: "pr-name" }, "Kororythm"), menuBar, el("span", { class: "spacer" }),
    iconButton("min", "Minimise", () => winOp("minimize"), "pr-wb"),
    iconButton("close", "Quit Kororythm", () => winOp("quit"), "pr-wb close"));
  dragBy(title);

  // ---- left tree ----
  const tree = el("nav", { class: "pr-tree", "aria-label": "Library" });
  function renderTree(cur) {
    tree.replaceChildren(...TREE.map(([id, label]) => id === "-" ? el("div", { class: "pr-tree-head" }, label) :
      el("button", { class: `pr-node ${cur === id ? "on" : ""} ${id === "now" ? "now" : ""}`, onclick: () => go(id) }, label)));
  }

  // ---- centre: light show or library ----
  const viz = el("canvas", { class: "pr-viz", "aria-hidden": "true" });
  const nowInfo = el("div", { class: "pr-nowinfo" });
  const nowArt = el("div", { class: "pr-nowart" });
  const nowPane = el("div", { class: "pr-now" }, viz, el("div", { class: "pr-nowcard" }, nowArt, nowInfo));
  const main = el("div", { class: "lib-main" });
  const crumbs = el("div", { class: "lib-crumbs" });
  const libPane = el("div", { class: "pr-lib", hidden: true }, crumbs, main);
  let searchT = 0;
  const search = el("input", { class: "pr-search", type: "search", placeholder: "Search", "aria-label": "Search the library",
    oninput: (e) => { clearTimeout(searchT); searchT = setTimeout(() => { if (e.target.value.trim()) { showLib(); browser.search(e.target.value.trim()); } }, 200); } });
  const browser = createBrowser({ main, crumbs, onSection: (s, q) => { if (view !== "now") renderTree(q ? "" : s); } });

  // An equalizer drawer inside the window.
  const eqPane = el("div", { class: "pr-eq", hidden: true });
  function toggleEq() { eqPane.hidden = !eqPane.hidden; if (!eqPane.hidden) drawEq(); }

  function showLib() { view = "lib"; nowPane.hidden = true; libPane.hidden = false; }
  function go(id) {
    search.value = "";
    if (id === "now") { view = "now"; nowPane.hidden = false; libPane.hidden = true; renderTree("now"); return; }
    showLib();
    browser.go(id);
  }

  // ---- bottom control bar ----
  const seek = slider({ label: "Seek", cls: "pr-seek", onChange: (v) => cmd("seek", { ms: Math.round(v * (tick.durationMs || 0)) }) });
  const vol = slider({ label: "Volume", cls: "pr-vol", onInput: (v) => cmd("volume", { v }) });
  const orb = el("button", { class: "pr-orb", "aria-label": "Play or pause", title: "Play / pause (Space)", onclick: () => cmd("toggle") });
  const shuffle = iconButton("shuffle", "Shuffle", () => cmd("shuffle", { on: !st?.shuffle }), "pr-small");
  const repeat = iconButton("repeat", "Repeat", () => cmd("repeat"), "pr-small");
  const miniArt = el("div", { class: "pr-miniart" });
  const miniT = el("div", { class: "pr-mini-t" }), miniA = el("div", { class: "pr-mini-a" });
  const time = el("div", { class: "pr-time" });
  const ctrl = el("div", { class: "pr-ctrl" },
    seek,
    el("div", { class: "pr-ctrl-row" },
      el("div", { class: "pr-mini" }, miniArt, el("div", { class: "pr-mini-text" }, miniT, miniA)),
      el("div", { class: "pr-transport" },
        shuffle, repeat,
        iconButton("stop", "Stop", () => cmd("stop"), "pr-small"),
        el("div", { class: "pr-joined" },
          iconButton("prev", "Previous", () => cmd("prev"), "pr-pn prev"),
          orb,
          iconButton("next", "Next", () => cmd("next"), "pr-pn next")),
        el("span", { class: "pr-vol-ic", html: icon.speaker, "aria-hidden": "true" }), vol),
      el("div", { class: "pr-right" }, time, iconButton("list", "Now Playing / Library", () => go(view === "now" ? "albums" : "now"), "pr-small"))));

  root.append(el("div", { class: "pr-frame" },
    title,
    el("div", { class: "pr-toolbar" }, el("button", { class: "pr-back", "aria-label": "Back", onclick: () => crumbs.querySelector(".crumb-back")?.click(), html: icon.prev }),
      el("div", { class: "pr-crumb" }, "Library"), el("span", { class: "spacer" }), el("label", { class: "pr-searchwrap" }, el("span", { class: "pr-search-ic", html: icon.search }), search)),
    el("div", { class: "pr-body" }, tree, el("div", { class: "pr-centre" }, nowPane, libPane, eqPane)),
    ctrl));
  renderTree("now");

  const position = () => tick.playing ? Math.min(tick.durationMs || Infinity, tick.positionMs + (Date.now() - tick.at)) : tick.positionMs;

  // ---- light show loop ----
  const eng = prismEngine(viz);
  let lastT = performance.now();
  const sm = [0, 0, 0];
  (function loop(now) {
    const dt = Math.min(0.05, (now - lastT) / 1000 || 0.016); lastT = now;
    const [b, m, h] = energy(bars);
    const idle = tick.playing ? 0 : 0.12;
    sm[0] += ((tick.playing ? b : idle) - sm[0]) * 0.35; sm[1] += ((tick.playing ? m : idle) - sm[1]) * 0.3; sm[2] += ((tick.playing ? h : idle * 0.5) - sm[2]) * 0.3;
    if (view === "now" && !document.hidden) { eng.step(dt, sm[0], sm[1], sm[2]); eng.present(); }
    const pos = position(), dur = tick.durationMs || st?.track?.durationMs || 0;
    if (!seek.dragging()) seek.set(dur ? pos / dur : 0);
    time.textContent = `${fmt(pos)} / ${fmt(dur)}`;
    requestAnimationFrame(loop);
  })(performance.now());
  // ---- equalizer drawer ----
  async function drawEq() {
    if (!st) return;
    const FREQ = ["60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K"];
    const eq = JSON.parse(JSON.stringify(st.eq));
    let t = 0;
    const send = () => { clearTimeout(t); t = setTimeout(() => cmd("eq", { eq: { ...eq, preset: "Custom" } }), 40); };
    eqPane.replaceChildren(
      el("div", { class: "pr-eq-head" }, el("span", {}, "Equalizer"), el("span", { class: "spacer" }),
        el("label", { class: "pr-eq-on" }, el("input", { type: "checkbox", checked: eq.enabled, onchange: (e) => { eq.enabled = e.target.checked; send(); } }), "On"),
        el("label", { class: "pr-eq-on" }, el("input", { type: "checkbox", checked: eq.boost, onchange: (e) => { eq.boost = e.target.checked; send(); } }), "Boost"),
        iconButton("close", "Close the equalizer", () => toggleEq(), "pr-small")),
      el("div", { class: "pr-eq-bands" }, FREQ.map((f, i) => {
        const s = slider({ vertical: true, label: `${f} Hz`, onInput: (v) => { eq.bands[i] = Math.round((v * 2 - 1) * 24) / 2; send(); } });
        s.set((eq.bands[i] / 12 + 1) / 2);
        return el("div", { class: "pr-eq-col" }, s, el("label", {}, f));
      })));
  }

  // Keyboard shortcuts of the window.
  addEventListener("keydown", (e) => {
    if (e.target.closest("input")) return;
    if (e.key === " ") { e.preventDefault(); cmd("toggle"); }
    if (e.key === "ArrowRight") cmd("seek", { ms: position() + 5000 });
    if (e.key === "ArrowLeft") cmd("seek", { ms: Math.max(0, position() - 5000) });
  });

  let artId = null;
  return {
    state(s) {
      st = s;
      tick = { positionMs: s.positionMs, durationMs: s.durationMs, playing: s.playing, at: Date.now() };
      browser.state(s);
      const t = s.track;
      orb.innerHTML = s.playing ? icon.pause : icon.play;
      orb.classList.toggle("playing", s.playing);
      shuffle.classList.toggle("on", !!s.shuffle);
      repeat.classList.toggle("on", s.repeat !== "off");
      repeat.innerHTML = s.repeat === "one" ? icon.repeatOne : icon.repeat;
      if (!vol.dragging()) vol.set(s.volume);
      miniT.textContent = t?.title || "Kororythm";
      miniA.textContent = t ? `${t.artist} — ${t.album}` : "Pick something in the library";
      nowInfo.replaceChildren(
        el("div", { class: "pr-now-t" }, t?.title || "Nothing playing"),
        el("div", { class: "pr-now-a" }, t ? `${t.artist} · ${t.album}` : "Choose an album on the left"),
        t ? el("div", { class: "pr-now-f" }, [t.codec, t.bitDepth ? `${t.bitDepth}-bit` : "", t.sampleRate ? `${t.sampleRate / 1000} kHz` : ""].filter(Boolean).join(" · ")) : null);
      if (t?.id !== artId) {
        artId = t?.id;
        for (const box of [miniArt, nowArt]) {
          box.replaceChildren();
          if (t?.hasArt) { const img = el("img", { src: `/api/art/${t.id}`, alt: "" }); img.onerror = () => img.remove(); box.append(img); }
        }
      }
      if (!eqPane.hidden && !eqPane.querySelector(".slider.dragging")) { /* keep sliders while open */ }
    },
    tick(t) { tick = { ...t, at: Date.now() }; },
    bars(b) { bars = b; },
  };
}

/** Lets an element drag the whole window (snapping is done in Go). */
export function dragBy(handle) {
  let start = null, raf = 0, pending = null;
  handle.addEventListener("pointerdown", (e) => {
    if (e.target.closest("button, input, .slider") || e.button !== 0) return;
    start = { x: e.screenX, y: e.screenY };
    handle.setPointerCapture(e.pointerId);
    winOp("dragStart");
  });
  handle.addEventListener("pointermove", (e) => {
    if (!start) return;
    pending = { dx: Math.round(e.screenX - start.x), dy: Math.round(e.screenY - start.y) };
    if (!raf) raf = requestAnimationFrame(() => { raf = 0; winOp("dragMove", pending); });
  });
  handle.addEventListener("pointerup", () => { if (start) { start = null; winOp("dragEnd"); } });
}
