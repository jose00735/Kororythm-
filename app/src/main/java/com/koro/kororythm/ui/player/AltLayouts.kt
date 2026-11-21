package com.koro.kororythm.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.ui.common.ArtThumb
import com.koro.kororythm.ui.lyrics.Energy
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.drawParrot
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder
import com.koro.kororythm.ui.viz.VizState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** What every alternative layout gets from the player screen. */
class LayoutHooks(
    val onOpenLibrary: () -> Unit = {},
    val onLyrics: () -> Unit = {},
)

/** Small top bar shared by the alternative layouts: logo, ◐ STYLE, LIBRARY. */
@Composable
fun LayoutTopBar(hooks: LayoutHooks, modifier: Modifier = Modifier, ink: Color = Neon.Ink) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(28.dp)) { drawParrot(androidx.compose.ui.geometry.Offset.Zero, size.minDimension) }
        Spacer(Modifier.width(8.dp))
        NeonText("KORORYTHM", Modifier.weight(1f), size = 13.sp, weight = FontWeight.Bold, spacing = 4.sp, color = ink)
        NeonButton("◐ STYLE", { StyleSheetState.open = true })
        Spacer(Modifier.width(6.dp))
        NeonButton("LIBRARY", hooks.onOpenLibrary, accent = Neon.Orange)
    }
}

// ============================================================================================
// COVER STAGE: the album art, big, over a blurred copy of itself
// ============================================================================================

