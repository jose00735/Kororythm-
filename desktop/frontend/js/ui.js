import { icon, parrot } from "./icons.js";
import { winOp, WIN, api } from "./bridge.js";

const cmdLayout = (name) => api("cmd", { op: "layout", name });

export function el(tag, attrs = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === "class") e.className = v;
    else if (k === "html") e.innerHTML = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? "" : v);
  }
  for (const k of kids.flat()) if (k != null) e.append(k);
  return e;
}

export const fmt = (ms) => {
  if (!ms || ms < 0) ms = 0;
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, "0");
  return h ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
};

/** A button with a drawn icon (and an accessible name). */
export function iconButton(name, label, onClick, cls = "") {
  return el("button", { class: `btn icon ${cls}`, title: label, "aria-label": label, html: icon[name], onclick: onClick });
}

/** A text button, e.g. EQ / PL / LIB toggles. */
export function textButton(text, label, onClick, cls = "") {
  return el("button", { class: `btn text ${cls}`, title: label, "aria-label": label, onclick: onClick }, text);
}

/**
 * A slider drawn as a track and a thumb. value is 0..1. While the user drags,
 * outside updates are ignored so the thumb does not jump.
 */
export function slider({ vertical = false, label, onInput, onChange, cls = "" }) {
  const thumb = el("span", { class: "thumb" });
  const fill = el("span", { class: "fill" });
  const root = el("div", {
    class: `slider ${vertical ? "v" : "h"} ${cls}`, role: "slider", tabindex: "0",
    "aria-label": label, "aria-valuemin": "0", "aria-valuemax": "100",
  }, el("span", { class: "track" }, fill), thumb);
  let value = 0, dragging = false;
  const draw = () => {
    const p = `${value * 100}%`;
    if (vertical) { thumb.style.bottom = p; fill.style.height = p; } else { thumb.style.left = p; fill.style.width = p; }
    root.setAttribute("aria-valuenow", String(Math.round(value * 100)));
  };
  const fromEvent = (e) => {
    const r = root.getBoundingClientRect();
    const v = vertical ? 1 - (e.clientY - r.top) / r.height : (e.clientX - r.left) / r.width;
    return Math.max(0, Math.min(1, v));
  };
  root.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    e.stopPropagation();
    dragging = true;
    root.setPointerCapture(e.pointerId);
    value = fromEvent(e); draw(); onInput?.(value);
  });
  root.addEventListener("pointermove", (e) => { if (dragging) { value = fromEvent(e); draw(); onInput?.(value); } });
  const end = () => { if (dragging) { dragging = false; onChange?.(value); } };
  root.addEventListener("pointerup", end);
  root.addEventListener("pointercancel", end);
  root.addEventListener("keydown", (e) => {
    const step = e.shiftKey ? 0.1 : 0.02;
    const d = { ArrowRight: step, ArrowUp: step, ArrowLeft: -step, ArrowDown: -step }[e.key];
    if (d === undefined) return;
    e.preventDefault();
    value = Math.max(0, Math.min(1, value + d)); draw(); onInput?.(value); onChange?.(value);
  });
  root.set = (v) => { if (!dragging) { value = Math.max(0, Math.min(1, v || 0)); draw(); } };
  root.dragging = () => dragging;
  draw();
  return root;
}

/** Scrolling text for long song titles, like Winamp's display. */
export function marquee(cls = "") {
  const inner = el("span", { class: "mq-inner" });
  const root = el("div", { class: `marquee ${cls}` }, inner);
  let text = "";
  root.set = (t) => {
    if (t === text) return;
    text = t;
    inner.textContent = t;
    inner.classList.remove("run");
    requestAnimationFrame(() => {
      const over = inner.scrollWidth - root.clientWidth;
      if (over > 4) {
        inner.textContent = `${t}   ***   ${t}   ***   `;
        inner.style.setProperty("--mq-dur", `${Math.max(8, t.length * 0.32)}s`);
        inner.classList.add("run");
      }
    });
  };
  return root;
}

/**
 * The title strip: drag to move (snapping happens in Go), double-click to
 * shade, and minimise / shade / close buttons.
 */
