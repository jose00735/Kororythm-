import { el, slider, textButton, titleBar, contextMenu } from "./ui.js";
import { cmd, api } from "./bridge.js";

const FREQS = ["60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K"];
const MAX = 12;

export function eqWindow(root) {
  let eq = null;
  let presets = [];
  let pushT = 0;
  const toDb = (v) => Math.round((v * 2 - 1) * MAX * 2) / 2; // half-dB steps
  const toV = (db) => (db / MAX + 1) / 2;

  const send = () => {
    clearTimeout(pushT);
    pushT = setTimeout(() => cmd("eq", { eq: { ...eq, preset: "Custom" } }), 30);
    drawCurve();
  };
  const onBtn = textButton("ON", "Turn the equalizer on or off", () => { eq.enabled = !eq.enabled; send(); }, "toggle");
  const boostBtn = textButton("BOOST", "Loudness boost: fuller bass and treble", () => { eq.boost = !eq.boost; send(); }, "toggle");
  const presetBtn = textButton("PRESETS", "Choose a preset", (e) => {
    const r = e.currentTarget.getBoundingClientRect();
    contextMenu(r.left, r.bottom + 2, presets.map((p) => ({ label: p.Name, run: () => cmd("preset", { name: p.Name }) })));
  });
  const resetBtn = textButton("RESET", "Set every band to 0 dB", () => { eq.bands = eq.bands.map(() => 0); eq.preamp = 0; send(); });
  const presetName = el("span", { class: "eq-preset" });

  const pre = slider({ vertical: true, label: "Preamp", cls: "eq-slider pre", onInput: (v) => { eq.preamp = toDb(v); send(); } });
  const bands = FREQS.map((f, i) => slider({ vertical: true, label: `${f} Hz`, cls: "eq-slider", onInput: (v) => { eq.bands[i] = toDb(v); send(); } }));
  const curve = el("canvas", { class: "eq-curve", width: "400", height: "40", "aria-hidden": "true" });

  root.append(
    titleBar("EQUALIZER"),
    el("div", { class: "body eq-body" },
      el("div", { class: "eq-top" }, onBtn, boostBtn, el("span", { class: "spacer" }), presetName, presetBtn, resetBtn),
      el("div", { class: "lcd eq-graph" }, curve),
      el("div", { class: "eq-sliders" },
        el("div", { class: "eq-col pre-col" }, pre, el("label", {}, "PRE")),
        el("div", { class: "eq-db" }, el("span", {}, "+12"), el("span", {}, "0"), el("span", {}, "-12")),
        bands.map((s, i) => el("div", { class: "eq-col" }, s, el("label", {}, FREQS[i]))),
      ),
    ),
  );

  async function drawCurve() {
    if (!eq) return;
    let pts;
    try { pts = await api("eq/curve", eq); } catch { return; }
    const g = curve.getContext("2d");
    const w = curve.width, h = curve.height;
    const css = getComputedStyle(document.body);
    g.clearRect(0, 0, w, h);
    g.strokeStyle = css.getPropertyValue("--lcd-dim").trim();
    g.lineWidth = 1;
    g.beginPath(); g.moveTo(0, h / 2); g.lineTo(w, h / 2); g.stroke();
    g.strokeStyle = css.getPropertyValue("--lcd-ink").trim();
    g.lineWidth = 2;
    g.beginPath();
    pts.forEach((db, i) => {
      const x = (i / (pts.length - 1)) * w;
      const y = h / 2 - (Math.max(-15, Math.min(15, db)) / 15) * (h / 2 - 3);
      i ? g.lineTo(x, y) : g.moveTo(x, y);
    });
    g.stroke();
  }

  api("eq/presets").then((p) => { presets = p; }).catch(() => {});

  return {
    state(s) {
      if (bands.some((b) => b.dragging()) || pre.dragging()) return;
      eq = JSON.parse(JSON.stringify(s.eq));
      onBtn.classList.toggle("on", eq.enabled);
      boostBtn.classList.toggle("on", eq.boost);
      presetName.textContent = eq.preset || "Custom";
      pre.set(toV(eq.preamp));
      bands.forEach((b, i) => b.set(toV(eq.bands[i])));
      root.classList.toggle("eq-off", !eq.enabled);
      drawCurve();
    },
  };
}
