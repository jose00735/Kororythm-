import { connect, on, api, WIN } from "./bridge.js";
import { playerWindow } from "./win-player.js";
import { eqWindow } from "./win-eq.js";
import { playlistWindow } from "./win-playlist.js";
import { libraryWindow } from "./win-library.js";
import { podWindow } from "./win-pod.js";
import { prismWindow } from "./win-prism.js";
import { coverWindow } from "./win-cover.js";
import { downloadsWindow } from "./win-downloads.js";
import { syncWindow } from "./win-sync.js";
import { cmd, winOp } from "./bridge.js";

const builders = {
  player: playerWindow, eq: eqWindow, playlist: playlistWindow, library: libraryWindow,
  pod: podWindow, prism: prismWindow, cover: coverWindow, downloads: downloadsWindow, sync: syncWindow,
};
const root = document.getElementById("win");
root.classList.add(`w-${WIN}`);
const view = (builders[WIN] || playerWindow)(root);

const apply = (st) => {
  document.body.dataset.skin = st.skin || "neon";
  view.state?.(st);
};
on("state", apply);
on("tick", (t) => view.tick?.(t));
on("bars", (b) => view.bars?.(b));
on("win", (w) => {
  if (w._?.layout) document.body.dataset.layout = w._.layout;
  const me = w[WIN];
  if (me) root.classList.toggle("shaded", !!me.shaded);
  view.wins?.(w);
});

api("state").then(apply).catch(() => {});
api("win").then((w) => { root.classList.toggle("shaded", !!w[WIN]?.shaded); view.wins?.(w); }).catch(() => {});
connect();

// Shortcuts that work in every window.
const LAYOUT_KEYS = { "1": "classic", "2": "pod", "3": "prism", "4": "cover" };
addEventListener("keydown", (e) => {
  if (!e.ctrlKey || e.target.closest?.("input")) return;
  const k = e.key.toLowerCase();
  if (k === "b") winOp("toggle", { name: "pod" });
  else if (k === "l") winOp("toggle", { name: "library" });
  else if (k === "d") winOp("toggle", { name: "downloads" });
  else if (k === "y") winOp("toggle", { name: "sync" });
  else if (LAYOUT_KEYS[k]) cmd("layout", { name: LAYOUT_KEYS[k] });
  else return;
  e.preventDefault();
});

// Stop the WebView's own context menu and text selection outside inputs.
addEventListener("contextmenu", (e) => { if (!e.target.closest("input")) e.preventDefault(); });
