package com.koro.kororythm.ui.anim

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koro.kororythm.devices.DeviceEvent
import com.koro.kororythm.devices.OutputDevice
import com.koro.kororythm.devices.OutputKind
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.Parrot
import com.koro.kororythm.ui.skin.drawParrot
import com.koro.kororythm.ui.skin.neonBorder

private fun seg(v: Float, from: Float, to: Float) = ((v - from) / (to - from)).coerceIn(0f, 1f)
private fun ease(x: Float) = FastOutSlowInEasing.transform(x)

/** Plays the right animation for a plug / unplug event, then calls [onDone]. */
@Composable
fun DeviceOverlay(event: DeviceEvent, onDone: () -> Unit) {
    when (event) {
        is DeviceEvent.Plugged -> when (event.device.kind) {
            OutputKind.USB_DAC -> PowerSurge(event.device, reverse = false, onDone)
            OutputKind.WIRED, OutputKind.BLUETOOTH -> WingsUp(event.device, reverse = false, onDone)
            else -> ScanLine(onDone)
        }
        is DeviceEvent.Unplugged -> when (event.device.kind) {
            OutputKind.USB_DAC -> PowerSurge(event.device, reverse = true, onDone)
            OutputKind.WIRED, OutputKind.BLUETOOTH -> WingsUp(event.device, reverse = true, onDone)
            else -> ScanLine(onDone)
        }
    }
}

/**
 * "Power Surge" (USB DAC): neon lines race in from the screen edges, the DAC's name and max
 * format appear, and a BOOST gauge sweeps to full. Reverse: lines retract.
 */
@Composable
private fun PowerSurge(device: OutputDevice, reverse: Boolean, onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(if (reverse) 1300 else 3000, easing = LinearEasing))
        onDone()
    }
    PowerSurgeFrame(device, reverse, p.value)
}

/** One frame of Power Surge at progress [v] (0..1). */
@Composable
fun PowerSurgeFrame(device: OutputDevice, reverse: Boolean, v: Float) {
    val fade = 1f - seg(v, 0.85f, 1f)
    val lines = if (reverse) 1f - ease(seg(v, 0f, 0.6f)) else ease(seg(v, 0f, 0.35f))
    val panelAlpha = if (reverse) fade * (1f - seg(v, 0.5f, 0.8f)) else seg(v, 0.25f, 0.4f) * fade
    val gauge = if (reverse) 0f else ease(seg(v, 0.35f, 0.75f))

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = 0.75f * fade))
            val rows = 16
            for (i in 0 until rows) {
                val y = size.height * (0.06f + i * 0.058f)
                val c = if (i % 3 == 0) Neon.Orange else Neon.Cyan
                val len = size.width * 0.5f * lines
                val (a, b) = if (i % 2 == 0) Offset(0f, y) to Offset(len, y) else Offset(size.width, y) to Offset(size.width - len, y)
                drawLine(c.copy(alpha = 0.25f * fade), a, b, 6.dp.toPx(), StrokeCap.Round)
                drawLine(c.copy(alpha = fade), a, b, 1.5.dp.toPx(), StrokeCap.Round)
                if (lines in 0.02f..0.98f) drawCircle(Neon.Ink.copy(alpha = fade), 3.dp.toPx(), b)
            }
        }
        Column(
            Modifier
                .graphicsLayer { alpha = panelAlpha }
                .background(Neon.Panel, RoundedCornerShape(14.dp))
                .neonBorder(Neon.Orange, 14.dp)
                .padding(horizontal = 26.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NeonText(if (reverse) "DAC DISCONNECTED" else "DAC CONNECTED", size = 11.sp, color = Neon.Orange, glow = Neon.Orange, spacing = 4.sp, weight = FontWeight.Bold)
            NeonText(device.name.uppercase(), size = 18.sp, weight = FontWeight.Bold, spacing = 2.sp)
            if (!reverse) {
                device.formatLabel?.let { NeonText("UP TO $it", size = 12.sp, color = Neon.Cyan) }
                Spacer(Modifier.height(6.dp))
                BoostGauge(gauge, Modifier.size(140.dp))
                NeonText(
                    if (device.bitPerfectCapable) "BIT-PERFECT READY" else "HI-RES PATH",
                    size = 11.sp, color = if (device.bitPerfectCapable) Neon.Orange else Neon.Cyan, weight = FontWeight.Bold, spacing = 3.sp,
                )
            } else {
                NeonText("PLAYBACK PAUSED", size = 11.sp, color = Neon.Dim, glow = null, spacing = 3.sp)
            }
        }
    }
}

