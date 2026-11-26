import { el, fmt, textButton, iconButton, titleBar, resizeGrip, contextMenu, slider } from "./ui.js";
import { api, cmd } from "./bridge.js";
import { icon } from "./icons.js";

const SECTIONS = [
  ["artists", "Artists"], ["albums", "Albums"], ["songs", "Songs"], ["genres", "Genres"], ["decades", "Decades"],
];
const SKINS = [
  ["neon", "Neon", "Dark glass and cyan glow", ["#04060b", "#00e5ff", "#ff6a00"]],
  ["hifi", "HiFi", "Brushed aluminium, green LCD", ["#bcc1c7", "#17241b", "#8dffb4"]],
  ["pod", "Pod", "White and silver, blue highlights", ["#ffffff", "#dde1e5", "#1e6fe0"]],
  ["chrome", "Chrome Deck", "Polished chrome, amber LCD", ["#a3aab2", "#1c1307", "#ffb347"]],
];

export function libraryWindow(root) {
  let st = null;
  let section = "albums";
  let stack = []; // drill-down views: {title, load}
  let query = "";
  let searchT = 0;

  const nav = el("nav", { class: "lib-nav", "aria-label": "Library sections" });
  const main = el("div", { class: "lib-main" });
  const crumbs = el("div", { class: "lib-crumbs" });
  const status = el("span", { class: "lib-status" });
  const search = el("input", {
    class: "lib-search", type: "search", placeholder: "Search songs, artists, albums", "aria-label": "Search the library",
    oninput: (e) => { query = e.target.value.trim(); clearTimeout(searchT); searchT = setTimeout(show, 180); },
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

  function renderNav() {
    nav.replaceChildren(
      ...SECTIONS.map(([id, label]) => el("button", {
        class: `nav-item ${section === id && !query ? "on" : ""}`, onclick: () => { section = id; stack = []; query = ""; search.value = ""; show(); },
      }, label)),
      el("div", { class: "nav-sep" }),
      el("button", { class: `nav-item ${section === "settings" ? "on" : ""}`, onclick: () => { section = "settings"; stack = []; show(); } }, "Settings"),
    );
  }

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
    const title = { artists: "Artists", albums: "Albums", songs: "All songs", genres: "Genres", decades: "Decades", settings: "Settings" }[section];
    renderCrumbs(title);
    if (section === "artists") return artistsView();
    if (section === "albums") return albumsView(await api("lib/albums"));
    if (section === "songs") return songsView(await api("lib/songs"), "All songs");
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
    );
  }

  function play(songs, i, source) { cmd("playTracks", { ids: songs.map((t) => t.id), i, source }); }

  function songsView(songs, source, withActions = true, albumMode = false) {
    if (!songs.length) return empty(query ? "Nothing matches that search." : undefined);
    if (withActions) main.append(actions(songs, source));
    const playingId = st?.track?.id;
    main.append(el("div", { class: "songs", role: "list" }, songs.map((t, i) => {
      const r = el("div", { class: `song ${t.id === playingId ? "cur" : ""}`, role: "listitem", tabindex: "0", title: t.path },
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

  let lastSongs = -1;
  return {
    state(s) {
      const first = !st;
      const prevSkin = st?.skin;
      st = s;
      status.textContent = s.scanning ? `Scanning… ${s.songs} songs` : `${s.songs} songs`;
      if (first || s.songs !== lastSongs || (section === "settings" && prevSkin !== s.skin)) { lastSongs = s.songs; show(); }
      main.querySelectorAll(".song").forEach((r) => r.classList.remove("cur"));
    },
  };
}
