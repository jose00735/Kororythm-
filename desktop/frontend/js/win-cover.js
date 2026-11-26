// Cover Stage layout: a borderless window shaped like a phone running the same
// cover flow as the Android app — the queue on one strip that glides with a
// spring, side covers turned towards the middle.
import { el, fmt, iconButton, slider, contextMenu } from "./ui.js";
import { cmd, winOp } from "./bridge.js";
import { icon } from "./icons.js";
import { dragBy } from "./win-prism.js";

const STEP = 0.56, FAR = 0.2, TILT = 62, VISIBLE = 4;

export function coverWindow(root) {
  let st = null;
  let tick = { positionMs: 0, durationMs: 0, playing: false, at: Date.now() };
  // Spring state of the strip: pos is the (fractional) index in the middle.
  let pos = 0, vel = 0, target = 0, dragging = false;

  const clock = el("span", { class: "ph-clock" });
  const status = el("div", { class: "ph-status" }, clock, el("span", { class: "spacer" }),
    el("span", { class: "ph-sig" }), el("span", { class: "ph-batt" }),
    iconButton("min", "Minimise", () => winOp("minimize"), "ph-wb"),
    iconButton("close", "Quit Kororythm", () => winOp("quit"), "ph-wb"));
  dragBy(status);

  const backdrop = el("div", { class: "ph-backdrop" });
  const strip = el("div", { class: "ph-strip", tabindex: "0", "aria-label": "Cover flow. Arrow keys move, Enter plays" });
  const title = el("div", { class: "ph-title" }), artist = el("div", { class: "ph-artist" });
  const meta = el("div", { class: "ph-meta" }, title, artist);
  const seek = slider({ label: "Seek", cls: "ph-seek", onChange: (v) => cmd("seek", { ms: Math.round(v * (tick.durationMs || 0)) }) });
  const tL = el("span"), tR = el("span");
  const play = iconButton("play", "Play or pause", () => cmd("toggle"), "ph-play");
  const shuffle = iconButton("shuffle", "Shuffle", () => cmd("shuffle", { on: !st?.shuffle }), "ph-side");
  const upnext = el("div", { class: "ph-upnext" });
  const more = iconButton("list", "Library, layouts and skins", (e) => {
    const r = e.currentTarget.getBoundingClientRect();
    contextMenu(r.left - 120, r.top - 250, [
      { label: "Browse like an iPod (Ctrl+B)", run: () => winOp("toggle", { name: "pod" }) },
      { label: "Library window", run: () => winOp("toggle", { name: "library" }) },
      { label: "Downloads", run: () => winOp("open", { name: "downloads" }) },
      "-",
      ...[["classic", "Classic layout"], ["pod", "Pod layout"], ["prism", "Prism layout"]].map(([id, l]) => ({ label: l, run: () => cmd("layout", { name: id }) })),
      "-",
      ...[["neon", "Neon"], ["hifi", "HiFi"], ["pod", "Pod"], ["chrome", "Chrome Deck"]].map(([id, l]) => ({ label: `Skin: ${l}`, run: () => cmd("skin", { name: id }) })),
    ]);
  }, "ph-side");

  const screen = el("div", { class: "ph-screen" },
    backdrop, el("div", { class: "ph-shade" }),
    status,
    el("div", { class: "ph-app" },
      el("div", { class: "ph-head" }, el("span", { class: "ph-brand" }, "COVER STAGE"), el("span", { class: "spacer" }), more),
      strip, meta, seek, el("div", { class: "ph-times" }, tL, tR),
      el("div", { class: "ph-transport" }, shuffle,
        iconButton("prev", "Previous", () => cmd("prev"), "ph-pn"), play, iconButton("next", "Next", () => cmd("next"), "ph-pn"),
        iconButton("repeat", "Repeat", () => cmd("repeat"), "ph-side")),
      el("div", { class: "ph-upnext-label" }, "UP NEXT"), upnext),
    el("div", { class: "ph-notch" }));
  const phone = el("div", { class: "phone" }, el("span", { class: "ph-btn b1" }), el("span", { class: "ph-btn b2" }), el("span", { class: "ph-btn b3" }), screen);
  dragBy(phone);
  root.append(phone);

  // ---- cover flow ----
  const covers = new Map(); // index -> element
  function coverEl(i) {
    let c = covers.get(i);
    const t = st.queue[i];
    if (!c || c.dataset.id !== t.id) {
      c?.remove();
      c = el("div", { class: "ph-cover", "data-id": t.id },
        el("div", { class: "ph-cover-face" }, el("span", { class: "art-ph", html: icon.note })),
        el("div", { class: "ph-cover-refl" }));
      const img = el("img", { src: `/api/art/${t.id}`, alt: "", draggable: "false" });
      img.onerror = () => img.remove();
      c.querySelector(".ph-cover-face").append(img);
      const r = img.cloneNode();
      r.onerror = () => r.remove();
      c.querySelector(".ph-cover-refl").append(r);
      c.addEventListener("click", () => { if (Math.abs(i - pos) < 0.5) cmd("toggle"); else cmd("playAt", { i }); });
      strip.append(c);
      covers.set(i, c);
    }
    return c;
  }
  function layout() {
    if (!st?.queue?.length) { strip.replaceChildren(); covers.clear(); return; }
    const side = strip.clientWidth * 0.62;
    strip.style.setProperty("--side", `${side}px`);
    const from = Math.max(0, Math.floor(pos) - VISIBLE), to = Math.min(st.queue.length - 1, Math.ceil(pos) + VISIBLE);
    for (const [i, c] of covers) if (i < from || i > to) { c.remove(); covers.delete(i); }
    for (let i = from; i <= to; i++) {
      const c = coverEl(i);
      const d = i - pos, ad = Math.abs(d), near = Math.max(-1, Math.min(1, d));
      const tx = ad <= 1 ? d * side * STEP : Math.sign(d) * (side * STEP + (ad - 1) * side * FAR);
      const s = 1 - 0.2 * Math.min(1, ad);
      c.style.transform = `translateX(${tx}px) rotateY(${-near * TILT}deg) scale(${s})`;
      c.style.zIndex = String(100 - Math.round(ad * 10));
      c.style.opacity = ad <= 2.6 ? "1" : String(Math.max(0, 1 - (ad - 2.6) / 1.4));
      c.style.setProperty("--shade", String(0.5 * Math.min(1.4, ad) / 1.4));
    }
  }
  // Spring towards the current song (damping 0.82, like the Android version).
  let lastT = performance.now();
  (function loop(now) {
    const dt = Math.min(0.05, (now - lastT) / 1000); lastT = now;
    if (!dragging) {
      if (Math.abs(target - pos) > 4) pos = target - Math.sign(target - pos) * 3; // long jumps skip ahead
      const k = 170, c = 2 * 0.82 * Math.sqrt(k);
      vel += (k * (target - pos) - c * vel) * dt;
      pos += vel * dt;
      if (Math.abs(target - pos) < 0.0005 && Math.abs(vel) < 0.001) { pos = target; vel = 0; }
    }
    layout();
    const p = tick.playing ? Math.min(tick.durationMs || Infinity, tick.positionMs + (Date.now() - tick.at)) : tick.positionMs;
    const dur = tick.durationMs || st?.track?.durationMs || 0;
    if (!seek.dragging()) seek.set(dur ? p / dur : 0);
    tL.textContent = fmt(p); tR.textContent = "-" + fmt(Math.max(0, dur - p));
    const d = new Date();
    clock.textContent = `${d.getHours()}:${String(d.getMinutes()).padStart(2, "0")}`;
    requestAnimationFrame(loop);
  })(performance.now());

  // Swipe / fling the strip with the mouse.
  let sx = 0, spos = 0, samples = [];
  strip.addEventListener("pointerdown", (e) => {
    dragging = true; sx = e.clientX; spos = pos; samples = [[performance.now(), pos]];
    strip.setPointerCapture(e.pointerId);
  });
  strip.addEventListener("pointermove", (e) => {
    if (!dragging || !st?.queue?.length) return;
    const step = strip.clientWidth * 0.62 * STEP;
    let p = spos - (e.clientX - sx) / step;
    const n = st.queue.length - 1;
    if (p < 0) p *= 0.35; else if (p > n) p = n + (p - n) * 0.35;
    pos = p;
    samples.push([performance.now(), pos]); if (samples.length > 6) samples.shift();
  });
  strip.addEventListener("pointerup", (e) => {
    if (!dragging) return;
    dragging = false;
    const moved = Math.abs(e.clientX - sx) > 6;
    const [t0, p0] = samples[0], [t1, p1] = samples[samples.length - 1];
    vel = t1 > t0 ? ((p1 - p0) / (t1 - t0)) * 1000 : 0;
    if (!moved) { pos = spos; return; } // a click; the cover's own handler runs
    const to = Math.max(0, Math.min(st.queue.length - 1, Math.round(pos + vel * 0.22)));
    target = to;
    if (to !== st.cur) cmd("playAt", { i: to });
  });
  strip.addEventListener("wheel", (e) => {
    e.preventDefault();
    if (!st?.queue?.length) return;
    const to = Math.max(0, Math.min(st.queue.length - 1, st.cur + (e.deltaY > 0 ? 1 : -1)));
    if (to !== st.cur) cmd("playAt", { i: to });
  }, { passive: false });
  addEventListener("keydown", (e) => {
    if (e.key === "ArrowRight") cmd("next");
    else if (e.key === "ArrowLeft") cmd("prev");
    else if (e.key === " " || e.key === "Enter") cmd("toggle");
    else return;
    e.preventDefault();
  });

  let shown = null;
  return {
    state(s) {
      const first = !st;
      st = s;
      tick = { positionMs: s.positionMs, durationMs: s.durationMs, playing: s.playing, at: Date.now() };
      if (s.cur >= 0) { target = s.cur; if (first) pos = target; }
      // Covers whose song moved in the queue are rebuilt.
      for (const [i, c] of covers) if (!s.queue[i] || s.queue[i].id !== c.dataset.id) { c.remove(); covers.delete(i); }
      const t = s.track;
      play.innerHTML = s.playing ? icon.pause : icon.play;
      shuffle.classList.toggle("on", !!s.shuffle);
      if (t?.id !== shown) {
        const dir = shown && st ? 1 : 0;
        shown = t?.id;
        meta.classList.remove("slide");
        void meta.offsetWidth;
        if (dir) meta.classList.add("slide");
        title.textContent = t?.title || "Nothing playing";
        artist.textContent = t ? [t.artist, t.album].filter(Boolean).join(" · ") : "Pick something in the library";
        backdrop.replaceChildren();
        if (t?.hasArt) { const img = el("img", { src: `/api/art/${t.id}`, alt: "" }); img.onerror = () => img.remove(); backdrop.append(img); }
      }
      upnext.replaceChildren(...s.queue.slice(s.cur + 1, s.cur + 9).map((q, k) => el("button", {
        class: "ph-next-item", title: `${q.title} — ${q.artist}`, onclick: () => cmd("playAt", { i: s.cur + 1 + k }),
      }, el("span", { class: "ph-next-art" }, (() => { const i = el("img", { src: `/api/art/${q.id}`, alt: "" }); i.onerror = () => i.remove(); return i; })()),
        el("span", { class: "ph-next-t" }, q.title))));
    },
    tick(t) { tick = { ...t, at: Date.now() }; },
  };
}
