package com.koro.kororythm.ui.downloads

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koro.kororythm.downloads.DlFormat
import com.koro.kororythm.downloads.MusicStore
import com.koro.kororythm.downloads.YtLogic
import com.koro.kororythm.playback.PlayerViewModel
import com.koro.kororythm.ui.common.ArtThumb
import com.koro.kororythm.ui.common.ChoiceCard
import com.koro.kororythm.ui.common.NeonScreen
import com.koro.kororythm.ui.common.NeonTextField
import com.koro.kororythm.ui.player.formatTime
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder
import java.util.UUID

open class DlCallbacks {
    open fun close() {}
    open fun link(s: String) {}
    open fun paste() {}
    open fun fetch() {}
    open fun title(s: String) {}
    open fun artist(s: String) {}
    open fun album(s: String) {}
    open fun format(f: DlFormat) {}
    open fun download() {}
    open fun cancel(id: UUID) {}
    open fun play(job: DlJob) {}
    open fun clearFinished() {}
    open fun updateEngine() {}
    /** Full log text of a job (LOG button). */
    open fun log(id: UUID): String = ""
    open fun copy(text: String) {}
    open fun stop() {}
    open fun toggle(index: Int) {}
    open fun selectAll(all: Boolean) {}
    open fun closePlaylist() {}
    open fun downloadSelected() {}
    open fun cancelAll() {}
}

