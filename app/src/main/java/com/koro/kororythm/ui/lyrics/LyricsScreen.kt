package com.koro.kororythm.ui.lyrics

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koro.kororythm.lyrics.Lrc
import com.koro.kororythm.playback.PlayerViewModel
import com.koro.kororythm.playback.TrackInfo
import com.koro.kororythm.ui.player.formatTime
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.viz.VizState
import kotlinx.coroutines.launch

open class LyricsCallbacks {
    open fun close() {}
    open fun find() {}
    open fun save() {}
    open fun seek(ms: Long) {}
}

// The console is always dark smoked glass, in both skins, so it has its own bright colours.
private val ConsoleBg = Color(0xFF050A12)
private val ConsoleInk = Color(0xFFE8F4FA)
private val ConsoleDim = Color(0xFF7D93A3)
private val ConsoleCyan = Color(0xFF39D9FF)
private val ConsoleOrange = Color(0xFFFF8A2A)

/**
 * Lyrics as a drop-down console (like Guake, or Quake's "~" console): it slides down from the
 * top over whatever screen is showing. Drag the grip up, tap below it or press Back to put it
 * away. Follows the playing song, or shows [fixed] when opened for another song from the library.
 */
@Composable
fun LyricsDropHost(vm: LyricsViewModel, player: PlayerViewModel, open: Boolean, fixed: TrackInfo?, viz: VizState?, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ps by player.state.collectAsStateWithLifecycle()
    val shown = fixed ?: ps.current
    LaunchedEffect(open, shown?.uri) { if (open) vm.show(shown) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.dismissConsent()
        if (r.resultCode == android.app.Activity.RESULT_OK) vm.saveToFile(player)
    }
    LaunchedEffect(ui.consent) { ui.consent?.let { consent.launch(IntentSenderRequest.Builder(it).build()) } }
    BackHandler(enabled = open) { onClose() }
    val playingThis = ps.current?.uri == ui.uri
    val close by rememberUpdatedState(onClose)
    val cb = remember(player) {
        object : LyricsCallbacks() {
            override fun close() = close()
            override fun find() = vm.find()
            override fun save() = vm.saveToFile(player)
            override fun seek(ms: Long) { if (player.state.value.current?.uri == vm.ui.value.uri) player.seekTo(ms) }
        }
    }
    LyricsDrop(open, ui, if (playingThis) ps.positionMs else -1L, cb, viz)
}

@Composable
fun LyricsDrop(open: Boolean, ui: LyricsUi, positionMs: Long, cb: LyricsCallbacks, viz: VizState?, frozenTime: Float? = null) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = cb::close),
            )
        }
        AnimatedVisibility(
            open,
            enter = slideInVertically(spring(0.78f, Spring.StiffnessMediumLow)) { -it } + fadeIn(tween(120)),
            exit = slideOutVertically(tween(220)) { -it } + fadeOut(tween(220)),
        ) {
            LyricsConsole(ui, positionMs, cb, viz, frozenTime)
        }
    }
}

@Composable
fun LyricsConsole(ui: LyricsUi, positionMs: Long, cb: LyricsCallbacks, viz: VizState?, frozenTime: Float? = null) {
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // About two thirds of the screen, like a drop-down terminal.
    val consoleHeight = (LocalConfiguration.current.screenHeightDp * 0.64f).dp
    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer { translationY = drag.value }
            .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            .background(ConsoleBg)
            .clickable(remember { MutableInteractionSource() }, null) {},
    ) {
        Box(Modifier.fillMaxWidth().height(consoleHeight)) {
            Ambience(viz, ConsoleCyan, ConsoleOrange, Modifier.fillMaxSize(), frozenTime)
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Header(ui, positionMs, cb)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        ui.synced -> SyncedLines(ui, positionMs, cb)
                        !ui.plain.isNullOrBlank() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                            Text(ui.plain, style = Neon.style(16.sp, ConsoleInk, ConsoleCyan.copy(alpha = 0.6f)), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                        ui.uri == null -> Hint("Play a song to see its lyrics.")
                        ui.instrumental -> Hint("♪  Instrumental  ♪")
                        ui.searching -> Hint("Looking on LRCLIB…")
                        else -> Hint("No lyrics yet.\nTap FIND LYRICS to search LRCLIB.")
                    }
                }
            }
        }
        // Grip: drag it up (or tap it) to put the console away.
        Box(
            Modifier.fillMaxWidth().height(26.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, ConsoleCyan.copy(alpha = 0.10f))))
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (drag.value < -size.height * 3f) { cb.close(); drag.snapTo(0f) } else drag.animateTo(0f, spring(0.7f))
                            }
                        },
                    ) { change, dy ->
                        change.consume()
                        scope.launch { drag.snapTo((drag.value + dy).coerceAtMost(0f)) }
                    }
                }
                .clickable(onClick = cb::close),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(44.dp).height(4.dp).background(ConsoleCyan.copy(alpha = 0.7f), RoundedCornerShape(2.dp)))
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, ConsoleCyan, ConsoleOrange, Color.Transparent))))
    }
}

