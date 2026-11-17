package com.koro.kororythm.ui.skin

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Neon Grid skin: colours and type. Original design, no film assets. */
object Neon {
    val Bg = Color(0xFF04060B)
    val Panel = Color(0xE6060C16)
    val Cyan = Color(0xFF00E5FF)
    val Orange = Color(0xFFFF6A00)
    val Blue = Color(0xFF4DA3FF)
    val Beak = Color(0xFFFFC93C)
    val Ink = Color(0xFFE8FDFF)
    val Dim = Color(0xFF5E8796)
    val Font: FontFamily = FontFamily.Monospace

    fun style(size: TextUnit = 12.sp, color: Color = Ink, glow: Color? = Cyan, weight: FontWeight = FontWeight.Normal, spacing: TextUnit = 1.sp) =
        TextStyle(
            fontFamily = Font,
            fontSize = size,
            color = color,
            fontWeight = weight,
            letterSpacing = spacing,
            shadow = glow?.let { Shadow(it.copy(alpha = 0.8f), blurRadius = 10f) },
        )
}

/** Three stacked strokes give the soft neon glow around panels and buttons. */
fun Modifier.neonBorder(color: Color = Neon.Cyan, radius: Dp = 10.dp, strength: Float = 1f): Modifier = drawBehind {
    val r = CornerRadius(radius.toPx())
    drawRoundRect(color.copy(alpha = 0.08f * strength), cornerRadius = r, style = Stroke(8.dp.toPx()))
    drawRoundRect(color.copy(alpha = 0.20f * strength), cornerRadius = r, style = Stroke(4.dp.toPx()))
    drawRoundRect(color.copy(alpha = 0.95f * strength), cornerRadius = r, style = Stroke(1.2.dp.toPx()))
}

@Composable
fun NeonText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 12.sp,
    color: Color = Neon.Ink,
    glow: Color? = Neon.Cyan,
    weight: FontWeight = FontWeight.Normal,
    spacing: TextUnit = 1.sp,
    maxLines: Int = 1,
) = Text(
    text, modifier,
    style = Neon.style(size, color, glow, weight, spacing),
    maxLines = maxLines,
    softWrap = maxLines > 1,
    overflow = TextOverflow.Ellipsis,
)

/** Small rectangular button that lights up when pressed or active. */
@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    accent: Color = Neon.Cyan,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val lit = active || pressed
    Box(
        modifier
            .background(if (lit) accent.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(6.dp))
            .neonBorder(accent, 6.dp, if (lit) 1f else 0.5f)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        NeonText(text, size = 11.sp, color = if (lit) Neon.Ink else accent, glow = if (lit) accent else null, weight = FontWeight.Bold)
    }
}

/**
 * A Winamp-style window: title bar with a "shade" button that rolls the panel up into a
 * one-line bar (like Winamp's windowshade mode).
 */
@Composable
fun NeonPanel(
    title: String,
    shaded: Boolean,
    onToggleShade: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Neon.Cyan,
    shadeContent: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(Neon.Panel, RoundedCornerShape(10.dp))
            .neonBorder(accent)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(accent.copy(alpha = 0.45f)))
            NeonText(title, Modifier.padding(horizontal = 8.dp), size = 10.sp, color = accent, spacing = 3.sp, weight = FontWeight.Bold)
            Box(Modifier.weight(1f).height(1.dp).background(accent.copy(alpha = 0.45f)))
            Spacer(Modifier.width(6.dp))
            NeonText(
                if (shaded) "▾" else "▴",
                Modifier.clickable(onClick = onToggleShade).padding(horizontal = 6.dp),
                size = 14.sp, color = accent,
            )
        }
        if (shaded) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, content = shadeContent)
        } else {
            content()
        }
    }
}

/**
 * Background: a perspective neon grid floor that scrolls towards you. [boot] 0..1 draws it
 * outward from the horizon on startup.
 */
@Composable
fun GridBackground(boot: Float, modifier: Modifier = Modifier) {
    val scroll by rememberInfiniteTransition(label = "grid").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3500, easing = LinearEasing), RepeatMode.Restart), label = "scroll",
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(Neon.Bg)
        val horizon = size.height * 0.42f
        val cx = size.width / 2
        val glowH = size.height * 0.25f
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Neon.Cyan.copy(alpha = 0.10f)), startY = horizon - glowH, endY = horizon),
            topLeft = Offset(0f, horizon - glowH), size = Size(size.width, glowH),
        )
        val floorH = size.height - horizon
        clipRect(top = horizon, bottom = horizon + floorH * boot) {
            val rows = 14
            for (i in 0..rows) {
                val p = (i + scroll) / rows
                val y = horizon + floorH * p * p
                drawLine(Neon.Cyan.copy(alpha = 0.08f + 0.30f * p), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            val spread = size.width * 0.32f
            for (j in -12..12) {
                drawLine(Neon.Cyan.copy(alpha = 0.16f), Offset(cx, horizon), Offset(cx + j * spread, size.height), 1.dp.toPx())
            }
        }
        val half = cx * boot
        drawLine(Neon.Cyan.copy(alpha = 0.25f), Offset(cx - half, horizon), Offset(cx + half, horizon), 6.dp.toPx())
        drawLine(Neon.Cyan.copy(alpha = 0.9f), Offset(cx - half, horizon), Offset(cx + half, horizon), 1.5.dp.toPx())
    }
}
