package com.koro.kororythm.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.ui.common.ArtThumb
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.neonBorder
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Cover flow for the Cover Stage layout.
 *
 * The whole queue sits on one continuous strip whose position is a single float: the song in
 * the middle faces you, the ones beside it turn towards the centre and step back. Every change
 * of song, whether from a swipe, the deck buttons, the library or the end of a track, glides
 * the strip with a spring, so covers slide and turn instead of jumping.
 *
 * @param side the size of the centre cover.
 * @param fullWidth the width the strip may use (it bleeds past the screen's side padding).
 * @param previewPos a fixed strip position, for screenshot tests of a mid-transition frame.
 */
@Composable
fun CoverFlow(
    st: PlayerUiState,
    actions: PlayerActions,
    side: Dp,
    fullWidth: Dp,
    modifier: Modifier = Modifier,
    previewPos: Float? = null,
) {
    val n = st.queue.size
    val start = st.currentIndex.coerceAtLeast(0).toFloat()
    val pos = remember { Animatable(start) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val stepPx = with(density) { (side * STEP).toPx() }
    val current by rememberUpdatedState(st.currentIndex)

    // Follow the player. A long jump (say, a song picked from the library) skips most of the way
    // instantly and only glides the last few covers, so it stays quick and readable.
    LaunchedEffect(st.currentIndex, n) {
        if (dragging || st.currentIndex < 0) return@LaunchedEffect
        val to = st.currentIndex.toFloat()
        val gap = to - pos.value
        if (abs(gap) > 4f) pos.snapTo(to - sign(gap) * 3f)
        pos.animateTo(to, spring(dampingRatio = 0.82f, stiffness = 170f))
    }

    val p = previewPos ?: pos.value
    var lastTick by remember { mutableIntStateOf(start.roundToInt()) }

    Box(
        modifier
            .requiredWidth(fullWidth)
            .height(side * (1f + REFLECTION) + side * 0.04f)
            .pointerInput(n, stepPx) {
                if (n == 0) return@pointerInput
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        tracker.resetTracking()
                        scope.launch { pos.stop() }
                    },
                    onDragEnd = {
                        val v = -tracker.calculateVelocity().x / stepPx // covers per second
                        // Throw: the fling carries the strip, then it settles on the nearest cover.
                        val target = (pos.value + v * 0.22f).roundToInt().coerceIn(0, n - 1)
                        if (target != current) actions.playAt(target)
                        scope.launch {
                            pos.animateTo(target.toFloat(), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), initialVelocity = v)
                            dragging = false
                        }
                    },
                    onDragCancel = {
                        scope.launch { pos.animateTo(current.coerceAtLeast(0).toFloat(), spring(0.8f, Spring.StiffnessLow)); dragging = false }
                    },
                ) { change, dx ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    // A little resistance past either end of the queue.
                    val raw = pos.value - dx / stepPx
                    val next = when {
                        raw < 0f -> raw * 0.35f
                        raw > n - 1f -> (n - 1f) + (raw - (n - 1f)) * 0.35f
                        else -> raw
                    }
                    scope.launch { pos.snapTo(next) }
                    val tick = next.roundToInt()
                    if (tick != lastTick) { lastTick = tick; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
            }
            .semantics { contentDescription = "Cover flow. Swipe to browse the queue, tap the middle cover to play or pause, tap a side cover to play it" },
        contentAlignment = Alignment.TopCenter,
    ) {
        if (n == 0) {
            FlowCover(null, side, 0f, Modifier, onTap = {})
            return@Box
        }
        val from = (floor(p).toInt() - VISIBLE).coerceAtLeast(0)
        val to = (ceil(p).toInt() + VISIBLE).coerceAtMost(n - 1)
        for (i in from..to) {
            key(st.queue[i].uri, i) {
                val d = i - p
                FlowCover(
                    st.queue[i].uri, side, d,
                    Modifier.zIndex(-abs(d)),
                    onTap = { if (abs(d) < 0.5f) actions.playPause() else actions.playAt(i) },
                )
            }
        }
    }
}

private const val STEP = 0.56f        // centre-to-neighbour distance, in covers
private const val FAR_STEP = 0.2f     // spacing of the covers further out (they stack)
private const val TILT = 62f          // how far the side covers turn
private const val REFLECTION = 0.16f
private const val VISIBLE = 4

/** One cover at strip offset [d] (0 = centre, ±1 = neighbours). */
@Composable
private fun FlowCover(uri: String?, side: Dp, d: Float, modifier: Modifier, onTap: () -> Unit) {
    val ad = abs(d)
    val near = d.coerceIn(-1f, 1f)
    val density = LocalDensity.current
    val sidePx = with(density) { side.toPx() }
    Column(
        modifier
            .graphicsLayer {
                translationX = if (ad <= 1f) d * sidePx * STEP else sign(d) * (sidePx * STEP + (ad - 1f) * sidePx * FAR_STEP)
                // Side covers turn to face the middle: the outer edge swings towards you.
                rotationY = -near * TILT
                transformOrigin = TransformOrigin(0.5f, 0.42f)
                cameraDistance = 20f * density.density
                val s = 1f - 0.2f * ad.coerceAtMost(1f)
                scaleX = s; scaleY = s
                alpha = when {
                    ad <= 2.6f -> 1f
                    else -> (1f - (ad - 2.6f) / 1.4f).coerceIn(0f, 1f)
                }
            }
            .pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) },
    ) {
        Box(Modifier.size(side).clip(RoundedCornerShape(8.dp)).neonBorder(Neon.Cyan, 8.dp, 0.5f * (1f - ad.coerceAtMost(1f)) + 0.15f)) {
            ArtThumb(null, Modifier.fillMaxSize(), size = side, audio = uri)
            // Covers off to the side sink into shadow, so the middle one reads as lit.
            Box(Modifier.fillMaxSize().drawWithContent { drawRect(Color.Black.copy(alpha = 0.5f * ad.coerceAtMost(1.4f) / 1.4f)) })
        }
        // Floor reflection, fading to nothing.
        Box(
            Modifier.padding(top = 2.dp).size(side, side * REFLECTION)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; clip = true }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent)), blendMode = BlendMode.DstIn)
                },
        ) {
            ArtThumb(null, Modifier.size(side).graphicsLayer { scaleY = -1f }, size = side, audio = uri)
        }
    }
}
