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
import com.koro.kororythm.ui.skin.hifiFace
import com.koro.kororythm.ui.skin.neonBorder
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

enum class TIcon(val label: String) {
    PREV("Previous"), PLAY("Play"), PAUSE("Pause"), STOP("Stop"), NEXT("Next"), EJECT("Open files"),
    SHUFFLE("Shuffle"), LYRICS("Lyrics"),
}

/** Transport button with a hand-drawn neon icon. */
@Composable
fun TransportButton(
    icon: TIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Neon.Cyan,
    size: Dp = 44.dp,
    active: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    val pressed = down || active
    val face = if (Neon.isHiFi) Modifier.background(hifiFace(pressed), RoundedCornerShape(8.dp))
    else Modifier.background(if (pressed) accent.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(8.dp))
    val iconColor = when {
        Neon.isHiFi -> if (active) Neon.Cyan else if (accent == Neon.Cyan) Neon.Ink else accent
        pressed -> Neon.Ink
        else -> accent
    }
    Box(
        modifier
            .size(size)
            .then(face)
            .neonBorder(accent, 8.dp, if (pressed) 1f else 0.55f)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics {
                contentDescription = icon.label
                role = Role.Button
                if (icon == TIcon.SHUFFLE) stateDescription = if (active) "On" else "Off"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.42f)) {
            if (Neon.isHiFi) translate(0f, 1.2f) { drawTransportIcon(icon, Color.White.copy(alpha = 0.6f)) } // engraved highlight
            drawTransportIcon(icon, iconColor)
        }
    }
}

internal fun DrawScope.drawTransportIcon(icon: TIcon, c: Color) {
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
        TIcon.SHUFFLE -> {
            // two crossing paths, each ending in an arrowhead on the right
            val sw = w * 0.11f
            val a = Path().apply { moveTo(0f, h * 0.22f); cubicTo(w * 0.45f, h * 0.22f, w * 0.45f, h * 0.78f, w * 0.82f, h * 0.78f) }
            val b = Path().apply { moveTo(0f, h * 0.78f); cubicTo(w * 0.45f, h * 0.78f, w * 0.45f, h * 0.22f, w * 0.82f, h * 0.22f) }
            drawPath(a, c, style = Stroke(sw, cap = StrokeCap.Round))
            drawPath(b, c, style = Stroke(sw, cap = StrokeCap.Round))
            for (y in floatArrayOf(h * 0.22f, h * 0.78f)) {
                drawPath(Path().apply { moveTo(w * 0.72f, y - h * 0.17f); lineTo(w, y); lineTo(w * 0.72f, y + h * 0.17f); close() }, c)
            }
        }
        TIcon.LYRICS -> {
            // a quaver: head, stem, flag
            drawOval(c, Offset(w * 0.12f, h * 0.66f), Size(w * 0.42f, h * 0.32f))
            drawRect(c, Offset(w * 0.44f, h * 0.04f), Size(w * 0.1f, h * 0.78f))
            val f = Path().apply {
                moveTo(w * 0.5f, h * 0.04f)
                cubicTo(w * 0.62f, h * 0.2f, w * 0.95f, h * 0.24f, w * 0.82f, h * 0.56f)
                cubicTo(w * 0.84f, h * 0.36f, w * 0.68f, h * 0.3f, w * 0.5f, h * 0.26f)
                close()
            }
            drawPath(f, c)
        }
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
        val x = size.width * fraction
        if (Neon.isHiFi) {
            // machined groove, green fill, chrome knob
            drawRoundRect(Color(0xFF3A4047), Offset(0f, y - track), Size(size.width, track * 2), CornerRadius(track))
            drawRoundRect(Color.White.copy(alpha = 0.6f), Offset(0f, y + track), Size(size.width, 1f))
            drawRoundRect(Neon.LcdText, Offset(0f, y - track / 2), Size(x, track), CornerRadius(track))
            val kw = 18.dp.toPx(); val kh = 14.dp.toPx()
            drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(x - kw / 2 + 1, y - kh / 2 + 2), Size(kw, kh), CornerRadius(3.dp.toPx()))
            drawRoundRect(hifiFace(false), Offset(x - kw / 2, y - kh / 2), Size(kw, kh), CornerRadius(3.dp.toPx()))
            drawLine(Color(0xFF59616A), Offset(x, y - kh / 2 + 3), Offset(x, y + kh / 2 - 3), 1.5f)
            return@Canvas
        }
        drawRoundRect(Neon.Cyan.copy(alpha = 0.18f), Offset(0f, y - track / 2), Size(size.width, track), CornerRadius(track))
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
        val y0 = h / 2
        val yv = h * (1f - (value / range + 1f) / 2f)
        if (Neon.isHiFi) {
            drawLine(Color(0xFF2E3338), Offset(cx, 0f), Offset(cx, h), 4.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.6f), Offset(cx + 2.dp.toPx(), 0f), Offset(cx + 2.dp.toPx(), h), 1f)
            val tw = 20.dp.toPx(); val th = 11.dp.toPx()
            drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(cx - tw / 2 + 1, yv - th / 2 + 2), Size(tw, th), CornerRadius(2.dp.toPx()))
            drawRoundRect(hifiFace(false), Offset(cx - tw / 2, yv - th / 2), Size(tw, th), CornerRadius(2.dp.toPx()))
            drawLine(if (value >= 0) accent else Neon.Orange, Offset(cx - tw / 2 + 3, yv), Offset(cx + tw / 2 - 3, yv), 2f)
            return@Canvas
        }
        drawLine(accent.copy(alpha = 0.25f), Offset(cx, 0f), Offset(cx, h), 3.dp.toPx())
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

