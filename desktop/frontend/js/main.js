import { connect, on, api, WIN } from "./bridge.js";
import { playerWindow } from "./win-player.js";
import { eqWindow } from "./win-eq.js";
import { playlistWindow } from "./win-playlist.js";
import { libraryWindow } from "./win-library.js";

const builders = { player: playerWindow, eq: eqWindow, playlist: playlistWindow, library: libraryWindow };
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
  const me = w[WIN];
  if (me) root.classList.toggle("shaded", !!me.shaded);
  view.wins?.(w);
});

api("state").then(apply).catch(() => {});
api("win").then((w) => { root.classList.toggle("shaded", !!w[WIN]?.shaded); view.wins?.(w); }).catch(() => {});
connect();

// Stop the WebView's own context menu and text selection outside inputs.
addEventListener("contextmenu", (e) => { if (!e.target.closest("input")) e.preventDefault(); });