export function titleBar(title, { closable = true } = {}) {
  const logo = el("button", { class: "tb-logo", html: parrot, title: "Layouts, skins and more", "aria-label": "Kororythm menu" });
  logo.addEventListener("click", () => {
    const r = logo.getBoundingClientRect();
    contextMenu(r.left, r.bottom + 2, [
      ...[["classic", "Classic"], ["pod", "Pod"], ["prism", "Prism"], ["cover", "Cover Stage"]].map(([id, n], i) => ({ label: `Layout: ${n}  (Ctrl+${i + 1})`, run: () => cmdLayout(id) })),
      "-",
      ...[["neon", "Neon"], ["hifi", "HiFi"], ["pod", "Pod"], ["chrome", "Chrome Deck"]].map(([id, n]) => ({ label: `Skin: ${n}`, run: () => api("cmd", { op: "skin", name: id }) })),
      "-",
      { label: "iPod browser  (Ctrl+B)", run: () => winOp("toggle", { name: "pod" }) },
      { label: "Library  (Ctrl+L)", run: () => winOp("toggle", { name: "library" }) },
      { label: "Downloads  (Ctrl+D)", run: () => winOp("toggle", { name: "downloads" }) },
    ]);
  });
  const bar = el("div", { class: "titlebar" },
    logo,
    el("span", { class: "tb-title" }, title),
    el("span", { class: "tb-rule" }),
    el("span", { class: "tb-buttons" },
      WIN === "player" ? iconButton("min", "Minimise", () => winOp("minimize"), "tb") : null,
      iconButton("shade", "Shade (show only this strip)", () => winOp("shade"), "tb"),
      closable ? iconButton("close", WIN === "player" ? "Quit Kororythm" : "Close window", () => winOp("close"), "tb") : null,
    ),
  );
  let start = null, pending = null, raf = 0;
  bar.addEventListener("pointerdown", (e) => {
    if (e.target.closest("button") || e.button !== 0) return;
    start = { x: e.screenX, y: e.screenY };
    bar.setPointerCapture(e.pointerId);
    winOp("dragStart");
  });
  bar.addEventListener("pointermove", (e) => {
    if (!start) return;
    pending = { dx: Math.round(e.screenX - start.x), dy: Math.round(e.screenY - start.y) };
    if (!raf) raf = requestAnimationFrame(() => { raf = 0; if (pending) winOp("dragMove", pending); });
  });
  const end = () => { if (start) { start = null; winOp("dragEnd"); } };
  bar.addEventListener("pointerup", end);
  bar.addEventListener("pointercancel", end);
  bar.addEventListener("dblclick", (e) => { if (!e.target.closest("button")) winOp("shade"); });
  return bar;
}

/** Bottom-right corner grip that resizes the window. */
export function resizeGrip() {
  const g = el("div", { class: "grip", title: "Resize", html: icon.grip });
  let start = null;
  g.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    start = { x: e.screenX, y: e.screenY, w: window.innerWidth, h: window.innerHeight };
    g.setPointerCapture(e.pointerId);
  });
  g.addEventListener("pointermove", (e) => {
    if (!start) return;
    winOp("resize", { w: Math.round(start.w + e.screenX - start.x), h: Math.round(start.h + e.screenY - start.y) });
  });
  g.addEventListener("pointerup", () => { start = null; });
  return g;
}

/** A small right-click menu. items: [{label, run}] or "-" for a divider. */
export function contextMenu(x, y, items) {
  document.querySelector(".ctx")?.remove();
  const m = el("div", { class: "ctx", role: "menu" },
    items.map((it) => it === "-" ? el("div", { class: "ctx-sep" }) :
      el("button", { class: "ctx-item", role: "menuitem", onclick: () => { m.remove(); it.run(); } }, it.label)));
  document.body.append(m);
  const r = m.getBoundingClientRect();
  m.style.left = `${Math.min(x, innerWidth - r.width - 4)}px`;
  m.style.top = `${Math.min(y, innerHeight - r.height - 4)}px`;
  const close = (e) => { if (!m.contains(e.target)) { m.remove(); removeEventListener("pointerdown", close, true); } };
  setTimeout(() => addEventListener("pointerdown", close, true));
  m.querySelector("button")?.focus();
}

/**
 * A small in-window dialog (instead of the browser's prompt/confirm).
 * With input = true it returns the typed text, else true/false.
 */
export function ask(message, { input = false, value = "", ok = "OK", danger = false } = {}) {
  return new Promise((resolve) => {
    const field = input ? el("input", { class: "ask-input", value, "aria-label": message }) : null;
    const done = (v) => { back.remove(); resolve(v); };
    const okBtn = textButton(ok, ok, () => done(input ? field.value.trim() || null : true), danger ? "danger" : "accent");
    const box = el("div", { class: "ask", role: "dialog", "aria-modal": "true", "aria-label": message },
      el("div", { class: "ask-msg" }, message), field,
      el("div", { class: "ask-row" }, textButton("CANCEL", "Cancel", () => done(input ? null : false)), okBtn));
    const back = el("div", { class: "ask-back" }, box);
    back.addEventListener("keydown", (e) => {
      if (e.key === "Escape") done(input ? null : false);
      if (e.key === "Enter") okBtn.click();
    });
    document.body.append(back);
    (field || okBtn).focus();
    field?.select();
  });
}
