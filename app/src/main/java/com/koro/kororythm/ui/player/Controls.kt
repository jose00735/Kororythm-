package com.koro.kororythm.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.neonBorder

enum class TIcon { PREV, PLAY, PAUSE, STOP, NEXT, EJECT }

/** Transport button with a hand-drawn neon icon. */
@Composable
fun TransportButton(icon: TIcon, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = Neon.Cyan, size: Dp = 44.dp) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .size(size)
            .background(if (pressed) accent.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(8.dp))
            .neonBorder(accent, 8.dp, if (pressed) 1f else 0.55f)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.42f)) { drawTransportIcon(icon, if (pressed) Neon.Ink else accent) }
    }
}

private fun DrawScope.drawTransportIcon(icon: TIcon, c: Color) {
    val w = size.width; val h = size.height
    fun tri(x0: Float, x1: Float, pointRight: Boolean) {
        val p = Path().apply {
            if (pointRight) { moveTo(x0, 0f); lineTo(x1, h / 2); lineTo(x0, h) }
            else { moveTo(x1, 0f); lineTo(x0, h / 2); lineTo(x1, h) }
            close()
        }
        drawPath(p, c)
    }
    val bar = w * 0.16f
    when (icon) {
        TIcon.PLAY -> tri(w * 0.12f, w, true)
        TIcon.PAUSE -> {
            drawRect(c, Offset(w * 0.12f, 0f), Size(w * 0.26f, h))
            drawRect(c, Offset(w * 0.62f, 0f), Size(w * 0.26f, h))
        }
        TIcon.STOP -> drawRect(c, Offset(w * 0.08f, h * 0.08f), Size(w * 0.84f, h * 0.84f))
        TIcon.NEXT -> { tri(0f, w - bar, true); drawRect(c, Offset(w - bar, 0f), Size(bar, h)) }
        TIcon.PREV -> { drawRect(c, Offset.Zero, Size(bar, h)); tri(bar, w, false) }
        TIcon.EJECT -> {
            val p = Path().apply { moveTo(0f, h * 0.62f); lineTo(w / 2, 0f); lineTo(w, h * 0.62f); close() }
            drawPath(p, c)
            drawRect(c, Offset(0f, h * 0.78f), Size(w, h * 0.22f))
        }
    }
}

/** Horizontal neon seek bar. Shows the drag position while dragging, seeks on release. */
@Composable
fun NeonSeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)
    val fraction = dragFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Canvas(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> if (duration > 0) seek(((o.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let { if (duration > 0) seek((it * duration).toLong()) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                ) { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        val y = size.height / 2
        val track = 3.dp.toPx()
        drawRoundRect(Neon.Cyan.copy(alpha = 0.18f), Offset(0f, y - track / 2), Size(size.width, track), CornerRadius(track))
        val x = size.width * fraction
        drawRoundRect(Neon.Cyan.copy(alpha = 0.3f), Offset(0f, y - track * 1.5f), Size(x, track * 3), CornerRadius(track))
        drawRoundRect(Neon.Cyan, Offset(0f, y - track / 2), Size(x, track), CornerRadius(track))
        drawCircle(Neon.Orange.copy(alpha = 0.35f), 10.dp.toPx(), Offset(x, y))
        drawCircle(Neon.Orange, 5.dp.toPx(), Offset(x, y))
    }
}

/**
 * Vertical EQ slider, -[range]..+[range] dB. Drag or tap to set; double-tap resets to 0.
 */
@Composable
fun NeonVSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, range: Float = 12f, accent: Color = Neon.Cyan) {
    val change by rememberUpdatedState(onChange)
    fun valueAt(y: Float, h: Float) = (((1f - y / h) * 2f - 1f) * range).coerceIn(-range, range).let { Math.round(it * 2f) / 2f }
    Canvas(
        modifier
            .width(26.dp)
            .height(120.dp)
            .padding(vertical = 6.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { change(0f) },
                    onTap = { o -> change(valueAt(o.y, size.height.toFloat())) },
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { c, _ -> change(valueAt(c.position.y, size.height.toFloat())) }
            },
    ) {
        val cx = size.width / 2
        val h = size.height
        // tick marks
        for (i in 0..8) {
            val y = h * i / 8f
            drawLine(Neon.Dim.copy(alpha = 0.5f), Offset(cx - 7.dp.toPx(), y), Offset(cx - 4.dp.toPx(), y), 1f)
        }
        drawLine(accent.copy(alpha = 0.25f), Offset(cx, 0f), Offset(cx, h), 3.dp.toPx())
        val y0 = h / 2
        val yv = h * (1f - (value / range + 1f) / 2f)
        val c = if (value >= 0) accent else Neon.Orange
        drawLine(c.copy(alpha = 0.35f), Offset(cx, y0), Offset(cx, yv), 7.dp.toPx())
        drawLine(c, Offset(cx, y0), Offset(cx, yv), 3.dp.toPx())
        val tw = 16.dp.toPx(); val th = 7.dp.toPx()
        drawRoundRect(Neon.Ink, Offset(cx - tw / 2, yv - th / 2), Size(tw, th), CornerRadius(2.dp.toPx()))
        drawRoundRect(c.copy(alpha = 0.4f), Offset(cx - tw / 2 - 2, yv - th / 2 - 2), Size(tw + 4, th + 4), CornerRadius(3.dp.toPx()))
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
