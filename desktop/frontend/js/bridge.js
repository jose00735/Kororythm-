// Talks to the Go side. Commands go over fetch (served by Wails' asset handler in
// the Windows app, or by the developer server). Live events come over a Wails
// stream in the app, or Server-Sent Events in a browser.

export async function api(path, body) {
  const opt = body === undefined ? {} : {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  };
  const r = await fetch("/api/" + path, opt);
  if (!r.ok) throw new Error((await r.text()) || r.statusText);
  return r.json();
}

export const cmd = (op, args = {}) => api("cmd", { op, ...args }).catch((e) => console.warn(op, e));
export const winOp = (op, args = {}) => api("win", { op, name: WIN, ...args }).catch(() => {});

export const WIN = new URLSearchParams(location.search).get("w") || "player";

const handlers = {};
export function on(kind, fn) { (handlers[kind] ||= []).push(fn); }
function dispatch(msg) { for (const fn of handlers[msg.t] || []) fn(msg.d); }

export async function connect() {
  // Inside the Windows app (WebView2) the Wails runtime is served at /wails/runtime.js.
  if (window.chrome?.webview) try {
    const rt = await import("/wails/runtime.js");
    if (rt && rt.JSONStream) {
      const s = rt.JSONStream("events");
      s.onmessage = (ev) => dispatch(ev.data);
      s.onclose = () => setTimeout(connect, 1000);
      return;
    }
  } catch (_) { /* not running inside Wails */ }
  const es = new EventSource("/api/events");
  es.onmessage = (ev) => dispatch(JSON.parse(ev.data));
}
