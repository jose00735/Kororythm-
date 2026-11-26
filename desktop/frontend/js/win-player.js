import { el, fmt, iconButton, textButton, slider, marquee, titleBar } from "./ui.js";
import { cmd, winOp, api } from "./bridge.js";
import { icon } from "./icons.js";

export function playerWindow(root) {
  let st = null;
  let tick = { positionMs: 0, durationMs: 0, playing: false, at: Date.now() };
  let remaining = false;
  const peaks = new Array(64).fill(0);
  let bars = new Array(64).fill(0);

  const status = el("span", { class: "lcd-status", "aria-hidden": "true" });
  const time = el("button", { class: "lcd-time", title: "Click to switch elapsed / remaining", onclick: () => { remaining = !remaining; draw(); } });
  const canvas = el("canvas", { class: "lcd-viz", width: "336", height: "84", "aria-hidden": "true" });
  const title = marquee("lcd-title");
  const fmtLine = el("div", { class: "lcd-format" });
  const badges = el("div", { class: "lcd-badges" });
  const seek = slider({
    label: "Seek",
    cls: "seek",
    onInput: (v) => { time.textContent = fmt(v * (tick.durationMs || 0)); },
    onChange: (v) => cmd("seek", { ms: Math.round(v * (tick.durationMs || 0)) }),
  });
  const vol = slider({ label: "Volume", cls: "volume", onInput: (v) => cmd("volume", { v }) });
  const shuffle = iconButton("shuffle", "Shuffle", () => cmd("shuffle", { on: !st?.shuffle }), "toggle");
  const repeat = iconButton("repeat", "Repeat: off", () => cmd("repeat"), "toggle");
  const eqT = textButton("EQ", "Show or hide the equalizer", () => winOp("toggle", { name: "eq" }), "toggle small");
  const plT = textButton("PL", "Show or hide the playlist", () => winOp("toggle", { name: "playlist" }), "toggle small");
  const libT = textButton("LIB", "Show or hide the library", () => winOp("toggle", { name: "library" }), "toggle small");

  const shadeTime = el("span", { class: "sh-time" });
  const shadeTitle = marquee("sh-title");

  root.append(
    titleBar("KORORYTHM"),
    el("div", { class: "shade-strip" },
      shadeTime, shadeTitle,
      iconButton("prev", "Previous", () => cmd("prev"), "mini"),
      iconButton("play", "Play / pause", () => cmd("toggle"), "mini"),
      iconButton("next", "Next", () => cmd("next"), "mini"),
    ),
    el("div", { class: "body player-body" },
      el("div", { class: "lcd" },
        el("div", { class: "lcd-top" }, status, time),
        canvas,
      ),
      el("div", { class: "info" },
        el("div", { class: "lcd lcd-line" }, title),
        el("div", { class: "info-row" }, fmtLine, badges),
        el("div", { class: "info-row vol-row" },
          el("span", { class: "vol-icon", html: icon.speaker, "aria-hidden": "true" }), vol,
          el("span", { class: "spacer" }), eqT, plT, libT),
      ),
      el("div", { class: "seek-row" }, seek),
      el("div", { class: "transport" },
        iconButton("prev", "Previous (Z)", () => cmd("prev"), "tp"),
        iconButton("play", "Play (X)", () => cmd("play"), "tp play"),
        iconButton("pause", "Pause (C)", () => cmd("pause"), "tp"),
        iconButton("stop", "Stop (V)", () => cmd("stop"), "tp"),
        iconButton("next", "Next (B)", () => cmd("next"), "tp"),
        iconButton("eject", "Open the library", () => winOp("open", { name: "library" }), "tp eject"),
        el("span", { class: "spacer" }),
        shuffle, repeat,
      ),
    ),
  );

  const position = () => {
    if (!tick.playing) return tick.positionMs;
    return Math.min(tick.durationMs || Infinity, tick.positionMs + (Date.now() - tick.at));
  };

  function draw() {
    const pos = position();
    const dur = tick.durationMs || st?.track?.durationMs || 0;
    const t = remaining && dur ? "-" + fmt(dur - pos) : fmt(pos);
    time.textContent = t;
    shadeTime.textContent = t;
    if (!seek.dragging()) seek.set(dur ? pos / dur : 0);
  }

  function drawViz() {
    const g = canvas.getContext("2d");
    const w = canvas.width, h = canvas.height;
    g.clearRect(0, 0, w, h);
    const css = getComputedStyle(document.body);
    const ink = css.getPropertyValue("--lcd-ink").trim() || "#00e5ff";
    const peak = css.getPropertyValue("--accent-2").trim() || "#ff6a00";
    const dim = css.getPropertyValue("--lcd-dim").trim() || "#0b4a57";
    const n = 32, gap = 2, bw = (w - gap * (n - 1)) / n;
    for (let i = 0; i < n; i++) {
      const v = Math.max(bars[2 * i] || 0, bars[2 * i + 1] || 0);
      peaks[i] = Math.max(v, (peaks[i] || 0) - 0.012);
      const x = i * (bw + gap);
      const segs = 14, sh = h / segs;
      const lit = Math.round(v * segs);
      for (let sgi = 0; sgi < segs; sgi++) {
        g.fillStyle = sgi < lit ? ink : dim;
        g.globalAlpha = sgi < lit ? 0.35 + 0.65 * (sgi / segs) : 0.35;
        g.fillRect(x, h - (sgi + 1) * sh + 1, bw, sh - 2);
      }
      g.globalAlpha = 1;
      g.fillStyle = peak;
      g.fillRect(x, h - Math.max(1, Math.round(peaks[i] * segs)) * sh + 1, bw, 2);
    }
  }

  (function loop() { draw(); drawViz(); requestAnimationFrame(loop); })();

  addEventListener("keydown", (e) => {
    if (e.target.closest("input")) return;
    const k = e.key.toLowerCase();
    const map = { z: "prev", x: "play", c: "pause", v: "stop", b: "next", " ": "toggle" };
    if (map[k]) { e.preventDefault(); cmd(map[k]); }
    if (e.key === "ArrowRight" && !e.target.closest(".slider")) cmd("seek", { ms: position() + 5000 });
    if (e.key === "ArrowLeft" && !e.target.closest(".slider")) cmd("seek", { ms: Math.max(0, position() - 5000) });
  });

  return {
    state(s) {
      st = s;
      tick = { positionMs: s.positionMs, durationMs: s.durationMs, playing: s.playing, at: Date.now() };
      const t = s.track;
      const line = t ? `${(s.cur ?? 0) + 1}. ${t.artist} - ${t.title} (${fmt(t.durationMs)})` : "Kororythm · open the library to start";
      title.set(line);
      shadeTitle.set(line);
      status.innerHTML = s.playing ? icon.play : (t && s.positionMs > 0 ? icon.pause : icon.stop);
      status.className = `lcd-status ${s.playing ? "on" : ""}`;
      if (t) {
        const parts = [];
        if (t.bitrateKb) parts.push(`${t.bitrateKb} kbps`);
        if (t.sampleRate) parts.push(`${(t.sampleRate / 1000).toFixed(t.sampleRate % 1000 ? 1 : 0)} kHz`);
        if (t.bitDepth) parts.push(`${t.bitDepth} bit`);
        fmtLine.textContent = parts.join(" · ");
        badges.textContent = t.codec || "";
      } else {
        fmtLine.textContent = s.scanning ? "Scanning your music…" : `${s.songs} songs in the library`;
        badges.textContent = "";
      }
      if (!vol.dragging()) vol.set(s.volume);
      shuffle.classList.toggle("on", !!s.shuffle);
      repeat.classList.toggle("on", s.repeat !== "off");
      repeat.innerHTML = s.repeat === "one" ? icon.repeatOne : icon.repeat;
      repeat.title = `Repeat: ${s.repeat}`;
      repeat.setAttribute("aria-label", repeat.title);
      root.classList.toggle("has-message", !!s.message);
      if (s.message) fmtLine.textContent = s.message;
    },
    tick(t) { tick = { ...t, at: Date.now() }; },
    bars(b) { bars = b; },
    wins(w) {
      eqT.classList.toggle("on", !!w.eq?.open);
      plT.classList.toggle("on", !!w.playlist?.open);
      libT.classList.toggle("on", !!w.library?.open);
    },
  };
}

export { api };