@Composable
private fun Header(ui: LyricsUi, positionMs: Long, cb: LyricsCallbacks) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NeonText("♪", Modifier.padding(end = 8.dp), size = 16.sp, color = ConsoleOrange, glow = ConsoleOrange)
        Column(Modifier.weight(1f)) {
            NeonText(ui.title.ifEmpty { "LYRICS" }, size = 14.sp, weight = FontWeight.Bold, color = ConsoleInk, glow = ConsoleCyan)
            NeonText(
                listOfNotNull(ui.artist, ui.source?.let { "via $it" + if (ui.synced) " · synced" else "" }).joinToString(" · ").ifEmpty { "Lyrics console" },
                size = 10.sp, color = ConsoleDim, glow = null,
            )
        }
        if (positionMs >= 0) NeonText(formatTime(positionMs), Modifier.padding(horizontal = 8.dp), size = 11.sp, color = ConsoleCyan, glow = null)
        NeonText("✕", Modifier.clickable(onClick = cb::close).padding(6.dp), size = 16.sp, color = ConsoleDim, glow = null)
    }
    if (ui.uri != null) {
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ConsoleButton(if (ui.searching) "SEARCHING…" else if (ui.hasLyrics) "↻ FIND AGAIN" else "FIND LYRICS", ConsoleOrange, cb::find)
            if (ui.hasLyrics && ui.canSave) ConsoleButton(ui.busy?.let { "SAVING…" } ?: "SAVE INTO FILE", ConsoleCyan, cb::save)
        }
    }
    (ui.error ?: ui.message)?.let {
        NeonText(it, Modifier.padding(top = 4.dp), size = 10.sp, color = if (ui.error != null) ConsoleOrange else ConsoleCyan, glow = null, maxLines = 2)
    }
}

/** Terminal-style outlined button (skin-independent, the console is always dark). */
@Composable
private fun ConsoleButton(text: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).background(accent.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .drawWithContent {
                drawContent()
                drawRoundRect(accent.copy(alpha = 0.8f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        NeonText(text, size = 11.sp, color = accent, glow = null, weight = FontWeight.Bold)
    }
}

@Composable
private fun Hint(text: String) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
    NeonText(text, size = 13.sp, color = ConsoleDim, glow = null, maxLines = 3)
}

@Composable
private fun SyncedLines(ui: LyricsUi, positionMs: Long, cb: LyricsCallbacks) {
    val current = if (positionMs >= 0) Lrc.currentIndex(ui.lines, positionMs) else -1
    val state = rememberLazyListState(current.coerceAtLeast(0))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val half = maxHeight / 2
        LaunchedEffect(current) { if (current >= 0) state.animateScrollToItem(current) }
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(top = (half - 22.dp).coerceAtLeast(0.dp), bottom = half),
            modifier = Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 0.2f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            itemsIndexed(ui.lines) { i, line ->
                val on = i == current
                val color by animateColorAsState(if (on) ConsoleOrange else if (i < current) ConsoleDim else ConsoleInk, label = "c")
                val scale by animateFloatAsState(if (on) 1.14f else 1f, label = "s")
                Text(
                    line.text.ifBlank { "♪" },
                    style = Neon.style(17.sp, color, if (on) ConsoleOrange else null, if (on) FontWeight.Bold else FontWeight.Normal),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
                        .clickable { cb.seek(line.timeMs) }.padding(horizontal = 18.dp, vertical = 7.dp),
                )
            }
        }
    }
}
