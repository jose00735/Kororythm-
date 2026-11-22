package com.koro.kororythm.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** Small top bar shared by the alternative layouts: logo, STYLE, LIBRARY. */
@Composable
fun LayoutTopBar(hooks: LayoutHooks, modifier: Modifier = Modifier, ink: Color = Neon.Ink) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(28.dp)) { drawParrot(androidx.compose.ui.geometry.Offset.Zero, size.minDimension) }
        Spacer(Modifier.width(8.dp))
        NeonText("KORORYTHM", Modifier.weight(1f), size = 13.sp, weight = FontWeight.Bold, spacing = 4.sp, color = ink)
        NeonButton("STYLE", { StyleSheetState.open = true })
        Spacer(Modifier.width(6.dp))
        NeonButton("LIBRARY", hooks.onOpenLibrary, accent = Neon.Orange)
    }
}

// ============================================================================================
// COVER STAGE: the album art, big, over a blurred copy of itself
// ============================================================================================

@Composable
fun CoverLayout(st: PlayerUiState, actions: PlayerActions, hooks: LayoutHooks, previewFlow: Float? = null) {
    val cur = st.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Neon.Bg)) {
        val screenW = maxWidth
        // Blurred, darkened copy of the cover as the backdrop; it cross-fades with the song
        // (Android 12+ blurs; older phones get the dim copy).
        Crossfade(cur?.uri, animationSpec = tween(700), label = "backdrop") { uri ->
            if (uri != null) ArtThumb(null, Modifier.fillMaxSize().blur(48.dp).graphicsLayer { alpha = 0.55f }, size = 600.dp, audio = uri)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Neon.Bg.copy(alpha = 0.35f), Neon.Bg.copy(alpha = 0.92f)))))
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LayoutTopBar(hooks)
            Spacer(Modifier.height(14.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                // Flow, reflections and the song's name read as one object; the slack goes around it.
                // The middle cover leaves room on each side for its neighbours to show.
                val side = minOf(maxWidth * 0.76f, (maxHeight - 64.dp) / 1.2f)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CoverFlow(st, actions, side, screenW, previewPos = previewFlow)
                    // The name slides in from the side the new cover came from.
                    AnimatedContent(
                        targetState = st.currentIndex,
                        transitionSpec = {
                            val dir = if (targetState >= initialState) 1 else -1
                            (slideInHorizontally(tween(360)) { it / 5 * dir } + fadeIn(tween(360)))
                                .togetherWith(slideOutHorizontally(tween(260)) { -it / 5 * dir } + fadeOut(tween(200)))
                        },
                        label = "title",
                    ) { idx ->
                        val t = st.queue.getOrNull(idx)
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            NeonText(t?.title ?: "Nothing playing", size = 20.sp, weight = FontWeight.Bold)
                            NeonText(
                                listOfNotNull(t?.artist, t?.album).joinToString(" · ").ifEmpty { "Pick something in the library" },
                                Modifier.padding(top = 2.dp), size = 12.sp, color = Neon.Dim, glow = null,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            NeonSeekBar(st.positionMs, st.durationMs, actions::seekTo, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                NeonText(formatTime(st.positionMs), size = 11.sp, color = Neon.Dim, glow = null)
                Spacer(Modifier.weight(1f))
                NeonText("-" + formatTime((st.durationMs - st.positionMs).coerceAtLeast(0)), size = 11.sp, color = Neon.Dim, glow = null)
            }
            // Symmetric deck: two equal side keys around prev / play / next.
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TransportButton(TIcon.SHUFFLE, actions::toggleShuffle, size = 48.dp, active = st.shuffle)
                TransportButton(TIcon.PREV, actions::previous, size = 52.dp)
                TransportButton(if (st.isPlaying) TIcon.PAUSE else TIcon.PLAY, actions::playPause, size = 66.dp, accent = Neon.Orange)
                TransportButton(TIcon.NEXT, actions::next, size = 52.dp)
                TransportButton(TIcon.LYRICS, hooks.onLyrics, size = 48.dp)
            }
            // Up next: the following songs' covers
            val next = st.queue.withIndex().drop(st.currentIndex + 1).take(12)
            if (next.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    NeonText("UP NEXT", size = 10.sp, color = Neon.Dim, glow = null, spacing = 3.sp)
                }
                LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(next, key = { _, v -> v.index }) { _, v ->
                        Column(
                            Modifier.width(72.dp).clip(RoundedCornerShape(6.dp))
                                .clickable(onClickLabel = "Play ${v.value.title}") { actions.playAt(v.index) },
                        ) {
                            ArtThumb(null, size = 72.dp, audio = v.value.uri)
                            NeonText(v.value.title, Modifier.padding(top = 4.dp), size = 10.sp, color = Neon.Ink.copy(alpha = 0.8f), glow = null)
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
        // Scrims keep the words readable over the brightest colours without dimming the show.
        val scrim by androidx.compose.animation.core.animateFloatAsState(if (controls) 1f else 0.75f, label = "scrim")
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.42f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f * scrim), Color.Black.copy(alpha = 0.82f * scrim)))),
        )
        AnimatedVisibility(controls, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().systemBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp)) {
            NeonText(cur?.title ?: "PRISM", size = 20.sp, weight = FontWeight.Bold, color = Color.White, glow = Color.White)
            NeonText(listOfNotNull(cur?.artist, cur?.album).joinToString(" · ").ifEmpty { "Play something to light it up" }, size = 12.sp, color = Color(0xFFD7E3EA), glow = null)
            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut()) {
                Column(Modifier.padding(top = 10.dp)) {
                    NeonSeekBar(st.positionMs, st.durationMs, actions::seekTo, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TransportButton(TIcon.PREV, actions::previous, size = 48.dp)
                        TransportButton(if (st.isPlaying) TIcon.PAUSE else TIcon.PLAY, actions::playPause, size = 58.dp, accent = Neon.Orange)
                        TransportButton(TIcon.NEXT, actions::next, size = 48.dp)
                        Spacer(Modifier.weight(1f))
                        TransportButton(TIcon.SHUFFLE, actions::toggleShuffle, size = 48.dp, active = st.shuffle)
                        TransportButton(TIcon.LYRICS, hooks.onLyrics, size = 48.dp)
                    }
                }
            }
        }
        AnimatedVisibility(controls, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            LayoutTopBar(hooks, Modifier.systemBarsPadding().padding(12.dp), ink = Color.White)
        }
        // The gesture hint shows once, when the layout opens, then gets out of the way.
        var hint by remember { mutableStateOf(frozenFrames == null) }
        LaunchedEffect(Unit) { if (hint) { delay(3500); hint = false } }
        AnimatedVisibility(hint && !controls, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            NeonText(
                "TAP FOR CONTROLS · DOUBLE-TAP TO PAUSE",
                Modifier.systemBarsPadding().padding(top = 16.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                size = 10.sp, color = Color.White, glow = null, spacing = 1.sp,
            )
        }
    }
}

