// Downloads window: paste a link, fetch it (stoppable), choose songs of a
// playlist, save them as tagged MP3s into the library, with a verbose LOG.
import { el, fmt, textButton, titleBar, resizeGrip } from "./ui.js";
import { api, on } from "./bridge.js";

export function downloadsWindow(root) {
  let st = null;
  const chosen = new Set();
  let format = "mp3v0";
  const logLines = [];

  const url = el("input", { class: "dl-url", type: "url", placeholder: "Paste a YouTube (or other) link", "aria-label": "Link to download",
    onkeydown: (e) => { if (e.key === "Enter") fetchLink(); } });
  const fetchBtn = textButton("FETCH", "Read what the link contains", () => fetchLink(), "accent");
  const stopBtn = textButton("STOP", "Stop fetching or downloading", () => api("dl/stop", {}));
  const toolsBox = el("div", { class: "dl-tools" });
  const probeBox = el("div", { class: "dl-probe" });
  const jobsBox = el("div", { class: "dl-jobs" });
  const logBox = el("pre", { class: "dl-log", "aria-label": "Download log" });
  const tabs = el("div", { class: "dl-tabs" },
    el("button", { class: "dl-tab on", onclick: (e) => tab(e, "jobs") }, "DOWNLOADS"),
    el("button", { class: "dl-tab", onclick: (e) => tab(e, "log") }, "LOG"));

  root.append(titleBar("DOWNLOADS"),
    el("div", { class: "body dl-body" },
      toolsBox,
      el("div", { class: "dl-row" }, url, fetchBtn, stopBtn),
      probeBox,
      tabs,
      el("div", { class: "lcd dl-out" }, jobsBox, logBox),
      el("div", { class: "dl-foot" }, el("span", { class: "dl-folder" }), el("span", { class: "spacer" }),
        textButton("CLEAR", "Clear finished downloads", () => api("dl/clear", {})), resizeGrip())));
  logBox.hidden = true;

  function tab(e, which) {
    tabs.querySelectorAll(".dl-tab").forEach((b) => b.classList.toggle("on", b === e.currentTarget));
    jobsBox.hidden = which !== "jobs";
    logBox.hidden = which !== "log";
    if (which === "log") logBox.scrollTop = logBox.scrollHeight;
  }
  function fetchLink() {
    const u = url.value.trim();
    if (!u) return url.focus();
    chosen.clear();
    api("dl/probe", { url: u });
  }

  function render() {
    if (!st) return;
    const tl = st.tools;
    toolsBox.replaceChildren();
    if (!tl.ytdlp || !tl.ffmpeg) {
      toolsBox.append(el("div", { class: "dl-note" },
        el("div", { class: "dl-note-t" }, "Downloads need yt-dlp and ffmpeg"),
        el("div", {}, tl.installing ? `Getting ${tl.step || "tools"}… ${Math.round((tl.progress || 0) * 100)}%`
          : "Kororythm can fetch both from their official GitHub releases (about 120 MB, once)."),
        tl.installing ? el("div", { class: "bar" }, el("span", { style: `width:${(tl.progress || 0) * 100}%` }))
          : textButton("GET THE TOOLS", "Download yt-dlp and ffmpeg", () => api("dl/tools", {}), "accent"),
        tl.error ? el("div", { class: "dl-err" }, tl.error) : null));
    }
    fetchBtn.disabled = st.probing || !tl.ytdlp;
    stopBtn.disabled = !st.probing && !st.running;
    probeBox.replaceChildren();
    if (st.probing) probeBox.append(el("div", { class: "dl-probing" }, el("span", { class: "spin" }), "Reading the link… (STOP to cancel)"));
    if (st.error) probeBox.append(el("div", { class: "dl-err" }, st.error));
    const p = st.probe;
    if (p) {
      if (!chosen.size) p.entries.forEach((e) => chosen.add(e.id));
      const list = el("div", { class: "dl-entries" }, p.entries.map((e) => el("label", { class: "dl-entry" },
        el("input", { type: "checkbox", checked: chosen.has(e.id), onchange: (ev) => { ev.target.checked ? chosen.add(e.id) : chosen.delete(e.id); countLabel(); } }),
        el("span", { class: "dl-e-t" }, e.title), el("span", { class: "dl-e-u" }, e.uploader || ""), el("span", { class: "dl-e-d" }, e.duration ? fmt(e.duration * 1000) : ""))));
      const count = el("span", { class: "dl-count" });
      const countLabel = () => { count.textContent = `${chosen.size} of ${p.entries.length} chosen`; };
      countLabel();
      probeBox.append(
        el("div", { class: "dl-p-head" }, el("span", { class: "dl-p-title" }, p.playlist ? `Playlist: ${p.title}` : p.title), el("span", { class: "spacer" }), count),
        p.playlist ? el("div", { class: "dl-sel" },
          textButton("ALL", "Choose every song", () => { p.entries.forEach((e) => chosen.add(e.id)); render(); }),
          textButton("NONE", "Choose no songs", () => { chosen.clear(); render(); })) : null,
        p.playlist ? list : null,
        el("div", { class: "dl-row" },
          el("label", { class: "dl-fmt" }, el("input", { type: "radio", name: "fmt", checked: format === "mp3v0", onchange: () => { format = "mp3v0"; } }), "MP3 · VBR V0 (~245 kbps)"),
          el("label", { class: "dl-fmt" }, el("input", { type: "radio", name: "fmt", checked: format === "mp3320", onchange: () => { format = "mp3320"; } }), "MP3 · 320 kbps"),
          el("span", { class: "spacer" }),
          textButton("DOWNLOAD", "Save the chosen songs to your library", () => api("dl/start", { ids: [...chosen], format }).catch((e) => alertIn(e.message)), "accent")));
    }
    const jobs = st.jobs || [];
    jobsBox.replaceChildren(...(jobs.length ? jobs.slice().reverse().map((j) => el("div", { class: `dl-job ${j.status}` },
      el("div", { class: "dl-job-top" }, el("span", { class: "dl-job-t" }, j.title), el("span", { class: "dl-job-s" }, label(j))),
      el("div", { class: "bar" }, el("span", { style: `width:${Math.round(j.progress * 100)}%` })),
      j.error ? el("div", { class: "dl-err" }, j.error) : null)) :
      [el("div", { class: "dl-empty" }, "Finished songs appear in the library on their own.")]));
    root.querySelector(".dl-folder").textContent = `Saves to ${st.folder}`;
  }
  const label = (j) => ({ queued: "Waiting", downloading: `${Math.round(j.progress * 100)}%`, converting: "Tagging…", done: "Saved", failed: "Failed", stopped: "Stopped" }[j.status] || j.status);
  function alertIn(msg) { probeBox.append(el("div", { class: "dl-err" }, msg)); }

  on("dl", (s) => { st = s; render(); });
  on("dlLog", (line) => {
    logLines.push(line);
    if (logLines.length > 800) logLines.splice(0, logLines.length - 800);
    const atEnd = logBox.scrollTop + logBox.clientHeight >= logBox.scrollHeight - 8;
    logBox.textContent = logLines.join("\n");
    if (atEnd) logBox.scrollTop = logBox.scrollHeight;
  });
  api("dl").then((s) => { st = s; render(); }).catch(() => {});
  return {};
}
