import { el, fmt, textButton, iconButton, titleBar, resizeGrip, contextMenu, slider, ask } from "./ui.js";
import { api, cmd } from "./bridge.js";
import { icon } from "./icons.js";

const SECTIONS = [
  ["artists", "Artists"], ["albums", "Albums"], ["songs", "Songs"], ["playlists", "Playlists"], ["genres", "Genres"], ["decades", "Decades"],
];
const LAYOUTS = [
  ["classic", "Classic", "Winamp-style floating windows"], ["pod", "Pod", "An iPod with a click wheel"],
  ["prism", "Prism", "Media player with a light show"], ["cover", "Cover Stage", "A phone with cover flow"],
];
const SKINS = [
  ["neon", "Neon", "Dark glass and cyan glow", ["#04060b", "#00e5ff", "#ff6a00"]],
  ["hifi", "HiFi", "Brushed aluminium, green LCD", ["#bcc1c7", "#17241b", "#8dffb4"]],
  ["pod", "Pod", "White and silver, blue highlights", ["#ffffff", "#dde1e5", "#1e6fe0"]],
  ["chrome", "Chrome Deck", "Polished chrome, amber LCD", ["#a3aab2", "#1c1307", "#ffb347"]],
];

export function libraryWindow(root) {
  const nav = el("nav", { class: "lib-nav", "aria-label": "Library sections" });
  const main = el("div", { class: "lib-main" });
  const crumbs = el("div", { class: "lib-crumbs" });
  const status = el("span", { class: "lib-status" });
  let searchT = 0;
  const search = el("input", {
    class: "lib-search", type: "search", placeholder: "Search songs, artists, albums", "aria-label": "Search the library",
    oninput: (e) => { clearTimeout(searchT); searchT = setTimeout(() => b.search(e.target.value.trim()), 180); },
  });
  root.append(
    titleBar("LIBRARY"),
    el("div", { class: "body lib-body" },
      el("div", { class: "lib-toolbar" },
        el("label", { class: "lib-search-wrap" }, el("span", { class: "lib-search-icon", html: icon.search }), search),
        status,
        iconButton("refresh", "Rescan music folders", () => api("lib/scan", {}).catch(() => {}), "flat"),
      ),
      el("div", { class: "lib-split" }, nav, el("div", { class: "lib-pane lcd" }, crumbs, main)),
      el("div", { class: "lib-foot" }, el("span", { class: "spacer" }), resizeGrip()),
    ),
  );
  const b = createBrowser({ main, crumbs, onSection: (section, query) => {
    nav.replaceChildren(
      ...SECTIONS.map(([id, label]) => el("button", {
        class: `nav-item ${section === id && !query ? "on" : ""}`, onclick: () => { search.value = ""; b.go(id); },
      }, label)),
      el("div", { class: "nav-sep" }),
      el("button", { class: `nav-item ${section === "settings" ? "on" : ""}`, onclick: () => b.go("settings") }, "Settings"),
    );
  } });
  return {
    state(s) {
      status.textContent = s.scanning ? `Scanning… ${s.songs} songs` : `${s.songs} songs`;
      b.state(s);
    },
  };
}

/**
 * The library browser (artists, albums, songs, genres, decades, playlists,
 * settings) drawn into main, with a breadcrumb in crumbs. Shared by the Library
 * window and the Prism window.
 */
