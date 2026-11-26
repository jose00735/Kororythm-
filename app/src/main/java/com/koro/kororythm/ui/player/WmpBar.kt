package com.koro.kororythm.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.SkinId
import com.koro.kororythm.ui.skin.SkinState

/** The orb's colour for each skin (Windows 7 blue on the Neon and Pod skins). */
private fun orbTint(): Color = when (SkinState.current) {
    SkinId.NEON -> Color(0xFF2E8BFF)
    SkinId.POD -> Color(0xFF1E6FE0)
    SkinId.HIFI -> Color(0xFF3FA46A)
    SkinId.CHROME -> Color(0xFFE59A2C)
}

/**
 * PRISM's control bar, drawn like the Windows 7 media player: a smoked-glass strip with a
 * seek line on top, the big round play orb in the middle with ⏮ / ⏭ joined to it as one
 * capsule, stop to the left, shuffle and repeat on the left edge, lyrics on the right.
 */
@Composable
fun WmpBar(st: PlayerUiState, actions: PlayerActions, onLyrics: () -> Unit, modifier: Modifier = Modifier) {
    val tint = orbTint()
    val glass = Brush.verticalGradient(
        0f to Color(0xFF3B4450).copy(alpha = 0.92f),
        0.48f to Color(0xFF1C222A).copy(alpha = 0.92f),
        0.5f to Color(0xFF0B0F14).copy(alpha = 0.94f),
        1f to Color(0xFF151B22).copy(alpha = 0.94f),
    )
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(glass)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.08f))), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        WmpSeek(st.positionMs, st.durationMs, actions::seekTo, tint)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            NeonText(formatTime(st.positionMs), Modifier.width(44.dp), size = 10.sp, color = Color(0xFFC9D3DD), glow = null)
            Spacer(Modifier.weight(1f))
            NeonText(formatTime(st.durationMs), Modifier.width(44.dp), size = 10.sp, color = Color(0xFFC9D3DD), glow = null)
        }
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIcon(TIcon.SHUFFLE, "Shuffle", actions::toggleShuffle, active = st.shuffle, tint = tint)
            Spacer(Modifier.width(4.dp))
            RepeatIcon(st.repeatMode, actions::cycleRepeat, tint)
            Spacer(Modifier.weight(1f))
            GlassIcon(TIcon.STOP, "Stop", actions::stop, tint = tint)
            Spacer(Modifier.width(6.dp))
            // ⏮ ( ▶ ) ⏭: the capsule sits behind the orb
            Box(contentAlignment = Alignment.Center) {
                Row(
                    Modifier.width(150.dp).height(34.dp).clip(RoundedCornerShape(50))
                        .background(Brush.verticalGradient(listOf(Color(0xFF5A6472), Color(0xFF262D36), Color(0xFF12171D), Color(0xFF2A323C))))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50)),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapsuleHalf(TIcon.PREV, actions::previous, tint)
                    CapsuleHalf(TIcon.NEXT, actions::next, tint)
                }
                PlayOrb(st.isPlaying, actions::playPause, tint)
            }
            Spacer(Modifier.weight(1f))
            GlassIcon(TIcon.LYRICS, "Lyrics", onLyrics, tint = tint)
        }
    }
}

/** The big glass play/pause orb. */
@Composable
private fun PlayOrb(playing: Boolean, onClick: () -> Unit, tint: Color, size: Dp = 58.dp) {
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    Box(
        Modifier.size(size).clip(CircleShape)
            .clickable(source, null, onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause" else "Play"; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            val c = center
            // dark rim, coloured body, inner glow from below, glass highlight on top
            drawCircle(Brush.verticalGradient(listOf(Color(0xFFDCE3EA), Color(0xFF3A434E))), r)
            val body = if (down) lerp(tint, Color.Black, 0.25f) else tint
            drawCircle(Brush.radialGradient(listOf(lerp(body, Color.White, 0.35f), body, lerp(body, Color.Black, 0.55f)), Offset(c.x, c.y + r * 0.45f), r * 1.1f), r * 0.88f)
            drawCircle(Brush.radialGradient(listOf(lerp(tint, Color.White, 0.6f).copy(alpha = 0.7f), Color.Transparent), Offset(c.x, c.y + r * 0.7f), r * 0.6f), r * 0.86f)
            drawOval(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.05f)), startY = c.y - r * 0.82f, endY = c.y),
                Offset(c.x - r * 0.62f, c.y - r * 0.82f), Size(r * 1.24f, r * 0.8f),
            )
            val g = r * 0.62f
            translateIcon(if (playing) TIcon.PAUSE else TIcon.PLAY, Offset(c.x - g / 2 + if (playing) 0f else g * 0.08f, c.y - g / 2), g, Color.White)
        }
    }
}

