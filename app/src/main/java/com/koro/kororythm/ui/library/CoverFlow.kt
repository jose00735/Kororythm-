package com.koro.kororythm.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koro.kororythm.ui.common.ArtThumb
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Cover flow: the centre album faces you, the others are turned 55° away and stacked to the
 * sides, all with mirror reflections on the floor. Drag or flick to browse (it snaps to a
 * cover); tap a side cover to bring it forward, tap the centre one to open it.
 */
@Composable
fun CoverFlow(cards: List<AlbumCard>, onOpen: (Int) -> Unit, onPlay: (Int) -> Unit, initial: Int = 0) {
    val pos = remember(cards.size) { Animatable(initial.coerceIn(0, (cards.size - 1).coerceAtLeast(0)).toFloat()) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    fun snapTo(i: Int) = scope.launch { pos.animateTo(i.coerceIn(0, cards.lastIndex).toFloat(), spring(0.8f, Spring.StiffnessLow)) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cover: Dp = (maxWidth * 0.5f).coerceAtMost(maxHeight * 0.46f).coerceAtMost(260.dp)
        val near = cover * 0.66f   // centre → first neighbour
        val step = cover * 0.26f   // spacing of the stack further out
        val stepPx = with(density) { step.toPx() }
        val nearPx = with(density) { near.toPx() }
        val coverPx = with(density) { cover.toPx() }

        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(cover * 1.48f).pointerInput(cards.size) {
                    val vt = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { vt.resetTracking() },
                        onDragEnd = {
                            val v = vt.calculateVelocity().x / coverPx // covers per second
                            snapTo((pos.value - v * 0.3f).roundToInt())
                        },
                        onDragCancel = { snapTo(pos.value.roundToInt()) },
                    ) { change, dx ->
                        change.consume()
                        vt.addPosition(change.uptimeMillis, change.position)
                        val perCover = if (abs(pos.value - pos.value.roundToInt()) < 0.5f) nearPx else stepPx
                        scope.launch { pos.snapTo((pos.value - dx / perCover).coerceIn(-0.4f, cards.lastIndex + 0.4f)) }
                    }
                },
                contentAlignment = Alignment.TopCenter,
            ) {
                val c = pos.value
                val from = (floor(c) - 6).toInt().coerceAtLeast(0)
                val to = (ceil(c) + 6).toInt().coerceAtMost(cards.lastIndex)
                // Far covers first, so the centre one is drawn (and tapped) on top.
                (from..to).sortedByDescending { abs(it - c) }.forEach { i ->
                    key(i) {
                        val d = i - c
                        val t = d.coerceIn(-1f, 1f)
                        val x = sign(d) * (abs(t) * nearPx + (abs(d) - 1f).coerceAtLeast(0f) * stepPx)
                        Column(
                            Modifier
                                .requiredSize(cover, cover * 1.48f)
                                .graphicsLayer {
                                    translationX = x
                                    rotationY = -55f * t
                                    cameraDistance = 14f * density.density
                                    val s = 1f - 0.16f * abs(t)
                                    scaleX = s; scaleY = s
                                    alpha = (1.5f - abs(d) * 0.22f).coerceIn(0f, 1f)
                                }
                                .clickable { if (abs(d) < 0.5f) onOpen(i) else snapTo(i) },
                        ) {
                            val card = cards[i]
                            ArtThumb(card.artPath, Modifier.size(cover), size = cover, contentUri = card.coverUri)
                            // Reflection: the same cover flipped, fading into the floor.
                            Box(
                                Modifier.padding(top = 3.dp).size(cover, cover * 0.44f)
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; clip = true }
                                    .drawWithContent {
                                        drawContent()
                                        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.42f), Color.Transparent)), blendMode = BlendMode.DstIn)
                                    },
                            ) {
                                ArtThumb(
                                    card.artPath,
                                    Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true).size(cover).graphicsLayer {
                                        scaleY = -1f; transformOrigin = TransformOrigin(0.5f, 0.5f)
                                    },
                                    size = cover, contentUri = card.coverUri,
                                )
                            }
                        }
                    }
                }
            }
            val centered = pos.value.roundToInt().coerceIn(0, cards.lastIndex)
            val card = cards[centered]
            NeonText(card.title, Modifier.padding(horizontal = 16.dp), size = 15.sp, weight = FontWeight.Bold)
            NeonText(card.subtitle, size = 11.sp, color = Neon.Cyan, glow = null)
            NeonText(card.meta, size = 10.sp, color = Neon.Dim, glow = null)
            Spacer(Modifier.height(8.dp))
            NeonButton("OPEN ▸", { onPlay(centered) }, accent = Neon.Orange, active = true)
            NeonText("${centered + 1} / ${cards.size}", Modifier.padding(top = 6.dp), size = 9.sp, color = Neon.Dim, glow = null)
        }
    }
}
