// Sync window: pair a phone with a 6-digit code, then watch the songs travel.
// The stage animates every step: a radar while it waits, a line between the PC
// and the phone once they talk, album covers flying along it in the direction
// they go, and the parrot landing on the phone when it is all done.
import { el, textButton, titleBar, resizeGrip, ask } from "./ui.js";
import { api, on } from "./bridge.js";
import { parrot } from "./icons.js";

const css = (name) => getComputedStyle(document.body).getPropertyValue(name).trim() || "#00e5ff";

export function syncWindow(root) {
  let st = null;
  let phase = "idle"; // idle | pairing | talking | moving | done
  let device = "";
  let done = 0, total = 0, bytes = 0, totalBytes = 0;
  let doneAt = 0, phaseAt = performance.now();
  const flyers = []; // covers in flight
  const art = new Map();
  const parrotImg = new Image();
  parrotImg.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(parrot.replace('aria-hidden="true" class="parrot" ', 'xmlns="http://www.w3.org/2000/svg" '));

  const canvas = el("canvas", { class: "sync-stage", "aria-hidden": "true" });
  const headline = el("div", { class: "sync-head" });
  const sub = el("div", { class: "sync-sub" });
  const codeBox = el("div", { class: "sync-code" });
  const devBox = el("div", { class: "sync-devs" });
  const cableBox = el("div", { class: "sync-cable" });
  const logBox = el("div", { class: "sync-log lcd", "aria-live": "polite" });

  root.append(titleBar("SYNC"),
    el("div", { class: "body sync-body" },
      el("div", { class: "lcd sync-screen" }, canvas, el("div", { class: "sync-caption" }, headline, sub)),
      codeBox,
      cableBox,
      el("div", { class: "sync-cols" },
        el("div", { class: "sync-col" }, el("div", { class: "sync-label" }, "PAIRED PHONES"), devBox),
        el("div", { class: "sync-col" }, el("div", { class: "sync-label" }, "ACTIVITY"), logBox)),
      el("div", { class: "dl-foot" }, el("span", { class: "sync-key" }), el("span", { class: "spacer" }), resizeGrip())));

  function setPhase(p) { if (p !== phase) { phase = p; phaseAt = performance.now(); } }

  function render() {
    if (!st) return;
    if (!st.available) {
      headline.textContent = "Sync is not available";
      sub.textContent = "The sync service could not start on this PC.";
      return;
    }
    if (phase === "idle" || phase === "pairing") setPhase(st.code ? "pairing" : "idle");
    if (phase === "idle") {
      headline.textContent = st.devices.length ? "Waiting for your phone" : "Pair your phone";
      sub.textContent = st.devices.length
        ? "Open Kororythm on the phone ▸ LIBRARY ▸ SYNC, on the same Wi-Fi."
        : "Press PAIR A PHONE, then type the code on the phone.";
    } else if (phase === "pairing") {
      headline.textContent = "Type this code on the phone";
      sub.textContent = `Phone: LIBRARY ▸ SYNC ▸ ${st.name || "this PC"}${st.addrs.length ? ` (or ${st.addrs[0]})` : ""}`;
    }
    codeBox.replaceChildren(
      st.code
        ? el("div", { class: "sync-digits", "aria-label": `Pairing code ${st.code.split("").join(" ")}` },
          ...st.code.split("").map((d) => el("span", {}, d)),
          el("span", { class: "sync-left" }, `${Math.max(0, Math.floor(st.codeLeft / 60))}:${String(Math.max(0, st.codeLeft % 60)).padStart(2, "0")}`))
        : textButton("PAIR A PHONE", "Show a code to type on the phone", () => api("sync/code", {}).then(set), "accent"),
      el("div", { class: "sync-addr" }, st.running
        ? `Listening on ${st.addrs.length ? st.addrs.map((a) => `${a}:${st.port}`).join(", ") : `port ${st.port}`}`
        : "Starting…"));
    devBox.replaceChildren(...(st.devices.length ? st.devices.map((d) => el("div", { class: "sync-dev" },
      el("span", { class: "sync-dev-n" }, d.name),
      el("span", { class: "sync-dev-t" }, d.lastSync ? `synced ${ago(d.lastSync)}` : "not synced yet"),
      textButton("FORGET", `Unpair ${d.name}`, async () => {
        if (await ask(`Forget ${d.name}? It will need a new code to sync again.`, { ok: "FORGET", danger: true })) api("sync/forget", { id: d.id }).then(set);
      }))) : [el("div", { class: "sync-empty" }, "None yet")]));
    const c = st.cable || { phones: [] };
    cableBox.replaceChildren(
      el("span", { class: "sync-label" }, "USB CABLE"),
      el("span", { class: "sync-cable-t" }, c.installing ? "Getting adb from Google's platform-tools…"
        : c.error ? c.error
        : !c.on ? "Phone without Wi-Fi? Plug it in (USB debugging on), or turn on USB tethering on the phone."
        : c.phones.length ? c.phones.map((p) => `${p.model || p.serial}: ${p.linked ? "linked" : p.state === "unauthorized" ? "allow USB debugging on the phone" : p.state}`).join(" · ")
        : "Waiting for a phone on USB…"),
      textButton(c.on ? "STOP USB" : "USE USB CABLE", c.on ? "Stop linking phones on USB" : "Link phones plugged in by USB (adb)", () => api("sync/cable", {}).then(set), c.on ? "" : "accent"));
    root.querySelector(".sync-key").textContent = st.key ? `PC key ${st.key} — the phone shows the same when paired` : "";
    logBox.replaceChildren(...st.log.slice(-12).reverse().map((e) => el("div", { class: `sync-ev k-${e.kind}` }, describe(e))));
  }

  function describe(e) {
    switch (e.kind) {
      case "code": return "New pairing code";
      case "paired": return `Paired with ${e.device}`;
      case "start": return `${e.device}: comparing libraries`;
      case "plan": return `${e.device}: ${e.message}`;
      case "file": return `${e.dir === "toPc" ? "←" : "→"} ${e.title}${e.artist ? " · " + e.artist : ""}`;
      case "received": return `✓ ${e.title}`;
      case "done": return `${e.device}: done — ${e.message}`;
      case "error": return `${e.device || "Sync"}: ${e.message}`;
      default: return e.message || e.kind;
    }
  }
  const ago = (ms) => {
    const s = Math.round((Date.now() - ms) / 1000);
    if (s < 60) return "just now";
    if (s < 3600) return `${Math.round(s / 60)} min ago`;
    if (s < 86400) return `${Math.round(s / 3600)} h ago`;
    return new Date(ms).toLocaleDateString();
  };

  function cover(id) {
    if (!id) return null;
    if (!art.has(id)) { const i = new Image(); i.src = `/api/art/${encodeURIComponent(id)}`; art.set(id, i); }
    return art.get(id);
  }

  function event(e) {
    switch (e.kind) {
      case "paired": device = e.device; setPhase("talking"); headline.textContent = `Paired with ${e.device}`; sub.textContent = "Syncing starts on the phone."; break;
      case "start": device = e.device; setPhase("talking"); headline.textContent = `${e.device} is here`; sub.textContent = "Comparing libraries…"; break;
      case "plan":
        total = e.total || 0; totalBytes = e.totalBytes || 0; done = 0; bytes = 0;
        setPhase(total ? "moving" : "done");
        headline.textContent = total ? `Syncing with ${e.device}` : "Already in sync";
        sub.textContent = e.message; break;
      case "file":
        flyers.push({ dir: e.dir, t0: performance.now(), img: cover(e.artId), title: e.title });
        headline.textContent = e.title || "Syncing";
        sub.textContent = `${e.dir === "toPc" ? "From" : "To"} ${e.device}${e.artist ? " · " + e.artist : ""}`; break;
      case "progress":
        done = e.done; total = e.total; bytes = e.bytes; totalBytes = e.totalBytes; break;
      case "done":
        setPhase("done"); doneAt = performance.now();
        headline.textContent = "All synced"; sub.textContent = e.message; break;
    }
    api("sync").then(set).catch(() => {});
  }

  // ---- the stage ----
  const ctx = canvas.getContext("2d");
  function frame(now) {
    const r = canvas.getBoundingClientRect();
    const dpr = devicePixelRatio || 1;
    if (canvas.width !== Math.round(r.width * dpr) || canvas.height !== Math.round(r.height * dpr)) {
      canvas.width = Math.round(r.width * dpr); canvas.height = Math.round(r.height * dpr);
    }
    const W = canvas.width / dpr, H = canvas.height / dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, W, H);
    const accent = css("--lcd-ink"), warm = css("--accent-2"), dim = css("--lcd-dim");
    const t = (now - phaseAt) / 1000;

    if (phase === "idle" || phase === "pairing") radar(W, H, now / 1000, accent, dim);
    else {
      const pc = { x: W * 0.2, y: H * 0.56 }, ph = { x: W * 0.8, y: H * 0.56 };
      const reach = Math.min(1, t / 0.7); // the line draws itself
      link(pc, ph, reach, now / 1000, accent, dim, phase === "moving");
      drawPC(pc, accent);
      drawPhone(ph, accent);
      // covers fly along the arc, 1.4 s per song
      for (let i = flyers.length - 1; i >= 0; i--) {
        const f = flyers[i];
        const p = (now - f.t0) / 1400;
        if (p >= 1) { flyers.splice(i, 1); continue; }
        const e = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
        const [a, b] = f.dir === "toPc" ? [ph, pc] : [pc, ph];
        const x = a.x + (b.x - a.x) * e, y = a.y - Math.sin(e * Math.PI) * H * 0.32 + (b.y - a.y) * e;
        const s = 34 + 14 * Math.sin(e * Math.PI);
        ctx.save();
        ctx.globalAlpha = Math.min(1, p * 6, (1 - p) * 6);
        ctx.translate(x, y); ctx.rotate((e - 0.5) * (f.dir === "toPc" ? -0.5 : 0.5));
        ctx.shadowColor = accent; ctx.shadowBlur = 14;
        if (f.img?.complete && f.img.naturalWidth) ctx.drawImage(f.img, -s / 2, -s / 2, s, s);
        else noteTile(s, accent, warm);
        ctx.restore();
      }
      if (phase === "moving" && total) ring(W / 2, H * 0.2, Math.min(1, totalBytes ? bytes / totalBytes : done / total), accent, dim);
      if (phase === "done") landing(ph, (now - (doneAt || phaseAt)) / 1000, accent, warm);
    }
    requestAnimationFrame(frame);
  }

  function radar(W, H, s, accent, dim) {
    const cx = W / 2, cy = H / 2, R = Math.min(W, H) * 0.44;
    ctx.strokeStyle = dim; ctx.lineWidth = 1;
    for (let i = 1; i <= 3; i++) { ctx.beginPath(); ctx.arc(cx, cy, (R * i) / 3, 0, Math.PI * 2); ctx.stroke(); }
    ctx.beginPath(); ctx.moveTo(cx - R, cy); ctx.lineTo(cx + R, cy); ctx.moveTo(cx, cy - R); ctx.lineTo(cx, cy + R); ctx.stroke();
    const a = (s * 1.6) % (Math.PI * 2);
    const g = ctx.createConicGradient ? ctx.createConicGradient(a - 1.1, cx, cy) : null;
    if (g) {
      g.addColorStop(0, "transparent"); g.addColorStop(0.17, hexA(accent, 0.45)); g.addColorStop(0.175, "transparent"); g.addColorStop(1, "transparent");
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(cx, cy, R, 0, Math.PI * 2); ctx.fill();
    }
    ctx.strokeStyle = accent; ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(cx + Math.cos(a) * R, cy + Math.sin(a) * R); ctx.stroke();
    // a pulse ring going out every two seconds
    const p = (s % 2) / 2;
    ctx.strokeStyle = hexA(accent, 1 - p); ctx.beginPath(); ctx.arc(cx, cy, R * p, 0, Math.PI * 2); ctx.stroke();
    drawPC({ x: cx, y: cy + 4 }, accent, 0.8);
  }

  function link(a, b, reach, s, accent, dim, busy) {
    const mid = { x: (a.x + b.x) / 2, y: a.y - (b.x - a.x) * 0.42 };
    const pt = (u) => ({ x: (1 - u) ** 2 * a.x + 2 * (1 - u) * u * mid.x + u * u * b.x, y: (1 - u) ** 2 * a.y + 2 * (1 - u) * u * mid.y + u * u * b.y });
    ctx.lineWidth = 2; ctx.strokeStyle = dim; ctx.setLineDash([4, 6]); ctx.lineDashOffset = -s * 30;
    ctx.beginPath(); for (let u = 0; u <= reach + 1e-6; u += 0.02) { const p = pt(u); u ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y); } ctx.stroke();
    ctx.setLineDash([]);
    if (busy) { // light pulses running along the line
      for (let k = 0; k < 3; k++) {
        const u = (s * 0.6 + k / 3) % 1, p = pt(u);
        ctx.fillStyle = accent; ctx.shadowColor = accent; ctx.shadowBlur = 10;
        ctx.beginPath(); ctx.arc(p.x, p.y, 2.6, 0, Math.PI * 2); ctx.fill(); ctx.shadowBlur = 0;
      }
    }
  }

  function drawPC(c, accent, k = 1) {
    const w = 54 * k, h = 36 * k;
    ctx.strokeStyle = accent; ctx.lineWidth = 2; ctx.shadowColor = accent; ctx.shadowBlur = 8;
    ctx.strokeRect(c.x - w / 2, c.y - h / 2 - 6 * k, w, h);
    ctx.beginPath(); ctx.moveTo(c.x - 8 * k, c.y + h / 2 - 6 * k); ctx.lineTo(c.x - 12 * k, c.y + h / 2 + 6 * k); ctx.lineTo(c.x + 12 * k, c.y + h / 2 + 6 * k); ctx.lineTo(c.x + 8 * k, c.y + h / 2 - 6 * k); ctx.stroke();
    ctx.shadowBlur = 0;
  }
  function drawPhone(c, accent) {
    ctx.strokeStyle = accent; ctx.lineWidth = 2; ctx.shadowColor = accent; ctx.shadowBlur = 8;
    roundRect(c.x - 15, c.y - 28, 30, 56, 6); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(c.x - 5, c.y - 23); ctx.lineTo(c.x + 5, c.y - 23); ctx.stroke();
    ctx.shadowBlur = 0;
  }
  function noteTile(s, accent, warm) {
    ctx.fillStyle = hexA(accent, 0.18); ctx.strokeStyle = accent; ctx.lineWidth = 1.5;
    roundRect(-s / 2, -s / 2, s, s, 5); ctx.fill(); ctx.stroke();
    ctx.fillStyle = warm; ctx.font = `${Math.round(s * 0.55)}px sans-serif`; ctx.textAlign = "center"; ctx.textBaseline = "middle"; ctx.fillText("♪", 0, 2);
  }
  function ring(x, y, f, accent, dim) {
    ctx.lineWidth = 4; ctx.strokeStyle = dim; ctx.beginPath(); ctx.arc(x, y, 20, 0, Math.PI * 2); ctx.stroke();
    ctx.strokeStyle = accent; ctx.beginPath(); ctx.arc(x, y, 20, -Math.PI / 2, -Math.PI / 2 + f * Math.PI * 2); ctx.stroke();
    ctx.fillStyle = accent; ctx.font = "bold 11px var(--font)"; ctx.textAlign = "center"; ctx.textBaseline = "middle";
    ctx.fillText(`${Math.round(f * 100)}%`, x, y);
  }
  // The parrot flies in from the top left and lands on the phone, then a tick pops.
  function landing(ph, s, accent, warm) {
    const p = Math.min(1, s / 1.1);
    const e = 1 - Math.pow(1 - p, 3);
    const x = ph.x - 160 * (1 - e), y = ph.y - 48 - 110 * (1 - e) + Math.sin(e * Math.PI) * -20;
    const flap = p < 1 ? 1 + 0.18 * Math.sin(s * 28) : 1;
    if (parrotImg.complete) {
      ctx.save(); ctx.translate(x, y); ctx.scale(1, flap);
      ctx.drawImage(parrotImg, -24, -24, 48, 48); ctx.restore();
    }
    if (p >= 1) {
      const q = Math.min(1, (s - 1.1) / 0.35);
      const k = q < 1 ? 0.6 + 0.6 * Math.sin(q * Math.PI * 0.75) : 1;
      ctx.save(); ctx.translate(ph.x + 26, ph.y + 18); ctx.scale(k, k);
      ctx.fillStyle = warm; ctx.beginPath(); ctx.arc(0, 0, 11, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = "#fff"; ctx.lineWidth = 2.6; ctx.lineCap = "round";
      ctx.beginPath(); ctx.moveTo(-5, 0); ctx.lineTo(-1.5, 4); ctx.lineTo(5.5, -4); ctx.stroke(); ctx.restore();
    }
  }
  function roundRect(x, y, w, h, r) {
    ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
  }
  function hexA(c, a) {
    const m = /^#([0-9a-f]{6})$/i.exec(c);
    if (!m) return c;
    const n = parseInt(m[1], 16);
    return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${a})`;
  }

  function set(s) { st = s; render(); }
  on("sync", event);
  on("syncState", set);
  api("sync/start", {}).then(set).catch(() => api("sync").then(set).catch(() => {}));
  setInterval(() => { if (st?.code) api("sync").then(set).catch(() => {}); }, 1000);
  requestAnimationFrame(frame);

  // for the screenshot tests: jump to a phase
  window.__syncDemo = (kind, data) => event({ device: "Pixel 8", ...data, kind });
  return {};
}