/**
 * Horizontal neon slider for the DJ panel. [center] draws a detent mark (e.g. 0 % tempo);
 * double-tap jumps back to it.
 */
@Composable
fun NeonHSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    center: Float? = null,
    accent: Color = Neon.Cyan,
) {
    val change by rememberUpdatedState(onChange)
    val lo = range.start; val hi = range.endInclusive
    fun at(x: Float, w: Float) = (lo + (x / w).coerceIn(0f, 1f) * (hi - lo))
    Canvas(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(lo, hi, center) {
                detectTapGestures(
                    onDoubleTap = { center?.let { change(it) } },
                    onTap = { o -> change(at(o.x, size.width.toFloat())) },
                )
            }
            .pointerInput(lo, hi) {
                detectHorizontalDragGestures { c, _ -> change(at(c.position.x, size.width.toFloat())) }
            },
    ) {
        val y = size.height / 2
        val f = ((value - lo) / (hi - lo)).coerceIn(0f, 1f)
        val x = size.width * f
        val track = 3.dp.toPx()
        if (Neon.isHiFi) {
            drawRoundRect(Color(0xFF3A4047), Offset(0f, y - track), Size(size.width, track * 2), CornerRadius(track))
        } else {
            drawRoundRect(accent.copy(alpha = 0.18f), Offset(0f, y - track / 2), Size(size.width, track), CornerRadius(track))
        }
        val cx = center?.let { size.width * ((it - lo) / (hi - lo)) } ?: 0f
        center?.let { drawLine(Neon.Dim, Offset(cx, y - 9.dp.toPx()), Offset(cx, y + 9.dp.toPx()), 1.5f) }
        val from = minOf(cx, x); val to = maxOf(cx, x)
        drawRoundRect(if (Neon.isHiFi) Neon.LcdText else accent, Offset(from, y - track / 2), Size(to - from, track), CornerRadius(track))
        if (Neon.isHiFi) {
            val kw = 18.dp.toPx(); val kh = 16.dp.toPx()
            drawRoundRect(hifiFace(false), Offset(x - kw / 2, y - kh / 2), Size(kw, kh), CornerRadius(3.dp.toPx()))
            drawLine(Color(0xFF59616A), Offset(x, y - kh / 2 + 3), Offset(x, y + kh / 2 - 3), 1.5f)
        } else {
            drawCircle(Neon.Orange.copy(alpha = 0.35f), 11.dp.toPx(), Offset(x, y))
            drawCircle(Neon.Orange, 6.dp.toPx(), Offset(x, y))
        }
    }
}