@Composable
fun DownloadsScreen(vm: DownloadsViewModel, player: PlayerViewModel, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadVersion() }
    // Android 8–9 save into the Music folder with the old storage permission.
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.download() }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val cb = object : DlCallbacks() {
        override fun close() = onClose()
        override fun link(s: String) = vm.setLink(s)
        override fun paste() {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            YtLogic.extractUrl(text)?.let { vm.setLink(it); vm.fetch() } ?: vm.setLink(text.orEmpty())
        }
        override fun fetch() = vm.fetch()
        override fun title(s: String) = vm.setTitle(s)
        override fun artist(s: String) = vm.setArtist(s)
        override fun album(s: String) = vm.setAlbum(s)
        override fun format(f: DlFormat) = vm.setFormat(f)
        override fun download() {
            if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT < 29) storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.download()
        }
        override fun cancel(id: UUID) = vm.cancel(id)
        override fun play(job: DlJob) = vm.play(job, player)
        override fun clearFinished() = vm.clearFinished()
        override fun updateEngine() = vm.updateEngine()
        override fun log(id: UUID) = vm.log(id)
        override fun stop() = vm.cancelFetch()
        override fun toggle(index: Int) = vm.toggle(index)
        override fun selectAll(all: Boolean) = vm.selectAll(all)
        override fun closePlaylist() = vm.closePlaylist()
        override fun downloadSelected() {
            if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
            vm.downloadSelected()
        }
        override fun cancelAll() = vm.cancelAll()
        override fun copy(text: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Kororythm download log", text))
            android.widget.Toast.makeText(context, "Log copied", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    DownloadsContent(ui, cb)
}

@Composable
fun DownloadsContent(ui: DlUi, cb: DlCallbacks, frozenNow: Long? = null) {
    // A clock for "no output for N s" and the elapsed time.
    val now by androidx.compose.runtime.produceState(frozenNow ?: System.currentTimeMillis()) {
        if (frozenNow != null) return@produceState
        while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    NeonScreen(title = "DOWNLOADS", onBack = cb::close) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card("LINK") {
                NeonTextField(ui.link, cb::link, placeholder = "https://youtu.be/…")
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NeonButton("PASTE", cb::paste)
                    if (ui.fetching) NeonButton("■ STOP", cb::stop, active = true, accent = Neon.Orange)
                    else NeonButton("FETCH ▸", cb::fetch, accent = Neon.Orange)
                    Spacer(Modifier.weight(1f))
                    NeonText("or share from YouTube", size = 9.sp, color = Neon.Dim, glow = null)
                }
                if (ui.fetching) {
                    Spacer(Modifier.height(8.dp))
                    Scanline()
                    val elapsed = ((now - ui.fetchStarted) / 1000).coerceAtLeast(0)
                    val quiet = ((now - ui.lastOutput) / 1000).coerceAtLeast(0)
                    NeonText(
                        (if (ui.firstRun && ui.fetchLines.isEmpty()) "Unpacking the download engine (first time only)" else "Reading the link") + " · ${elapsed} s",
                        Modifier.padding(top = 4.dp), size = 10.sp, color = Neon.Dim, glow = null,
                    )
                    if (quiet >= 20) {
                        NeonText("No output for $quiet s; it may be stuck. Tap ■ STOP to abort.", size = 10.sp, color = Neon.Orange, glow = null, maxLines = 2)
                    }
                    if (ui.fetchLines.isNotEmpty()) Terminal(ui.fetchLines.takeLast(7), Modifier.padding(top = 6.dp))
                }
                ui.error?.let { NeonText(it, Modifier.padding(top = 8.dp), size = 11.sp, color = Neon.Orange, glow = null, maxLines = 3) }
            }

            ui.playlist?.let { pl -> PlaylistPicker(pl, ui, cb) }

            ui.info?.let { info ->
                Card("TRACK") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ArtThumb(null, size = 64.dp, url = info.thumbnail)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            NeonText(info.videoTitle, size = 12.sp, weight = FontWeight.Bold, maxLines = 2)
                            NeonText(
                                listOfNotNull(info.uploader, info.durationSec.takeIf { it > 0 }?.let { formatTime(it * 1000L) }).joinToString(" · "),
                                size = 10.sp, color = Neon.Dim, glow = null,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    NeonTextField(ui.title, cb::title, label = "TITLE")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeonTextField(ui.artist, cb::artist, Modifier.weight(1f), label = "ARTIST")
                        NeonTextField(ui.album, cb::album, Modifier.weight(1f), label = "ALBUM", placeholder = "optional")
                    }
                }
                Card("SAVE AS") {
                    DlFormat.entries.forEach { f ->
                        ChoiceCard(f.label, f.hint, Modifier.padding(bottom = 6.dp), selected = ui.format == f, onClick = { cb.format(f) })
                    }
                    NeonText(YtLogic.qualityLabel(info.best, ui.format), size = 10.sp, color = Neon.Cyan, glow = null, maxLines = 2)
                    NeonText(
                        "MP3 never sounds better than its source; Upgrade quality treats it as ${info.best?.abrKbps ?: "?"} kbps.",
                        Modifier.padding(top = 2.dp), size = 9.sp, color = Neon.Dim, glow = null, maxLines = 2,
                    )
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        NeonButton("DOWNLOAD ▸", cb::download, accent = Neon.Orange, active = true)
                        Spacer(Modifier.width(10.dp))
                        NeonText("to ${MusicStore.FOLDER}", size = 10.sp, color = Neon.Dim, glow = null)
                    }
                }
            }

            Card("QUEUE") {
                if (ui.jobs.isEmpty()) {
                    NeonText("Nothing yet. Downloads land in ${MusicStore.FOLDER} and in your library.", size = 11.sp, color = Neon.Dim, glow = null, maxLines = 2)
                }
                val active = ui.jobs.count { it.state == JobState.RUNNING || it.state == JobState.QUEUED }
                if (active >= 2) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        NeonText("$active downloads in progress · 2 at a time", Modifier.weight(1f), size = 10.sp, color = Neon.Dim, glow = null)
                        NeonButton("CANCEL ALL", cb::cancelAll)
                    }
                }
                ui.jobs.forEach { j -> JobRow(j, cb, logOpen = j.id == ui.logOpen, now = now) }
                if (ui.jobs.any { it.state == JobState.DONE || it.state == JobState.FAILED }) {
                    NeonButton("CLEAR FINISHED", cb::clearFinished, Modifier.padding(top = 6.dp))
                }
            }

            Card("ENGINE") {
                NeonText("yt-dlp ${ui.engineVersion ?: "(bundled)"} · FFmpeg", size = 11.sp, color = Neon.Cyan, glow = null)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NeonButton(if (ui.updating) "UPDATING…" else "UPDATE ENGINE", cb::updateEngine, active = ui.updating)
                    Spacer(Modifier.width(10.dp))
                    NeonText(ui.updateMessage ?: "Sites change; update if links stop working.", Modifier.weight(1f), size = 10.sp, color = Neon.Dim, glow = null, maxLines = 2)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun JobRow(j: DlJob, cb: DlCallbacks, logOpen: Boolean = false, now: Long = 0) {
    var showLog by androidx.compose.runtime.saveable.rememberSaveable(j.id.toString()) { androidx.compose.runtime.mutableStateOf(logOpen) }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                NeonText(j.title, size = 12.sp, weight = FontWeight.Bold)
                val line = when (j.state) {
                    JobState.DONE -> "✓ ${j.savedAs ?: "Saved"}"
                    JobState.FAILED -> "✕ ${j.error}"
                    JobState.RUNNING -> j.stage + (if (j.progress in 0.001f..0.999f) " · ${(j.progress * 100).toInt()}%" else "") +
                        (((now - j.lastActivity) / 1000).takeIf { j.lastActivity > 0 && it >= 30 && !j.stage.startsWith("Waiting") }?.let { " · no output for $it s, may be stuck" } ?: "")
                    JobState.QUEUED -> "Waiting…"
                }
                NeonText(
                    line, size = 10.sp, glow = null, maxLines = 2,
                    color = when (j.state) { JobState.FAILED -> Neon.Orange; JobState.DONE -> Neon.Cyan; else -> Neon.Dim },
                )
            }
            NeonButton(if (showLog) "LOG ▴" else "LOG ▾", { showLog = !showLog }, Modifier.padding(end = 6.dp), active = showLog)
            when (j.state) {
                JobState.DONE -> if (j.trackId >= 0) NeonButton("PLAY ▸", { cb.play(j) }, accent = Neon.Orange)
                JobState.RUNNING, JobState.QUEUED -> NeonButton("CANCEL", { cb.cancel(j.id) })
                JobState.FAILED -> {}
            }
        }
        if (j.state == JobState.RUNNING) {
            Spacer(Modifier.height(5.dp))
            if (j.progress <= 0f || j.progress >= 1f) Scanline() else ProgressBar(j.progress)
        }
        if (showLog) LogView(j, cb)
    }
}