@Composable
private fun BoostGauge(level: Float, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val inset = stroke
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val tl = Offset(inset, inset)
            drawArc(Neon.Cyan.copy(alpha = 0.15f), 135f, 270f, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Neon.Orange.copy(alpha = 0.3f), 135f, 270f * level, false, tl, arcSize, style = Stroke(stroke * 2, cap = StrokeCap.Round))
            drawArc(Neon.Orange, 135f, 270f * level, false, tl, arcSize, style = Stroke(stroke / 2, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NeonText("${(level * 100).toInt()}%", size = 22.sp, weight = FontWeight.Bold, glow = Neon.Orange)
            NeonText("BOOST", size = 10.sp, color = Neon.Orange, glow = null, spacing = 3.sp)
        }
    }
}

/**
 * "Wings Up" (headphones): the parrot spreads its wings, its headphones glow and rings pulse
 * out from the cups. Blue for Bluetooth. Reverse: wings fold.
 */
@Composable
private fun WingsUp(device: OutputDevice, reverse: Boolean, onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(if (reverse) 1200 else 2200, easing = LinearEasing))
        onDone()
    }
    WingsUpFrame(device, reverse, p.value)
}

/** One frame of Wings Up at progress [v] (0..1). */
@Composable
fun WingsUpFrame(device: OutputDevice, reverse: Boolean, v: Float) {
    val bt = device.kind == OutputKind.BLUETOOTH
    val body = if (bt) Neon.Blue else Neon.Cyan
    val fade = 1f - seg(v, 0.82f, 1f)
    val appear = seg(v, 0f, 0.12f)
    val spread = if (reverse) 1f - ease(seg(v, 0.1f, 0.6f)) else ease(seg(v, 0.1f, 0.5f))
    val glow = if (reverse) 0f else seg(v, 0.35f, 0.5f)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawRect(Color.Black.copy(alpha = 0.82f * fade)) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(300.dp)) {
                val side = size.minDimension
                val tl = Offset.Zero
                val k = side / 108f
                if (!reverse) {
                    for (ring in 0 until 3) {
                        val rp = seg(v, 0.4f + ring * 0.12f, 0.8f + ring * 0.06f)
                        if (rp <= 0f || rp >= 1f) continue
                        val r = (8f + rp * 40f) * k
                        val a = (1f - rp) * fade
                        listOf(Parrot.cupLeft, Parrot.cupRight).forEach { cup ->
                            drawCircle(Neon.Orange.copy(alpha = a), r, tl + cup * k, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
                drawParrotAlpha(tl, side, spread, body, glow, appear * fade)
            }
            Column(
                Modifier.graphicsLayer { alpha = seg(v, 0.3f, 0.45f) * fade },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val title = when {
                    reverse && bt -> "BLUETOOTH DISCONNECTED"
                    reverse -> "HEADPHONES OFF"
                    bt -> "BLUETOOTH ON"
                    else -> "HEADPHONES ON"
                }
                NeonText(title, size = 18.sp, weight = FontWeight.Bold, spacing = 4.sp, glow = body)
                Spacer(Modifier.height(4.dp))
                NeonText(if (reverse) "PLAYBACK PAUSED" else device.name.uppercase(), size = 11.sp, color = body, glow = null, spacing = 2.sp)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawParrotAlpha(
    tl: Offset, side: Float, spread: Float, body: Color, glow: Float, alpha: Float,
) {
    if (alpha <= 0f) return
    drawParrot(tl, side, spread, body.copy(alpha = alpha), Neon.Orange.copy(alpha = alpha), glow)
}

/** Speaker / other output: a single cyan scan line sweeps down the screen. */
@Composable
private fun ScanLine(onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
        onDone()
    }
    Canvas(Modifier.fillMaxSize()) {
        val y = size.height * p.value
        drawRect(Neon.Cyan.copy(alpha = 0.12f), Offset(0f, y - 30.dp.toPx()), Size(size.width, 30.dp.toPx()))
        drawLine(Neon.Cyan, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
    }
}
