// Pod layout: a borderless window drawn as the iPod outline, with a screen and
// a click wheel. Turn the wheel by dragging round it, with the mouse wheel or
// the arrow keys; Enter is the centre button, Esc or Backspace is MENU.
import { el, fmt, iconButton } from "./ui.js";
import { cmd, winOp } from "./bridge.js";
import { icon } from "./icons.js";
import { podMenu } from "./podmenu.js";

export function podWindow(root) {
  let st = null;
  let tick = { positionMs: 0, durationMs: 0, playing: false, at: Date.now() };
  let mode = "menu"; // menu | now | scrub | volume
  let volT = 0;

  const screen = el("div", { class: "pod-screen" });
  const bar = el("div", { class: "pod-bar" }, el("span", { class: "pod-bar-title" }, "Kororythm"), el("span", { class: "pod-play-ic" }), el("span", { class: "pod-batt" }));
  const menuPane = el("div", { class: "pod-pane pod-menu" });
  const now = el("div", { class: "pod-pane pod-now", hidden: true });
  screen.append(bar, menuPane, now);

  const art = el("div", { class: "pod-art" });
  const nTitle = el("div", { class: "pod-t" }), nArtist = el("div", { class: "pod-a" }), nAlbum = el("div", { class: "pod-al" });
  const nCount = el("div", { class: "pod-count" });
  const prog = el("div", { class: "pod-prog" }, el("span", { class: "pod-prog-fill" }), el("span", { class: "pod-prog-knob" }));
  const tL = el("span"), tR = el("span");
  const modeLabel = el("div", { class: "pod-mode" });
  now.append(el("div", { class: "pod-now-top" }, art, el("div", { class: "pod-now-text" }, nCount, nTitle, nArtist, nAlbum)), modeLabel, prog, el("div", { class: "pod-times" }, tL, tR));

  const menu = podMenu(menuPane, { onNowPlaying: () => setMode("now"), getState: () => st });

  const wheel = el("div", { class: "pod-wheel", role: "group", "aria-label": "Click wheel", tabindex: "0" },
    el("button", { class: "pw-btn pw-menu", "aria-label": "Menu" }, "MENU"),
    el("button", { class: "pw-btn pw-prev", "aria-label": "Previous", html: icon.prev }),
    el("button", { class: "pw-btn pw-next", "aria-label": "Next", html: icon.next }),
    el("button", { class: "pw-btn pw-play", "aria-label": "Play or pause", html: icon.play + icon.pause }),
    el("button", { class: "pw-centre", "aria-label": "Select" }));

  const controls = el("div", { class: "pod-wc" },
    iconButton("min", "Minimise", () => winOp("minimize"), "tb"),
    iconButton("close", "Close", () => winOp("close"), "tb"));

  const body = el("div", { class: "pod-body" }, controls, screen, wheel);
  root.append(body);

  function setMode(m) {
    mode = m;
    menuPane.hidden = m !== "menu";
    now.hidden = m === "menu";
    modeLabel.textContent = m === "scrub" ? "SCRUB · turn to move through the song" : m === "volume" ? "VOLUME" : "";
    root.classList.toggle("pod-scrub", m === "scrub");
    root.classList.toggle("pod-vol", m === "volume");
    drawNow();
  }

  // ---- wheel input ----
  function turn(dir) {
    if (mode === "menu") return menu.move(dir);
    if (mode === "scrub") {
      const pos = Math.max(0, position() + dir * 4000);
      tick = { ...tick, positionMs: pos, at: Date.now() };
      cmd("seek", { ms: pos });
      return;
    }
    // Now Playing: the wheel is the volume, as on the real thing.
    if (st) {
      st.volume = Math.max(0, Math.min(1, (st.volume || 0) + dir * 0.03));
      cmd("volume", { v: st.volume });
      if (mode !== "volume") setMode("volume");
      clearTimeout(volT);
      volT = setTimeout(() => mode === "volume" && setMode("now"), 1500);
      drawNow();
    }
  }
  function centre() {
    if (mode === "menu") return menu.activate();
    setMode(mode === "scrub" ? "now" : "scrub");
  }
  function menuBtn() {
    if (mode !== "menu") return setMode("menu");
    if (!menu.back()) setMode("now");
  }

  // Drag round the ring: one step every 15 degrees.
  let last = null, acc = 0, moved = false;
  const angleOf = (e) => {
    const r = wheel.getBoundingClientRect();
    return Math.atan2(e.clientY - (r.top + r.height / 2), e.clientX - (r.left + r.width / 2));
  };
  wheel.addEventListener("pointerdown", (e) => {
    if (e.target.closest(".pw-centre")) return;
    last = angleOf(e); acc = 0; moved = false;
    wheel.setPointerCapture(e.pointerId);
  });
  wheel.addEventListener("pointermove", (e) => {
    if (last == null) return;
    const a = angleOf(e);
    let d = a - last;
    if (d > Math.PI) d -= 2 * Math.PI;
    if (d < -Math.PI) d += 2 * Math.PI;
    last = a;
    acc += d;
    const stepAng = Math.PI / 12;
    while (Math.abs(acc) >= stepAng) { const s = Math.sign(acc); acc -= s * stepAng; moved = true; turn(s); }
  });
  wheel.addEventListener("pointerup", (e) => {
    const wasMoved = moved;
    last = null;
    if (wasMoved) return;
    const b = e.target.closest(".pw-btn");
    if (b?.classList.contains("pw-menu")) menuBtn();
    else if (b?.classList.contains("pw-prev")) cmd("prev");
    else if (b?.classList.contains("pw-next")) cmd("next");
    else if (b?.classList.contains("pw-play")) cmd("toggle");
  });
  wheel.querySelector(".pw-centre").addEventListener("click", centre);
  let holdT = 0;
  wheel.querySelector(".pw-centre").addEventListener("pointerdown", () => { holdT = setTimeout(() => menu.alt(), 650); });
  wheel.querySelector(".pw-centre").addEventListener("pointerup", () => clearTimeout(holdT));
  addEventListener("wheel", (e) => { e.preventDefault(); turn(e.deltaY > 0 ? 1 : -1); }, { passive: false });
  addEventListener("keydown", (e) => {
    const k = e.key;
    if (k === "ArrowDown" || k === "ArrowRight") turn(1);
    else if (k === "ArrowUp" || k === "ArrowLeft") turn(-1);
    else if (k === "Enter") centre();
    else if (k === "Escape" || k === "Backspace") menuBtn();
    else if (k === " ") cmd("toggle");
    else return;
    e.preventDefault();
  });

  // Drag the iPod by its body (not the screen or wheel).
  let start = null, raf = 0, pending = null;
  body.addEventListener("pointerdown", (e) => {
    if (e.target !== body || e.button !== 0) return;
    start = { x: e.screenX, y: e.screenY };
    body.setPointerCapture(e.pointerId);
    winOp("dragStart");
  });
  body.addEventListener("pointermove", (e) => {
    if (!start) return;
    pending = { dx: Math.round(e.screenX - start.x), dy: Math.round(e.screenY - start.y) };
    if (!raf) raf = requestAnimationFrame(() => { raf = 0; winOp("dragMove", pending); });
  });
  body.addEventListener("pointerup", () => { if (start) { start = null; winOp("dragEnd"); } });

  // ---- now playing ----
  const position = () => tick.playing ? Math.min(tick.durationMs || Infinity, tick.positionMs + (Date.now() - tick.at)) : tick.positionMs;
  let artId = null;
  function drawNow() {
    const t = st?.track;
    const dur = tick.durationMs || t?.durationMs || 0;
    const pos = position();
    if (mode === "volume") {
      prog.style.setProperty("--p", `${(st?.volume || 0) * 100}%`);
      tL.textContent = ""; tR.textContent = `${Math.round((st?.volume || 0) * 100)}%`;
    } else {
      prog.style.setProperty("--p", dur ? `${(pos / dur) * 100}%` : "0%");
      tL.textContent = fmt(pos); tR.textContent = "-" + fmt(Math.max(0, dur - pos));
    }
    bar.querySelector(".pod-play-ic").innerHTML = tick.playing ? icon.play : icon.pause;
  }
  (function loop() { if (mode !== "menu") drawNow(); else bar.querySelector(".pod-play-ic").innerHTML = tick.playing ? icon.play : icon.pause; requestAnimationFrame(loop); })();

  return {
    state(s) {
      const first = !st;
      st = s;
      tick = { positionMs: s.positionMs, durationMs: s.durationMs, playing: s.playing, at: Date.now() };
      const t = s.track;
      nTitle.textContent = t?.title || "Nothing playing";
      nArtist.textContent = t?.artist || "";
      nAlbum.textContent = t?.album || "";
      nCount.textContent = t ? `${s.cur + 1} of ${s.queue.length}` : "";
      if (t?.id !== artId) {
        artId = t?.id;
        art.replaceChildren();
        if (t?.hasArt) { const img = el("img", { src: `/api/art/${t.id}`, alt: "" }); img.onerror = () => img.remove(); art.append(img); }
      }
      if (first && s.playing) setMode("now");
      drawNow();
    },
    tick(t) { tick = { ...t, at: Date.now() }; },
  };
}