private val TermBg = androidx.compose.ui.graphics.Color(0xFF02060A)
private val TermText = androidx.compose.ui.graphics.Color(0xFFB9F6CA)
private val TermDim = androidx.compose.ui.graphics.Color(0xFF5E7A86)
private val TermWarn = androidx.compose.ui.graphics.Color(0xFFFFB74D)
private val TermErr = androidx.compose.ui.graphics.Color(0xFFFF6E6E)
private val TermCyan = androidx.compose.ui.graphics.Color(0xFF4DD0E1)

/** Live log of one download, terminal style; refreshes while the job runs and follows the end. */
@Composable
private fun LogView(j: DlJob, cb: DlCallbacks) {
    val running = j.state == JobState.RUNNING || j.state == JobState.QUEUED
    val text by androidx.compose.runtime.produceState(cb.log(j.id), j.id, running) {
        while (true) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cb.log(j.id) }
            if (!running) break
            kotlinx.coroutines.delay(500)
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(text.length) { scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(
            Modifier.fillMaxWidth().height(240.dp)
                .background(TermBg, RoundedCornerShape(6.dp))
                .neonBorder(TermCyan, 6.dp, 0.4f)
                .verticalScroll(scroll)
                .padding(8.dp),
        ) {
            if (text.isBlank()) {
                androidx.compose.material3.Text("(no log yet)", style = Neon.style(9.sp, TermDim, null, spacing = 0.sp).copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
            }
            text.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
                val color = when {
                    line.contains("[error]") || line.contains("ERROR") -> TermErr
                    line.contains("WARNING") -> TermWarn
                    line.contains("[debug]") -> TermDim
                    line.contains("[yt-dlp]") -> TermText
                    else -> TermCyan
                }
                androidx.compose.material3.Text(line, style = Neon.style(9.sp, color, null, spacing = 0.sp).copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            NeonText(if (running) "● live" else "${text.lines().count { it.isNotBlank() }} lines", Modifier.weight(1f), size = 9.sp, color = if (running) Neon.Orange else Neon.Dim, glow = null)
            NeonButton("COPY LOG", { cb.copy(text) })
        }
    }
}

@Composable
private fun ProgressBar(p: Float) {
    val c = Neon.Orange
    val track = Neon.Dim.copy(alpha = 0.25f)
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        drawRoundRect(Brush.horizontalGradient(listOf(Neon.Cyan, c)), size = Size(size.width * p, size.height), cornerRadius = r)
    }
}

/** Indeterminate bar: a light sweeping left to right. */
@Composable
private fun Scanline() {
    val x by rememberInfiniteTransition(label = "scan").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "x",
    )
    val c = Neon.Cyan
    val track = Neon.Dim.copy(alpha = 0.25f)
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val w = size.width * 0.25f
        val left = (size.width + w) * x - w
        drawRoundRect(c, Offset(left.coerceAtLeast(0f), 0f), Size((left + w).coerceAtMost(size.width) - left.coerceAtLeast(0f), size.height), r)
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Neon.Panel, RoundedCornerShape(10.dp))
            .neonBorder(Neon.Cyan, 10.dp, 0.6f)
            .padding(12.dp),
    ) {
        NeonText(title, size = 9.sp, color = Neon.Dim, glow = null, spacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** Last lines of yt-dlp's output, terminal style. */
@Composable
private fun Terminal(lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(TermBg, RoundedCornerShape(6.dp)).padding(6.dp)) {
        lines.forEach { line ->
            val color = when {
                line.contains("ERROR") -> TermErr
                line.contains("WARNING") -> TermWarn
                line.startsWith("yt-dlp ") || line.contains("[debug]") -> TermDim
                else -> TermText
            }
            androidx.compose.material3.Text(
                line, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = Neon.style(9.sp, color, null, spacing = 0.sp).copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            )
        }
    }
}

/** A playlist link: tick the items to download. */
@Composable
private fun PlaylistPicker(pl: com.koro.kororythm.downloads.Probe.Playlist, ui: DlUi, cb: DlCallbacks) {
    Card("PLAYLIST") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                NeonText(pl.title, size = 13.sp, weight = FontWeight.Bold, maxLines = 2)
                NeonText(
                    "${pl.entries.size} items" + (if (pl.total > pl.entries.size) " (first ${pl.entries.size} of ${pl.total})" else "") + " · ${ui.selected.size} selected",
                    size = 10.sp, color = Neon.Dim, glow = null,
                )
            }
            NeonText("✕", Modifier.clickable(onClick = cb::closePlaylist).padding(6.dp), size = 15.sp, color = Neon.Dim, glow = null)
        }
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NeonButton("ALL", { cb.selectAll(true) }, active = ui.selected.size == pl.entries.size)
            NeonButton("NONE", { cb.selectAll(false) }, active = ui.selected.isEmpty())
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                .background(Neon.Bg.copy(alpha = 0.35f), RoundedCornerShape(6.dp)),
        ) {
            pl.entries.forEach { e ->
                val on = e.index in ui.selected
                Row(
                    Modifier.fillMaxWidth().clickable { cb.toggle(e.index) }.padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.koro.kororythm.ui.common.NeonCheck(on)
                    Spacer(Modifier.width(8.dp))
                    NeonText("${e.index}.", Modifier.width(30.dp), size = 10.sp, color = Neon.Dim, glow = null)
                    Column(Modifier.weight(1f)) {
                        NeonText(e.title, size = 12.sp, color = if (on) Neon.Ink else Neon.Dim, glow = null)
                        e.artist?.let { NeonText(it, size = 9.sp, color = Neon.Dim, glow = null) }
                    }
                    if (e.durationSec > 0) NeonText(formatTime(e.durationSec * 1000L), size = 10.sp, color = Neon.Dim, glow = null)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        NeonTextField(ui.album, cb::album, label = "ALBUM FOR ALL (OPTIONAL)", placeholder = pl.title)
        Spacer(Modifier.height(8.dp))
        DlFormat.entries.forEach { f ->
            ChoiceCard(f.label, f.hint, Modifier.padding(bottom = 6.dp), selected = ui.format == f, onClick = { cb.format(f) })
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            NeonButton("DOWNLOAD ${ui.selected.size} ▸", cb::downloadSelected, accent = Neon.Orange, active = ui.selected.isNotEmpty())
            Spacer(Modifier.width(10.dp))
            NeonText("to ${MusicStore.FOLDER}", size = 10.sp, color = Neon.Dim, glow = null)
        }
    }
}
