package com.koro.kororythm.ui.lyrics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koro.kororythm.lyrics.Lrc
import com.koro.kororythm.playback.PlayerViewModel
import com.koro.kororythm.ui.common.NeonScreen
import com.koro.kororythm.ui.player.formatTime
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder
import androidx.compose.material3.Text

open class LyricsCallbacks {
    open fun close() {}
    open fun find() {}
    open fun save() {}
    open fun seek(ms: Long) {}
}

/**
 * Lyrics of the playing song (or of [fixedUri]'s song, opened from the library).
 * Synced lyrics light up line by line and scroll with the music; tap a line to jump there.
 */
@Composable
fun LyricsScreen(vm: LyricsViewModel, player: PlayerViewModel, onClose: () -> Unit, fixed: com.koro.kororythm.playback.TrackInfo? = null) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ps by player.state.collectAsStateWithLifecycle()
    val shown = fixed ?: ps.current
    LaunchedEffect(shown?.uri) { vm.show(shown) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.dismissConsent()
        if (r.resultCode == android.app.Activity.RESULT_OK) vm.saveToFile(player)
    }
    LaunchedEffect(ui.consent) { ui.consent?.let { consent.launch(IntentSenderRequest.Builder(it).build()) } }
    val playingThis = ps.current?.uri == ui.uri
    val cb = object : LyricsCallbacks() {
        override fun close() = onClose()
        override fun find() = vm.find()
        override fun save() = vm.saveToFile(player)
        override fun seek(ms: Long) { if (playingThis) player.seekTo(ms) }
    }
    LyricsContent(ui, if (playingThis) ps.positionMs else -1L, cb)
}

@Composable
fun LyricsContent(ui: LyricsUi, positionMs: Long, cb: LyricsCallbacks) {
    NeonScreen(title = "LYRICS", onBack = cb::close) {
        if (ui.uri == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NeonText("Play a song to see its lyrics.", size = 12.sp, color = Neon.Dim, glow = null)
            }
            return@NeonScreen
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                NeonText(ui.title, size = 15.sp, weight = FontWeight.Bold)
                NeonText(
                    listOfNotNull(ui.artist, ui.source?.let { "via $it" + if (ui.synced) " · synced" else "" }).joinToString(" · "),
                    size = 10.sp, color = Neon.Dim, glow = null,
                )
            }
            if (positionMs >= 0) NeonText(formatTime(positionMs), size = 11.sp, color = Neon.Cyan, glow = null)
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton(if (ui.searching) "SEARCHING…" else if (ui.hasLyrics) "↻ FIND AGAIN" else "FIND LYRICS", cb::find, active = ui.searching, accent = Neon.Orange)
            if (ui.hasLyrics && ui.canSave) NeonButton(ui.busy?.let { "SAVING…" } ?: "SAVE INTO FILE", cb::save, active = ui.busy != null)
        }
        (ui.error ?: ui.message)?.let {
            NeonText(it, Modifier.padding(bottom = 6.dp), size = 10.sp, color = if (ui.error != null) Neon.Orange else Neon.Cyan, glow = null, maxLines = 2)
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Neon.Panel, RoundedCornerShape(10.dp)).neonBorder(Neon.Cyan, 10.dp, 0.6f),
        ) {
            when {
                ui.synced -> SyncedLines(ui, positionMs, cb)
                !ui.plain.isNullOrBlank() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(ui.plain, style = Neon.style(15.sp, Neon.Ink, null), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                ui.instrumental -> Hint("♪  Instrumental  ♪")
                ui.searching -> Hint("Looking on LRCLIB…")
                else -> Hint("No lyrics yet.\nTap FIND LYRICS to search LRCLIB.")
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
    NeonText(text, size = 13.sp, color = Neon.Dim, glow = null, maxLines = 3)
}

@Composable
private fun SyncedLines(ui: LyricsUi, positionMs: Long, cb: LyricsCallbacks) {
    val current = if (positionMs >= 0) Lrc.currentIndex(ui.lines, positionMs) else -1
    val state = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val half = maxHeight / 2
        LaunchedEffect(current) {
            if (current >= 0) state.animateScrollToItem(current)
        }
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(top = half - 24.dp, bottom = half),
            modifier = Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // fade lines out towards the top and bottom edges
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            itemsIndexed(ui.lines) { i, line ->
                val on = i == current
                val past = i < current
                val color by animateColorAsState(if (on) Neon.Orange else if (past) Neon.Dim else Neon.Ink, label = "c")
                val scale by animateFloatAsState(if (on) 1.12f else 1f, label = "s")
                Text(
                    line.text.ifBlank { "♪" },
                    style = Neon.style(16.sp, color, if (on) Neon.Orange else null, if (on) FontWeight.Bold else FontWeight.Normal),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
                        .clickable { cb.seek(line.timeMs) }.padding(horizontal = 18.dp, vertical = 7.dp),
                )
            }
        }
    }
}
