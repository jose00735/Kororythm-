package com.koro.kororythm.ui.upgrade

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.tags.Quality
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pickers, Android consent dialogs and the pop-ups of Upgrade quality; draw it over everything. */
@Composable
fun UpgradeHost(vm: UpgradeViewModel, player: PlayerActions) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.picked(it) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.consentResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(state) {
        when (val s = state) {
            is UpgradeState.Pick -> picker.launch(arrayOf("audio/flac", "audio/x-flac", "audio/mpeg", "audio/wav", "audio/x-wav", "audio/*"))
            is UpgradeState.Consent -> consent.launch(IntentSenderRequest.Builder(s.sender).build())
            else -> Unit
        }
    }
    UpgradeOverlay(state, onConfirm = { vm.confirm(player) }, onDismiss = vm::dismiss)
}

@Composable
fun UpgradeOverlay(state: UpgradeState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (state) {
        UpgradeState.Idle, is UpgradeState.Pick, is UpgradeState.Consent -> Unit
        is UpgradeState.Checking -> Dialog { Busy("Measuring the new file…", state.track.title) }
        is UpgradeState.Working -> Dialog { Busy(state.stage, state.track.title) }
        is UpgradeState.Failed -> Dialog(onDismiss) {
            NeonText("UPGRADE FAILED", size = 13.sp, weight = FontWeight.Bold, color = Neon.Orange, spacing = 2.sp)
            NeonText(state.message, Modifier.padding(vertical = 10.dp), size = 12.sp, color = Neon.Ink, glow = null, maxLines = 5)
            NeonButton("OK", onDismiss)
        }
        is UpgradeState.Compare -> Dialog(onDismiss) { CompareCard(state, onConfirm, onDismiss) }
        is UpgradeState.Done -> PowerUp(state, onDismiss)
    }
}

@Composable
private fun Dialog(onDismiss: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
            .clickable(remember { MutableInteractionSource() }, null) { onDismiss?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth()
                .background(Neon.Panel, RoundedCornerShape(12.dp)).neonBorder(Neon.Cyan, 12.dp)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(16.dp),
        ) { content() }
    }
}

@Composable
private fun Busy(stage: String, title: String) {
    val sweep by rememberInfiniteTransition(label = "busy").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    val c = Neon.Orange
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(28.dp).height(28.dp)) { drawArc(c, sweep, 260f, false, style = Stroke(3.dp.toPx())) }
        Spacer(Modifier.width(12.dp))
        Column {
            NeonText(stage, size = 12.sp, weight = FontWeight.Bold, maxLines = 2)
            NeonText(title, size = 10.sp, color = Neon.Dim, glow = null)
        }
    }
}

@Composable
private fun CompareCard(s: UpgradeState.Compare, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val better = s.verdict is Quality.Verdict.Better
    NeonText("✦ UPGRADE QUALITY", size = 13.sp, weight = FontWeight.Bold, color = Neon.Orange, glow = Neon.Orange, spacing = 2.sp)
    NeonText(s.track.title, Modifier.padding(top = 4.dp), size = 11.sp, color = Neon.Dim, glow = null)
    Spacer(Modifier.height(12.dp))
    Meter("NOW", s.old, s.old.effectiveKbps.coerceAtLeast(s.new.effectiveKbps), Neon.Dim)
    Spacer(Modifier.height(8.dp))
    Meter("NEW", s.new, s.old.effectiveKbps.coerceAtLeast(s.new.effectiveKbps), if (better) Neon.Orange else Neon.Dim)
    NeonText(
        if (better) "Sounds better. Tags, cover, lyrics, hot cues and playlists carry over; the old file is replaced."
        else s.verdict.reason,
        Modifier.padding(vertical = 12.dp), size = 11.sp, color = if (better) Neon.Ink else Neon.Orange, glow = null, maxLines = 4,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NeonButton(if (better) "UPGRADE ▸" else "REPLACE ANYWAY", onConfirm, active = better, accent = Neon.Orange)
        NeonButton("CANCEL", onDismiss)
    }
}

@Composable
private fun Meter(label: String, spec: Quality.Spec, max: Double, color: Color) {
    val track = Neon.Dim.copy(alpha = 0.2f)
    Column {
        Row {
            NeonText(label, Modifier.width(40.dp), size = 9.sp, color = Neon.Dim, glow = null, spacing = 2.sp)
            NeonText(spec.label, size = 11.sp, color = color, glow = null)
        }
        val f = if (max > 0) (spec.effectiveKbps / max).toFloat().coerceIn(0.03f, 1f) else 0f
        Canvas(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
        }
    }
}

