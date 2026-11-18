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
    }
    DownloadsContent(ui, cb)
}

@Composable
fun DownloadsContent(ui: DlUi, cb: DlCallbacks) {
    NeonScreen(title = "DOWNLOADS", onBack = cb::close) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card("LINK") {
                NeonTextField(ui.link, cb::link, placeholder = "https://youtu.be/…")
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NeonButton("PASTE", cb::paste)
                    NeonButton(if (ui.fetching) "READING…" else "FETCH ▸", cb::fetch, active = ui.fetching, accent = Neon.Orange)
                    Spacer(Modifier.weight(1f))
                    NeonText("or share from YouTube", size = 9.sp, color = Neon.Dim, glow = null)
                }
                if (ui.fetching) {
                    Spacer(Modifier.height(8.dp))
                    Scanline()
                    NeonText(
                        if (ui.firstRun) "Unpacking the download engine (first time only)…" else "Reading the link…",
                        Modifier.padding(top = 4.dp), size = 10.sp, color = Neon.Dim, glow = null,
                    )
                }
                ui.error?.let { NeonText(it, Modifier.padding(top = 8.dp), size = 11.sp, color = Neon.Orange, glow = null, maxLines = 3) }
            }

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
                ui.jobs.forEach { j -> JobRow(j, cb) }
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
                NeonText(
                    "Download only what you have the right to keep. yt-dlp is public domain (Unlicense); Kororythm is GPL-3.0.",
                    Modifier.padding(top = 8.dp), size = 9.sp, color = Neon.Dim, glow = null, maxLines = 3,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun JobRow(j: DlJob, cb: DlCallbacks) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                NeonText(j.title, size = 12.sp, weight = FontWeight.Bold)
                val line = when (j.state) {
                    JobState.DONE -> "✓ ${j.savedAs ?: "Saved"}"
                    JobState.FAILED -> "✕ ${j.error}"
                    JobState.RUNNING -> j.stage + if (j.progress in 0.001f..0.999f) " · ${(j.progress * 100).toInt()}%" else ""
                    JobState.QUEUED -> "Waiting…"
                }
                NeonText(
                    line, size = 10.sp, glow = null, maxLines = 2,
                    color = when (j.state) { JobState.FAILED -> Neon.Orange; JobState.DONE -> Neon.Cyan; else -> Neon.Dim },
                )
            }
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