@Composable
fun CoverLayout(st: PlayerUiState, actions: PlayerActions, hooks: LayoutHooks) {
    val cur = st.current
    Box(Modifier.fillMaxSize().background(Neon.Bg)) {
        // Blurred, darkened copy of the cover as the backdrop (Android 12+ blurs; older phones get the dim copy).
        if (cur != null) {
            ArtThumb(null, Modifier.fillMaxSize().blur(48.dp).graphicsLayer { alpha = 0.55f }, size = 600.dp, audio = cur.uri)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Neon.Bg.copy(alpha = 0.35f), Neon.Bg.copy(alpha = 0.92f)))))
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LayoutTopBar(hooks)
            Spacer(Modifier.height(14.dp))
            // Swipe the cover for next / previous; tap it to play / pause.
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight * 0.82f)
                Column(
                    Modifier.graphicsLayer {
                        translationX = drag.value
                        rotationY = -drag.value / 40f
                        alpha = 1f - (abs(drag.value) / 900f).coerceAtMost(0.6f)
                    }
                        .pointerInput(st.currentIndex) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    scope.launch {
                                        when {
                                            drag.value < -160f -> actions.next()
                                            drag.value > 160f -> actions.previous()
                                        }
                                        drag.animateTo(0f, spring(0.7f))
                                    }
                                },
                            ) { ch, dx -> ch.consume(); scope.launch { drag.snapTo(drag.value + dx) } }
                        }
                        .pointerInput(Unit) { detectTapGestures(onTap = { actions.playPause() }) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(side).clip(RoundedCornerShape(10.dp)).neonBorder(Neon.Cyan, 10.dp, 0.5f)) {
                        ArtThumb(null, Modifier.fillMaxSize(), size = side, audio = cur?.uri)
                    }
                    // floor reflection
                    Box(
                        Modifier.size(side, side * 0.16f).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; clip = true }
                            .drawWithContent {
                                drawContent()
                                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent)), blendMode = BlendMode.DstIn)
                            },
                    ) {
                        ArtThumb(
                            null,
                            Modifier.size(side).graphicsLayer { scaleY = -1f },
                            size = side, audio = cur?.uri,
                        )
                    }
                }
            }
            NeonText(cur?.title ?: "Nothing playing", Modifier.padding(top = 4.dp), size = 19.sp, weight = FontWeight.Bold)
            NeonText(listOfNotNull(cur?.artist, cur?.album).joinToString(" · ").ifEmpty { "Pick something in the library" }, size = 12.sp, color = Neon.Dim, glow = null)
            Spacer(Modifier.height(10.dp))
            NeonSeekBar(st.positionMs, st.durationMs, actions::seekTo, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth()) {
                NeonText(formatTime(st.positionMs), size = 10.sp, color = Neon.Dim, glow = null)
                Spacer(Modifier.weight(1f))
                NeonText("-" + formatTime((st.durationMs - st.positionMs).coerceAtLeast(0)), size = 10.sp, color = Neon.Dim, glow = null)
            }
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton("SHUF", actions::toggleShuffle, active = st.shuffle)
                TransportButton(TIcon.PREV, actions::previous, size = 48.dp)
                TransportButton(if (st.isPlaying) TIcon.PAUSE else TIcon.PLAY, actions::playPause, size = 62.dp, accent = Neon.Orange)
                TransportButton(TIcon.NEXT, actions::next, size = 48.dp)
                NeonButton("♪", hooks.onLyrics)
            }
            // Up next: the following songs' covers
            val next = st.queue.withIndex().drop(st.currentIndex + 1).take(12)
            if (next.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
                    NeonText("UP NEXT", size = 9.sp, color = Neon.Dim, glow = null, spacing = 3.sp)
                }
                LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(next, key = { _, v -> v.index }) { _, v ->
                        Column(Modifier.width(64.dp).clickable { actions.playAt(v.index) }) {
                            ArtThumb(null, size = 64.dp, audio = v.value.uri)
                            NeonText(v.value.title, size = 8.sp, color = Neon.Dim, glow = null)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================================
// PRISM: full-screen feedback light show
// ============================================================================================

@Composable
fun PrismLayout(st: PlayerUiState, actions: PlayerActions, viz: VizState, hooks: LayoutHooks, frozenFrames: Int? = null) {
    val preview = LocalInspectionMode.current || frozenFrames != null
    var controls by remember { mutableStateOf(frozenFrames != null) }
    var lastTouch by remember { mutableStateOf(0L) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val w = 320
        val h = (w * (maxHeight.value / maxWidth.value)).roundToInt().coerceIn(200, 900)
        val engine = remember(h) { PrismEngine(w, h) }
        DisposableEffect(engine) { onDispose { if (!preview) engine.release() } }
        var tick by remember { mutableIntStateOf(0) }
        val energy = remember { Energy() }
        if (preview) {
            // Screenshots: a fixed number of frames with a steady, made-up beat.
            remember(engine) {
                repeat(frozenFrames ?: 120) { f ->
                    val beat = if (f % 30 < 6) 0.9f else 0.35f
                    engine.step(1 / 60f, beat, 0.55f, 0.45f)
                }
                true
            }
        } else {
            val playing by androidx.compose.runtime.rememberUpdatedState(st.isPlaying)
            LaunchedEffect(engine) {
                var last = 0L
                while (true) {
                    withFrameNanos { now ->
                        val dt = if (last == 0L) 1 / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
                        last = now
                        energy.feed(viz.bars)
                        // Keep it gently alive when the music is paused.
                        val idle = if (playing) 0f else 0.12f
                        engine.step(dt, maxOf(energy.bass, idle), maxOf(energy.mid, idle), maxOf(energy.high, idle * 0.5f))
                        tick++
                    }
                }
            }
        }
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controls = !controls; lastTouch = System.currentTimeMillis() },
                    onDoubleTap = { actions.playPause() },
                )
            },
        ) {
            tick // redraw on every new frame
            drawImage(
                engine.bitmap.asImageBitmap(),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
        }
        // Controls hide again after a few seconds.
        LaunchedEffect(controls, lastTouch) {
            if (controls && frozenFrames == null) { delay(4500); controls = false }
        }
        val cur = st.current
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))).systemBarsPadding().padding(18.dp)) {
            NeonText(cur?.title ?: "PRISM", size = 20.sp, weight = FontWeight.Bold, color = Color.White, glow = Color.White)
            NeonText(listOfNotNull(cur?.artist, cur?.album).joinToString(" · ").ifEmpty { "Play something to light it up" }, size = 12.sp, color = Color(0xFFD7E3EA), glow = null)
            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut()) {
                Column(Modifier.padding(top = 10.dp)) {
                    NeonSeekBar(st.positionMs, st.durationMs, actions::seekTo, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TransportButton(TIcon.PREV, actions::previous, size = 44.dp)
                        TransportButton(if (st.isPlaying) TIcon.PAUSE else TIcon.PLAY, actions::playPause, size = 54.dp, accent = Neon.Orange)
                        TransportButton(TIcon.NEXT, actions::next, size = 44.dp)
                        Spacer(Modifier.weight(1f))
                        NeonButton("♪", hooks.onLyrics)
                    }
                }
            }
        }
        AnimatedVisibility(controls, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            LayoutTopBar(hooks, Modifier.systemBarsPadding().padding(12.dp), ink = Color.White)
        }
        if (!controls) {
            NeonText(
                "TAP FOR CONTROLS · DOUBLE-TAP PLAY/PAUSE",
                Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(top = 14.dp).graphicsLayer { alpha = 0.45f },
                size = 8.sp, color = Color.White, glow = null, spacing = 2.sp,
            )
        }
    }
}