/** "Power up!" pop-up: light burst, rings, rising sparks and the before → after figures. */
@Composable
fun PowerUp(s: UpgradeState.Done, onDismiss: () -> Unit, frozen: Float? = null) {
    val t = remember { Animatable(frozen ?: 0f) }
    LaunchedEffect(s) { if (frozen == null) t.animateTo(1f, tween(2200, easing = FastOutSlowInEasing)) }
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "r")
    val p = t.value
    // The pop-up is always dark, so the Hi-Fi skin gets brighter accents here.
    val hot = Neon.Orange
    val cool = if (Neon.isHiFi) Color(0xFF6FE7D6) else Neon.Cyan
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f * (p * 3).coerceAtMost(1f)))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height * 0.42f)
            val maxR = size.minDimension * 0.62f
            // rotating rays
            rotate(spin, c) {
                val rays = 18
                for (i in 0 until rays) {
                    val a = (i * 2 * PI / rays).toFloat()
                    val len = maxR * (0.35f + 0.65f * p) * (if (i % 2 == 0) 1f else 0.7f)
                    drawLine(
                        Brush.linearGradient(listOf(hot.copy(alpha = 0.55f * p), Color.Transparent), c, Offset(c.x + cos(a) * len, c.y + sin(a) * len)),
                        c, Offset(c.x + cos(a) * len, c.y + sin(a) * len), strokeWidth = 10.dp.toPx() * (1 - 0.5f * (i % 3) / 2f),
                    )
                }
            }
            // shock rings
            for (k in 0 until 3) {
                val q = ((p * 1.6f) - k * 0.22f).coerceIn(0f, 1f)
                if (q > 0f && q < 1f) drawCircle(if (k == 1) hot else cool, maxR * q, c, alpha = (1 - q) * 0.9f, style = Stroke((6 * (1 - q) + 1).dp.toPx()))
            }
            // core glow
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f * p), hot.copy(alpha = 0.5f * p), Color.Transparent), c, maxR * 0.45f), maxR * 0.45f, c)
            // rising sparks
            for (i in 0 until 26) {
                val seed = (i * 7919 % 1000) / 1000f
                val x = size.width * (0.1f + 0.8f * seed)
                val rise = ((p + seed) % 1f)
                val y = size.height * (0.85f - 0.75f * rise)
                drawCircle(if (i % 3 == 0) cool else hot, (2 + 3 * seed).dp.toPx(), Offset(x, y), alpha = (1 - rise) * p)
            }
        }
        val pop = (p * 2.2f).coerceAtMost(1f)
        Column(
            Modifier.scale(0.6f + 0.4f * pop + 0.06f * sin(p * PI).toFloat()).alpha(pop),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NeonText("POWER UP!", size = 34.sp, weight = FontWeight.Bold, color = Color.White, glow = hot, spacing = 4.sp)
            NeonText(Quality.powerLabel(s.power), Modifier.padding(top = 2.dp), size = 46.sp, weight = FontWeight.Bold, color = hot, glow = hot)
            NeonText("MORE SOUND DATA", size = 10.sp, color = cool, glow = cool, spacing = 3.sp)
            Spacer(Modifier.height(18.dp))
            NeonText(s.title, size = 14.sp, weight = FontWeight.Bold, color = Color.White, glow = null)
            Spacer(Modifier.height(6.dp))
            NeonText(s.old, size = 12.sp, color = Color(0xFFB8C4CC), glow = null)
            NeonText("▼", size = 14.sp, color = hot, glow = hot)
            NeonText(s.new, size = 15.sp, weight = FontWeight.Bold, color = cool, glow = cool)
            s.note?.let { NeonText(it, Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp), size = 10.sp, color = Neon.Orange, glow = null, maxLines = 3) }
            Spacer(Modifier.height(18.dp))
            NeonText("TAP TO CONTINUE", Modifier.alpha(((p - 0.6f) * 2.5f).coerceIn(0f, 1f)), size = 9.sp, color = Color(0xFF8A99A3), glow = null, spacing = 3.sp)
        }
    }
}
