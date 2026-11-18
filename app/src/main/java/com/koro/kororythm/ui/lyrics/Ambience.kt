package com.koro.kororythm.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import com.koro.kororythm.ui.viz.VizState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Music energy in three bands, 0..1, smoothed. */
class Energy {
    var bass by mutableFloatStateOf(0f)
    var mid by mutableFloatStateOf(0f)
    var high by mutableFloatStateOf(0f)

    fun feed(bars: FloatArray) {
        if (bars.isEmpty()) return
        fun avg(from: Int, to: Int): Float {
            var s = 0f; var n = 0
            for (i in from until minOf(to, bars.size)) { s += bars[i]; n++ }
            return if (n == 0) 0f else s / n
        }
        val n = bars.size
        // rise fast, fall slowly, like a VU meter
        fun ease(old: Float, new: Float) = if (new > old) old + (new - old) * 0.35f else old + (new - old) * 0.06f
        bass = ease(bass, avg(0, n / 10))
        mid = ease(mid, avg(n / 10, n * 4 / 10))
        high = ease(high, avg(n * 4 / 10, n))
    }
}

/**
 * Windows-Media-Player-"Ambience"-style backdrop: slow ribbons of light, drifting glow orbs
 * and rising sparks. Bass swells the ribbons, mids breathe the orbs, highs light the sparks.
 * Kept dim so the lyrics stay readable.
 */
@Composable
fun Ambience(viz: VizState?, accentA: Color, accentB: Color, modifier: Modifier = Modifier, frozenTime: Float? = null) {
    val preview = LocalInspectionMode.current
    val energy = remember { Energy() }
    val time by produceState(frozenTime ?: 0f) {
        if (frozenTime != null || preview) return@produceState
        var start = -1L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (start < 0) start = now
                value = (now - start) / 1e9f
                viz?.let { energy.feed(it.bars) }
            }
        }
    }
    val violet = Color(0xFF8C63FF)
    Canvas(modifier) {
        val t = time
        val bass = energy.bass
        val mid = energy.mid
        val high = energy.high

        // ---- glow orbs ----
        val orbColors = listOf(accentA, accentB, violet, accentA, violet)
        orbColors.forEachIndexed { i, c ->
            val ph = i * 1.7f
            val cx = size.width * (0.5f + 0.38f * sin(t * 0.11f * (1 + i * 0.13f) + ph))
            val cy = size.height * (0.5f + 0.36f * cos(t * 0.09f * (1 + i * 0.17f) + ph * 1.3f))
            val r = size.minDimension * (0.28f + 0.06f * sin(t * 0.4f + ph) + 0.22f * mid)
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = 0.16f + 0.18f * mid), Color.Transparent), Offset(cx, cy), r),
                r, Offset(cx, cy),
            )
        }

        // ---- ribbons ----
        val ribbons = listOf(accentA, violet, accentB, accentA)
        ribbons.forEachIndexed { i, c ->
            ribbon(t, i, c, bass)
        }

        // ---- sparks ----
        for (i in 0 until 40) {
            val seed = ((i * 7919) % 997) / 997f
            val speed = 0.03f + 0.05f * seed
            val rise = ((t * speed + seed) % 1f)
            val x = size.width * (seed + 0.06f * sin(t * 0.7f + i)) % size.width
            val y = size.height * (1f - rise)
            val tw = 0.5f + 0.5f * sin(t * 3f + i * 2.1f)
            val a = (0.05f + 0.55f * high) * tw * (1f - rise)
            drawCircle(if (i % 3 == 0) accentB else accentA, (1.2f + 2.2f * seed) * density, Offset(x, y), alpha = a.coerceIn(0f, 0.7f))
        }

        // ---- console scanlines ----
        val step = 3 * density
        var y = 0f
        while (y < size.height) {
            drawLine(Color.Black.copy(alpha = 0.10f), Offset(0f, y), Offset(size.width, y), density * 0.6f)
            y += step
        }
    }
}

private fun DrawScope.ribbon(t: Float, i: Int, c: Color, bass: Float) {
    val ph = i * 2.3f
    val amp = size.height * (0.08f + 0.05f * sin(t * 0.21f + ph) + 0.16f * bass)
    val baseY = size.height * (0.25f + 0.17f * i) + size.height * 0.05f * sin(t * 0.13f + ph)
    val k1 = (2 * PI / size.width * (0.8f + 0.25f * i)).toFloat()
    val k2 = (2 * PI / size.width * (1.9f + 0.4f * i)).toFloat()
    val path = Path()
    val steps = 48
    for (s in 0..steps) {
        val x = size.width * s / steps
        val y = baseY + amp * sin(k1 * x + t * (0.55f + 0.1f * i) + ph) + amp * 0.35f * sin(k2 * x - t * (0.9f + 0.15f * i))
        if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    // Wide faint strokes under a thin bright core fake a soft glow.
    val widths = floatArrayOf(34f, 18f, 7f, 2f)
    val alphas = floatArrayOf(0.03f, 0.05f, 0.09f, 0.26f)
    for (w in widths.indices) {
        drawPath(path, c.copy(alpha = alphas[w] * (0.7f + 0.9f * bass)), style = Stroke(widths[w] * density * (1f + 0.6f * bass), cap = StrokeCap.Round))
    }
}
