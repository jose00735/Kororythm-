package com.koro.kororythm.ui.viz

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.koro.kororythm.AudioBus
import com.koro.kororythm.dsp.SpectrumTap
import com.koro.kororythm.dsp.VizFrame
import com.koro.kororythm.ui.skin.Neon
import kotlin.math.max

enum class VizMode { BARS, SCOPE }

@Stable
class VizState {
    var bars by mutableStateOf(FloatArray(64))
    var peaks by mutableStateOf(FloatArray(64))
    var scope by mutableStateOf(FloatArray(128))
}

/**
 * Drains spectrum frames at real-time speed (60 per second) while music plays, so the bars
 * follow the audio instead of racing ahead with the decoder. Bars fall smoothly; peaks hang.
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberVizState(): VizState {
    val st = remember { VizState() }
    LaunchedEffect(Unit) {
        val bars = FloatArray(64)
        val peaks = FloatArray(64)
        var scope = FloatArray(128)
        var last = 0L
        var budget = 0.0
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 1 / 60.0 else (now - last) / 1e9
            last = now
            var newest: VizFrame? = null
            if (AudioBus.playing.value) {
                budget += dt * 60
                while (budget >= 1) {
                    val f = SpectrumTap.poll() ?: break
                    newest = f
                    budget -= 1
                }
                if (budget > 4) budget = 0.0
            } else {
                budget = 0.0
            }
            val target = newest?.bars
            val fall = (dt * 1.8).toFloat()
            for (i in bars.indices) {
                val t = target?.getOrNull(i)
                bars[i] = if (t != null && t > bars[i]) t else max(0f, bars[i] - fall)
                peaks[i] = if (bars[i] >= peaks[i]) bars[i] else max(0f, peaks[i] - fall * 0.35f)
            }
            scope = newest?.scope ?: if (AudioBus.playing.value) scope else FloatArray(scope.size) { scope[it] * 0.85f }
            st.bars = bars.copyOf()
            st.peaks = peaks.copyOf()
            st.scope = scope
        }
    }
    return st
}

/** LED-segment spectrum bars (cyan to orange) with hanging peaks, or an oscilloscope trace. */
@Composable
fun VisualizerView(state: VizState, mode: VizMode, modifier: Modifier = Modifier, barCount: Int = 32) {
    Canvas(modifier) {
        when (mode) {
            VizMode.BARS -> {
                val src = state.bars
                val peaks = state.peaks
                val group = src.size / barCount
                val gap = 2.dp.toPx()
                val bw = (size.width - gap * (barCount - 1)) / barCount
                val seg = 3.dp.toPx()
                val segGap = 1.dp.toPx()
                val segments = (size.height / (seg + segGap)).toInt()
                for (b in 0 until barCount) {
                    var v = 0f; var pk = 0f
                    for (j in 0 until group) { v = max(v, src[b * group + j]); pk = max(pk, peaks[b * group + j]) }
                    val lit = (v * segments).toInt()
                    val x = b * (bw + gap)
                    for (s in 0 until lit) {
                        val y = size.height - (s + 1) * (seg + segGap)
                        val f = s.toFloat() / segments
                        val c = if (Neon.isHiFi) {
                            // classic LED meter: green, then amber, then red
                            when {
                                f < 0.6f -> Color(0xFF39E07A)
                                f < 0.82f -> Color(0xFFFFC23D)
                                else -> Color(0xFFFF4A3D)
                            }
                        } else lerp(Neon.Cyan, Neon.Orange, f)
                        drawRect(c, Offset(x, y), Size(bw, seg))
                    }
                    if (pk > 0.02f) {
                        val py = size.height - pk * segments * (seg + segGap) - seg
                        drawRect(if (Neon.isHiFi) Neon.LcdText else Neon.Ink, Offset(x, py), Size(bw, 1.5.dp.toPx()))
                    }
                }
            }
            VizMode.SCOPE -> {
                val s = state.scope
                val mid = size.height / 2
                val path = Path()
                for (i in s.indices) {
                    val x = size.width * i / (s.size - 1)
                    val y = mid - s[i].coerceIn(-1f, 1f) * mid * 0.95f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val c = Neon.LcdText
                drawLine(c.copy(alpha = 0.15f), Offset(0f, mid), Offset(size.width, mid), 1f)
                drawPath(path, c.copy(alpha = 0.3f), style = Stroke(5.dp.toPx()))
                drawPath(path, c, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
