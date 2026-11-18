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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.size
import android.content.Context
import android.content.SharedPreferences
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

/** The skins Kororythm can wear. Both are original designs, no film or brand assets. */
enum class SkinId(val label: String) { NEON("NEON GRID"), HIFI("HI-FI") }

/** Colour roles every screen draws with. */
class Palette(
    val bg: Color,
    val panel: Color,
    /** Main accent: borders, titles, lit buttons. */
    val cyan: Color,
    /** Second accent: play, current track, warnings. */
    val orange: Color,
    val blue: Color,
    val beak: Color,
    /** Normal text on panels and background. */
    val ink: Color,
    val dim: Color,
    /** Display windows (time, visualizer, marquee, text fields) and their text. */
    val lcd: Color,
    val lcdText: Color,
    /** Text drawn on dark overlays (plug animations). */
    val overlayInk: Color,
    val font: FontFamily,
)

object Skins {
    val neon = Palette(
        bg = Color(0xFF04060B), panel = Color(0xE6060C16), cyan = Color(0xFF00E5FF), orange = Color(0xFFFF6A00),
        blue = Color(0xFF4DA3FF), beak = Color(0xFFFFC93C), ink = Color(0xFFE8FDFF), dim = Color(0xFF5E8796),
        lcd = Color(0x66000000), lcdText = Color(0xFF00E5FF), overlayInk = Color(0xFFE8FDFF), font = FontFamily.Monospace,
    )

    /** Skeuomorphic hi-fi deck: brushed aluminium, green LCD windows, engraved labels. */
    val hifi = Palette(
        bg = Color(0xFFBFC4CA), panel = Color(0xFFDCE0E4), cyan = Color(0xFF127A5B), orange = Color(0xFFD7461A),
        blue = Color(0xFF2B5FD9), beak = Color(0xFFE8A317), ink = Color(0xFF1C2127), dim = Color(0xFF59616A),
        lcd = Color(0xFF17241B), lcdText = Color(0xFF8DFFB4), overlayInk = Color(0xFFF4F6F8), font = FontFamily.SansSerif,
    )

    fun of(id: SkinId) = if (id == SkinId.HIFI) hifi else neon
}

/** The skin in use. Reads are snapshot state, so switching repaints everything. */
object SkinState {
    var current by mutableStateOf(SkinId.NEON)
        private set
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("skin", Context.MODE_PRIVATE)
        current = runCatching { SkinId.valueOf(prefs?.getString("id", null) ?: "NEON") }.getOrDefault(SkinId.NEON)
    }

    fun set(id: SkinId) {
        current = id
        prefs?.edit()?.putString("id", id.name)?.apply()
    }

    fun next() = set(SkinId.entries[(current.ordinal + 1) % SkinId.entries.size])
}

/** Current skin's colours and type (kept under the old name so every screen follows the skin). */
object Neon {
    private val p: Palette get() = Skins.of(SkinState.current)
    val isHiFi: Boolean get() = SkinState.current == SkinId.HIFI
    val Bg get() = p.bg
    val Panel get() = p.panel
    val Cyan get() = p.cyan
    val Orange get() = p.orange
    val Blue get() = p.blue
    val Beak get() = p.beak
    val Ink get() = p.ink
    val Dim get() = p.dim
    val Lcd get() = p.lcd
    val LcdText get() = p.lcdText
    val OverlayInk get() = p.overlayInk
    val Font: FontFamily get() = p.font

    fun style(size: TextUnit = 12.sp, color: Color = Ink, glow: Color? = Cyan, weight: FontWeight = FontWeight.Normal, spacing: TextUnit = 1.sp): TextStyle {
        val shadow = when {
            !isHiFi -> glow?.let { Shadow(it.copy(alpha = 0.8f), blurRadius = 10f) }
            color.luminance() > 0.55f -> glow?.let { Shadow(color.copy(alpha = 0.55f), blurRadius = 6f) } // lit LCD text
            else -> Shadow(Color.White.copy(alpha = 0.6f), Offset(0f, 1.2f), 0f) // engraved into metal
        }
        return TextStyle(
            fontFamily = Font,
            fontSize = size,
            color = color,
            fontWeight = weight,
            letterSpacing = if (isHiFi) spacing * 0.6f else spacing,
            shadow = shadow,
        )
    }
}