@Composable
private fun CapsuleHalf(icon: TIcon, onClick: () -> Unit, tint: Color) {
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    Box(
        Modifier.width(46.dp).height(34.dp).clickable(source, null, onClick = onClick)
            .background(if (down) tint.copy(alpha = 0.35f) else Color.Transparent)
            .semantics { contentDescription = icon.label; role = Role.Button },
        contentAlignment = if (icon == TIcon.PREV) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Canvas(Modifier.padding(horizontal = 12.dp).size(14.dp)) { drawTransportIcon(icon, Color(0xFFE9EEF3)) }
    }
}

@Composable
private fun GlassIcon(icon: TIcon, label: String, onClick: () -> Unit, active: Boolean = false, tint: Color) {
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    val lit = active || down
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
            .background(if (lit) Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.2f))) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
            .clickable(source, null, onClick = onClick)
            .semantics {
                contentDescription = label; role = Role.Button
                if (icon == TIcon.SHUFFLE) stateDescription = if (active) "On" else "Off"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) { drawTransportIcon(icon, if (lit) Color.White else Color(0xFFB9C4CF)) }
    }
}

/** Repeat: a looped arrow, with a small "1" for repeat-one. */
@Composable
private fun RepeatIcon(mode: Int, onClick: () -> Unit, tint: Color) {
    val on = mode != 0
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
            .background(if (on) Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.2f))) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Repeat"; role = Role.Button; stateDescription = when (mode) { 1 -> "One"; 2 -> "All"; else -> "Off" } },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val c = if (on) Color.White else Color(0xFFB9C4CF)
            val w = size.width; val h = size.height; val sw = w * 0.11f
            drawRoundRect(c, Offset(w * 0.08f, h * 0.22f), Size(w * 0.84f, h * 0.56f), CornerRadius(h * 0.28f), style = Stroke(sw))
            drawPath(Path().apply { moveTo(w * 0.52f, h * 0.06f); lineTo(w * 0.74f, h * 0.22f); lineTo(w * 0.52f, h * 0.38f); close() }, c)
            if (mode == 1) drawLine(c, Offset(w * 0.5f, h * 0.38f), Offset(w * 0.5f, h * 0.66f), sw, StrokeCap.Round)
        }
    }
}

private fun DrawScope.translateIcon(icon: TIcon, at: Offset, side: Float, color: Color) =
    inset(at.x, at.y, size.width - at.x - side, size.height - at.y - side) { drawTransportIcon(icon, color) }

/** Thin seek line with a blue glow and a round thumb; tap or drag to seek. */
@Composable
private fun WmpSeek(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, tint: Color) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val duration by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)
    val f = drag ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Canvas(
        Modifier.fillMaxWidth().height(18.dp)
            .semantics { contentDescription = "Seek" }
            .pointerInput(Unit) { detectTapGestures { o -> if (duration > 0) seek(((o.x / size.width).coerceIn(0f, 1f) * duration).toLong()) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let { if (duration > 0) seek((it * duration).toLong()) }; drag = null },
                    onDragCancel = { drag = null },
                ) { ch, _ -> drag = (ch.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        val y = size.height / 2; val t = 2.dp.toPx(); val x = size.width * f
        drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(0f, y - t), Size(size.width, t * 2), CornerRadius(t))
        drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(0f, y + t), Size(size.width, 1f))
        drawRoundRect(Brush.verticalGradient(listOf(lerp(tint, Color.White, 0.5f), tint), startY = y - t, endY = y + t), Offset(0f, y - t), Size(x, t * 2), CornerRadius(t))
        drawCircle(tint.copy(alpha = 0.35f), 7.dp.toPx(), Offset(x, y))
        drawCircle(Brush.verticalGradient(listOf(Color.White, Color(0xFFB7C2CD)), startY = y - 5.dp.toPx(), endY = y + 5.dp.toPx()), 5.dp.toPx(), Offset(x, y))
    }
}