export function createBrowser({ main, crumbs, onSection = () => {} }) {
  let st = null;
  let section = "albums";
  let stack = []; // drill-down views: {title, load}
  let query = "";
  const renderNav = () => onSection(section, query);

  const art = (id, cls = "art") => {
    const box = el("span", { class: cls });
    if (id) {
      const img = el("img", { src: `/api/art/${id}`, alt: "", loading: "lazy", draggable: "false" });
      img.onerror = () => img.remove();
      box.append(img);
    }
    box.insertAdjacentHTML("afterbegin", `<span class="art-ph">${icon.note}</span>`);
    return box;
  };

  function push(title, load) { stack.push({ title, load }); show(); }

  function renderCrumbs(title) {
    crumbs.replaceChildren();
    if (stack.length) {
      crumbs.append(el("button", { class: "crumb-back", onclick: () => { stack.pop(); show(); } }, "‹ Back"));
    }
    crumbs.append(el("span", { class: "crumb-title" }, title));
  }

  async function show() {
    renderNav();
    main.replaceChildren();
    main.scrollTop = 0;
    if (query) { renderCrumbs(`Search: “${query}”`); return songsView(await api(`lib/songs?q=${encodeURIComponent(query)}`), `Search: ${query}`); }
    if (stack.length) { const top = stack[stack.length - 1]; renderCrumbs(top.title); return top.load(); }
    const title = { artists: "Artists", albums: "Albums", songs: "All songs", playlists: "Playlists", genres: "Genres", decades: "Decades", settings: "Settings" }[section];
    renderCrumbs(title);
    if (section === "artists") return artistsView();
    if (section === "albums") return albumsView(await api("lib/albums"));
    if (section === "songs") return songsView(await api("lib/songs"), "All songs");
    if (section === "playlists") return playlistsView();
    if (section === "genres") return genresView();
    if (section === "decades") return decadesView();
    if (section === "settings") return settingsView();
  }

  async function artistsView() {
    const rows = await api("lib/artists");
    if (!rows.length) return empty();
    main.append(el("div", { class: "rows" }, rows.map((a) => el("button", {
      class: "row-btn", onclick: () => push(a.name, async () => artistView(a.name)),
    }, art(a.artId, "art sm"), el("span", { class: "rb-main" }, el("span", { class: "rb-title" }, a.name),
      el("span", { class: "rb-sub" }, `${a.albums} album${a.albums === 1 ? "" : "s"} · ${a.songs} songs`))))));
  }

  async function artistView(name) {
    const [albums, songs] = await Promise.all([api(`lib/albums?artist=${encodeURIComponent(name)}`), api(`lib/songs?artist=${encodeURIComponent(name)}`)]);
    albumsView(albums);
    main.append(el("h3", { class: "lib-h" }, "Songs"));
    songsView(songs, name, false);
  }

  function albumsView(albums) {
    if (!albums.length) return empty();
    main.append(el("div", { class: "grid" }, albums.map((a) => el("button", {
      class: "tile", title: `${a.title} — ${a.artist}`,
      onclick: () => push(a.title, async () => albumView(a)),
      ondblclick: async () => { const s = await api(`lib/songs?album=${encodeURIComponent(a.key)}`); play(s, 0, a.title); },
    }, art(a.artId, "art lg"), el("span", { class: "tile-title" }, a.title),
      el("span", { class: "tile-sub" }, [a.artist, a.year || null].filter(Boolean).join(" · "))))));
  }

  async function albumView(a) {
    const songs = await api(`lib/songs?album=${encodeURIComponent(a.key)}`);
    const total = songs.reduce((s, t) => s + t.durationMs, 0);
    main.append(el("div", { class: "album-head" }, art(a.artId, "art xl"),
      el("div", { class: "album-meta" },
        el("div", { class: "album-title" }, a.title),
        el("div", { class: "album-sub" }, [a.artist, a.year || null, `${songs.length} songs`, fmt(total)].filter(Boolean).join(" · ")),
        actions(songs, a.title))));
    songsView(songs, a.title, false, true);
  }

  async function playlistsView() {
    const rows = await api("playlists");
    main.append(el("div", { class: "lib-actions" },
      textButton("NEW PLAYLIST", "Make an empty playlist", async () => { const n = await ask("Name of the new playlist", { input: true, ok: "CREATE" }); if (n) { await api("playlists", { name: n, ids: [] }); show(); } }, "accent"),
      textButton("SAVE QUEUE", "Save what's in the playlist window as a playlist", async () => {
        const n = await ask("Save the current queue as", { input: true, ok: "SAVE" }); if (n && st?.queue?.length) { await api("playlists", { name: n, ids: st.queue.map((q) => q.id) }); show(); }
      })));
    if (!rows.length) return empty("No playlists yet. Save the queue, or right-click songs and choose Add to playlist.");
    main.append(el("div", { class: "rows" }, rows.map((p) => {
      const r = el("button", { class: "row-btn", onclick: () => push(p.name, async () => playlistView(p.id)) },
        art(p.artId, "art sm"), el("span", { class: "rb-main" }, el("span", { class: "rb-title" }, p.name),
          el("span", { class: "rb-sub" }, `${p.songs} songs · ${fmt(p.durationMs)}`)));
      r.addEventListener("contextmenu", (e) => contextMenu(e.clientX, e.clientY, [
        { label: "Rename…", run: async () => { const n = await ask("Rename the playlist", { input: true, value: p.name, ok: "RENAME" }); if (n) { await api(`playlists/${p.id}/rename`, { name: n }); show(); } } },
        { label: "Delete playlist", run: async () => { if (await ask(`Delete the playlist “${p.name}”? The songs stay in your library.`, { ok: "DELETE", danger: true })) { await api(`playlists/${p.id}/delete`, {}); show(); } } },
      ]));
      return r;
    })));
  }

  async function playlistView(id) {
    const { playlist, tracks } = await api(`playlists/${id}`);
    main.append(el("div", { class: "album-meta pl-meta" }, el("div", { class: "album-title" }, playlist.name),
      el("div", { class: "album-sub" }, `${tracks.length} songs · ${fmt(tracks.reduce((s, t) => s + t.durationMs, 0))}`)));
    songsView(tracks, playlist.name, true, false, id);
  }

  async function genresView() {
    const rows = await api("lib/genres");
    if (!rows.length) return empty("No genres yet. Songs without a genre tag don't appear here.");
    main.append(el("div", { class: "rows" }, rows.map((g) => el("button", {
      class: "row-btn", onclick: () => push(g.name, async () => songsView(await api(`lib/songs?genre=${encodeURIComponent(g.name)}`), g.name)),
    }, el("span", { class: "rb-main" }, el("span", { class: "rb-title" }, g.name), el("span", { class: "rb-sub" }, `${g.songs} songs`))))));
  }

  async function decadesView() {
    const rows = await api("lib/decades");
    if (!rows.length) return empty("No years in the tags yet.");
    main.append(el("div", { class: "rows" }, rows.map((d) => el("button", {
      class: "row-btn", onclick: () => push(`${d}s`, async () => songsView(await api(`lib/songs?decade=${d}`), `${d}s`)),
    }, el("span", { class: "rb-main" }, el("span", { class: "rb-title" }, `${d}s`))))));
  }

  function actions(songs, source) {
    const ids = songs.map((t) => t.id);
    return el("div", { class: "lib-actions" },
      textButton("PLAY", "Play these songs", () => play(songs, 0, source), "accent"),
      textButton("PLAY NEXT", "Play right after the current song", () => cmd("enqueue", { ids, next: true })),
      textButton("ADD TO END", "Add after everything already lined up", () => cmd("enqueue", { ids, next: false })),
      textButton("+ PLAYLIST", "Add these songs to a playlist", (e) => { const r = e.currentTarget.getBoundingClientRect(); addToPlaylist(ids, r.left, r.bottom + 2); }),
    );
  }

  function play(songs, i, source) { cmd("playTracks", { ids: songs.map((t) => t.id), i, source }); }

  async function addToPlaylist(ids, x, y) {
    const lists = await api("playlists");
    contextMenu(x, y, [
      { label: "New playlist…", run: async () => { const n = await ask("Name of the new playlist", { input: true, ok: "CREATE" }); if (n) await api("playlists", { name: n, ids }); } },
      ...(lists.length ? ["-"] : []),
      ...lists.map((p) => ({ label: p.name, run: async () => { const r = await api(`playlists/${p.id}/add`, { ids }); toast(r.added ? `Added to ${p.name}${r.already ? ` · ${r.already} already there` : ""}` : `Already in ${p.name}`); } })),
    ]);
  }

  function songsView(songs, source, withActions = true, albumMode = false, playlistId = null) {
    if (!songs.length) return empty(query ? "Nothing matches that search." : undefined);
    if (withActions) main.append(actions(songs, source));
    const playingId = st?.track?.id;
    main.append(el("div", { class: "songs", role: "list" }, songs.map((t, i) => {
      const r = el("div", { class: `song ${t.id === playingId ? "cur" : ""}`, role: "listitem", tabindex: "0", title: t.path, "data-id": t.id },
        el("span", { class: "s-n" }, albumMode && t.trackNo ? String(t.trackNo) : String(i + 1)),
        el("span", { class: "s-t" }, el("span", { class: "s-title" }, t.title),
          albumMode ? null : el("span", { class: "s-sub" }, `${t.artist} · ${t.album}`)),
        el("span", { class: "s-codec" }, t.codec + (t.bitDepth ? ` ${t.bitDepth}` : "")),
        el("span", { class: "s-dur" }, fmt(t.durationMs)));
      r.addEventListener("dblclick", () => play(songs, i, source));
      r.addEventListener("keydown", (e) => { if (e.key === "Enter") play(songs, i, source); });
      r.addEventListener("contextmenu", (e) => contextMenu(e.clientX, e.clientY, [
        { label: "Play from here", run: () => play(songs, i, source) },
        { label: "Play next", run: () => cmd("enqueue", { ids: [t.id], next: true }) },
        { label: "Add to end of queue", run: () => cmd("enqueue", { ids: [t.id], next: false }) },
        "-",
        { label: "Add to playlist…", run: () => addToPlaylist([t.id], e.clientX, e.clientY) },
        ...(playlistId ? [{ label: "Remove from this playlist", run: async () => { await api(`playlists/${playlistId}/remove`, { i }); show(); } }] : []),
      ]));
      return r;
    })));
  }

  function empty(msg) {
    const none = !st || st.songs === 0;
    main.append(el("div", { class: "lib-empty" },
      el("div", { class: "lib-empty-title" }, none ? (st?.scanning ? "Scanning your music…" : "No music yet") : "Nothing here"),
      el("div", {}, msg || (none ? "Add the folders where your FLAC and MP3 files live in Settings." : "")),
      none && !st?.scanning ? textButton("OPEN SETTINGS", "Add music folders", () => { section = "settings"; stack = []; show(); }, "accent") : null));
  }

  async function settingsView() {
    const folders = await api("lib/folders");
    const list = el("div", { class: "folders" }, folders.length ? folders.map((f) => el("div", { class: "folder" },
      el("span", { class: "folder-ic", html: icon.folder }), el("span", { class: "folder-path" }, f),
      iconButton("close", `Stop watching ${f}`, async () => { await api("lib/folders", { folders: folders.filter((x) => x !== f) }); show(); }, "flat small"))) :
      el("div", { class: "muted" }, "No folders yet."));
    const pathInput = el("input", { class: "path-input", placeholder: "C:\\Users\\you\\Music", "aria-label": "Folder path" });
    const manual = el("div", { class: "manual", hidden: true }, pathInput,
      textButton("ADD", "Add this folder", async () => { if (pathInput.value.trim()) { await api("lib/folders", { folders: [...folders, pathInput.value.trim()] }); show(); } }));
    const add = textButton("ADD FOLDER…", "Choose a folder with music", async () => {
      try { await api("lib/folders", { pick: true }); show(); } catch { manual.hidden = false; pathInput.focus(); }
    }, "accent");
    const xf = slider({ label: "Crossfade", cls: "xf", onChange: (v) => cmd("crossfade", { ms: Math.round(v * 12) * 1000 }) });
    xf.set((st?.crossfadeMs || 0) / 12000);
    const xfLabel = el("span", { class: "xf-label" }, st?.crossfadeMs ? `${st.crossfadeMs / 1000} s` : "Off (gapless)");
    xf.addEventListener("pointerup", () => setTimeout(show, 150));
    const about = await api("about").catch(() => ({ version: "" }));

    main.append(
      el("section", { class: "set" }, el("h3", { class: "lib-h" }, "Music folders"),
        el("p", { class: "muted" }, "Kororythm watches these folders and adds new FLAC and MP3 files on its own."),
        list, el("div", { class: "set-row" }, add, textButton("RESCAN", "Read every folder again", () => api("lib/scan", {}))), manual),
      el("section", { class: "set" }, el("h3", { class: "lib-h" }, "Layout"),
        el("div", { class: "skins" }, LAYOUTS.map(([id, name, blurb]) => el("button", {
          class: `skin-card ${st?.layout === id ? "on" : ""}`, "aria-pressed": String(st?.layout === id),
          onclick: () => cmd("layout", { name: id }),
        }, el("span", { class: "layout-ic", html: icon[`l_${id}`] || "" }), el("span", { class: "skin-name" }, name), el("span", { class: "skin-blurb" }, blurb))))),
      el("section", { class: "set" }, el("h3", { class: "lib-h" }, "Skin"),
        el("div", { class: "skins" }, SKINS.map(([id, name, blurb, sw]) => el("button", {
          class: `skin-card ${st?.skin === id ? "on" : ""}`, "aria-pressed": String(st?.skin === id),
          onclick: () => cmd("skin", { name: id }),
        }, el("span", { class: "swatches" }, sw.map((c) => el("span", { class: "sw", style: `background:${c}` }))),
          el("span", { class: "skin-name" }, name), el("span", { class: "skin-blurb" }, blurb))))),
      el("section", { class: "set" }, el("h3", { class: "lib-h" }, "Playback"),
        el("div", { class: "set-row" }, el("span", { class: "set-label" }, "Crossfade"), xf, xfLabel),
        el("label", { class: "set-row check" },
          el("input", { type: "checkbox", checked: !!st?.alwaysOnTop, onchange: (e) => cmd("alwaysTop", { on: e.target.checked }) }),
          "Keep Kororythm's windows above other windows")),
      el("section", { class: "set" }, el("h3", { class: "lib-h" }, "About"),
        el("p", { class: "muted" }, `Kororythm for Windows ${about.version} · GPL-3.0`)),
    );
  }

  function toast(msg) {
    const t = el("div", { class: "toast", role: "status" }, msg);
    document.body.append(t);
    setTimeout(() => t.remove(), 2200);
  }

  let lastSongs = -1;
  return {
    go(sec) { section = sec; stack = []; query = ""; show(); },
    search(q) { query = q; show(); },
    state(s) {
      const first = !st;
      const prev = st;
      st = s;
      if (first || s.songs !== lastSongs || (section === "settings" && (prev?.skin !== s.skin || prev?.layout !== s.layout))) { lastSongs = s.songs; show(); }
      const playingId = s.track?.id;
      main.querySelectorAll(".song").forEach((r) => r.classList.toggle("cur", r.dataset.id === playingId));
    },
  };
}