/** Neon: three stacked glowing strokes. Hi-Fi: a machined bevel (light top edge, dark bottom). */
fun Modifier.neonBorder(color: Color = Neon.Cyan, radius: Dp = 10.dp, strength: Float = 1f): Modifier = drawBehind {
    val r = CornerRadius(radius.toPx())
    if (Neon.isHiFi) {
        val w = 1.dp.toPx()
        drawRoundRect(Color(0xFF6E757D).copy(alpha = 0.55f + 0.45f * strength.coerceAtMost(1f)), cornerRadius = r, style = Stroke(w))
        drawRoundRect(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.85f), Color.Transparent, Color.Black.copy(alpha = 0.22f))),
            topLeft = Offset(w, w), size = Size(size.width - 2 * w, size.height - 2 * w),
            cornerRadius = CornerRadius((radius.toPx() - w).coerceAtLeast(0f)), style = Stroke(w * 1.4f),
        )
        if (strength >= 1f && color != Neon.Cyan) {
            drawRoundRect(color.copy(alpha = 0.35f), cornerRadius = r, style = Stroke(2.dp.toPx()))
        }
    } else {
        drawRoundRect(color.copy(alpha = 0.08f * strength), cornerRadius = r, style = Stroke(8.dp.toPx()))
        drawRoundRect(color.copy(alpha = 0.20f * strength), cornerRadius = r, style = Stroke(4.dp.toPx()))
        drawRoundRect(color.copy(alpha = 0.95f * strength), cornerRadius = r, style = Stroke(1.2.dp.toPx()))
    }
}

/** Hi-Fi chrome: raised (or pressed-in) brushed button face. */
fun hifiFace(pressed: Boolean): Brush = if (pressed) {
    Brush.verticalGradient(listOf(Color(0xFF9AA1A9), Color(0xFFCDD2D8)))
} else {
    Brush.verticalGradient(listOf(Color(0xFFF7F8FA), Color(0xFFDADDE1), Color(0xFFB4BAC1)))
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
    val face = if (Neon.isHiFi) {
        Modifier.background(hifiFace(pressed), RoundedCornerShape(6.dp))
    } else {
        Modifier.background(if (lit) accent.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(6.dp))
    }
    Box(
        modifier
            .then(face)
            .neonBorder(accent, 6.dp, if (lit) 1f else 0.5f)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (Neon.isHiFi) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Canvas(Modifier.size(6.dp)) { drawCircle(accent); drawCircle(Color.White.copy(alpha = 0.5f), size.minDimension / 5, Offset(size.width * 0.35f, size.height * 0.35f)) }
                    Spacer(Modifier.width(5.dp))
                }
                NeonText(text, size = 11.sp, color = if (active) accent else Neon.Ink, glow = null, weight = FontWeight.Bold)
            }
        } else {
            NeonText(text, size = 11.sp, color = if (lit) Neon.Ink else accent, glow = if (lit) accent else null, weight = FontWeight.Bold)
        }
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
    val plate = if (Neon.isHiFi) {
        Modifier.background(Brush.verticalGradient(listOf(Color(0xFFEDEFF2), Color(0xFFD3D7DC), Color(0xFFC4C9CF))), RoundedCornerShape(10.dp)).drawBehind { drawScrews() }
    } else {
        Modifier.background(Neon.Panel, RoundedCornerShape(10.dp))
    }
    val rule = if (Neon.isHiFi) Neon.Dim.copy(alpha = 0.5f) else accent.copy(alpha = 0.45f)
    Column(
        modifier
            .then(plate)
            .neonBorder(accent)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (Neon.isHiFi) Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(rule))
            NeonText(title, Modifier.padding(horizontal = 8.dp), size = 10.sp, color = if (Neon.isHiFi) Neon.Ink else accent, spacing = 3.sp, weight = FontWeight.Bold)
            Box(Modifier.weight(1f).height(1.dp).background(rule))
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
    if (Neon.isHiFi) {
        BrushedMetal(modifier)
        return
    }
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

/** Hi-Fi background: brushed aluminium with fine horizontal grain and a soft vignette. */
@Composable
fun BrushedMetal(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFD2D6DB), Color(0xFFBCC1C7), Color(0xFFA9AFB6))))
        val step = 2.dp.toPx()
        var y = 0f
        var i = 0
        while (y < size.height) {
            // deterministic pseudo-random grain
            val h = ((i * 1103515245 + 12345) ushr 8) and 0xFF
            val light = h % 2 == 0
            val a = 0.025f + (h % 7) * 0.008f
            drawLine(if (light) Color.White.copy(alpha = a) else Color.Black.copy(alpha = a * 0.7f), Offset(0f, y), Offset(size.width, y), 1f)
            y += step
            i++
        }
        drawRect(
            Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)),
                center = Offset(size.width / 2, size.height * 0.4f), radius = size.maxDimension * 0.75f,
            ),
        )
    }
}

/** Four small slotted screws in the corners of a Hi-Fi panel. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScrews() {
    val r = 3.dp.toPx()
    val inset = 7.dp.toPx()
    listOf(
        Offset(inset, inset), Offset(size.width - inset, inset),
        Offset(inset, size.height - inset), Offset(size.width - inset, size.height - inset),
    ).forEachIndexed { k, c ->
        drawCircle(Brush.radialGradient(listOf(Color(0xFFF2F3F5), Color(0xFF8A9199)), c, r), r, c)
        drawCircle(Color(0xFF5D646C), r, c, style = Stroke(0.6f))
        val a = (k * 47f + 20f) * (Math.PI / 180).toFloat()
        val d = Offset(kotlin.math.cos(a) * r * 0.75f, kotlin.math.sin(a) * r * 0.75f)
        drawLine(Color(0xFF4A5057), c - d, c + d, 1f)
    }
}
