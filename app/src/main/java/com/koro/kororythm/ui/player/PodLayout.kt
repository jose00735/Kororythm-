package com.koro.kororythm.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.ui.common.ArtThumb
import com.koro.kororythm.ui.pod.PodInk
import com.koro.kororythm.ui.pod.PodMenuList
import com.koro.kororythm.ui.pod.PodMenuState
import com.koro.kororythm.ui.pod.PodResult
import com.koro.kororythm.ui.pod.PodTree
import com.koro.kororythm.ui.pod.RoomCatalog
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.koro.kororythm.ui.skin.BrushedMetal
import com.koro.kororythm.ui.skin.GridBackground
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/** What the POD screen is showing. */
enum class PodMode { NOW, SCRUB, MENU }

/** What the click wheel's ring was used for last (shown briefly on screen). */
private enum class WheelHint { NONE, VOLUME }

/**
 * POD: a pocket-player body with a screen and a click wheel.
 *
 * Wheel: turn it to change the volume (Now Playing), move through the song (after pressing
 * the centre once: scrubbing) or move the highlight (menus). Press MENU for the iPod menus
 * (Music → Playlists, Artists, Albums, Songs, Genres, Decades; Up Next; Shuffle Songs;
 * Settings) and again to go back a level, ⏮ / ⏭ for previous / next, ▶❚❚ to play or pause,
 * the centre to open or play the highlighted row or to toggle scrubbing.
 */
@Composable
fun PodLayout(
    st: PlayerUiState,
    actions: PlayerActions,
    hooks: LayoutHooks,
    startMode: PodMode = PodMode.NOW,
    startVolume: Float? = null,
    menuState: PodMenuState? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    var mode by remember { mutableStateOf(startMode) }
    var hint by remember { mutableStateOf(if (startVolume != null) WheelHint.VOLUME else WheelHint.NONE) }
    var hintAt by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val state by rememberUpdatedState(st)
    val menu = menuState ?: remember { PodMenuState(PodTree(RoomCatalog, actions) { state }) }
    var volume by remember {
        mutableFloatStateOf(
            startVolume ?: audio?.let { it.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / it.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) } ?: 0.5f,
        )
    }

    LaunchedEffect(hintAt) { if (hintAt > 0) { kotlinx.coroutines.delay(1600); hint = WheelHint.NONE } }

    fun tick(dir: Int) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        when (mode) {
            PodMode.NOW -> {
                audio?.let {
                    it.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                    volume = it.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / it.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                }
                hint = WheelHint.VOLUME; hintAt = System.currentTimeMillis()
            }
            PodMode.SCRUB -> actions.seekTo((state.positionMs + dir * 3000L).coerceIn(0, state.durationMs.coerceAtLeast(0)))
            PodMode.MENU -> menu.tick(dir)
        }
    }

    fun choose(index: Int) {
        if (menu.busy) return
        scope.launch { if (menu.select(actions, index) == PodResult.NOW_PLAYING) { mode = PodMode.NOW; menu.home() } }
    }

    fun press(button: WheelButton) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        when (button) {
            // MENU: open the menus from Now Playing; inside them, go up a level (and out at the top)
            WheelButton.MENU -> when (mode) {
                PodMode.MENU -> if (!menu.back()) mode = PodMode.NOW
                else -> { mode = PodMode.MENU; scope.launch { menu.refresh() } }
            }
            WheelButton.PREV -> actions.previous()
            WheelButton.NEXT -> actions.next()
            WheelButton.PLAY -> actions.playPause()
            WheelButton.CENTER -> when (mode) {
                PodMode.NOW -> mode = PodMode.SCRUB
                PodMode.SCRUB -> mode = PodMode.NOW
                PodMode.MENU -> choose(menu.top.cursor)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (Neon.isHiFi) BrushedMetal() else GridBackground(1f)
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LayoutTopBar(hooks)
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val bodyW = minOf(maxWidth, 420.dp, maxHeight * 0.62f)
                val bodyH = minOf(maxHeight, bodyW * 1.66f)
                PodBody(Modifier.size(bodyW, bodyH)) {
                    Column(Modifier.fillMaxSize().padding(bodyW * 0.07f), horizontalAlignment = Alignment.CenterHorizontally) {
                        PodScreen(st, mode, menu, hint, volume, Modifier.fillMaxWidth().height(bodyH * 0.44f), onTap = ::choose)
                        Spacer(Modifier.weight(1f))
                        ClickWheel(Modifier.size(bodyW * 0.74f), onTick = ::tick, onPress = ::press, playing = st.isPlaying)
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun PodBody(modifier: Modifier, content: @Composable () -> Unit) {
    val metal = Neon.metal
    val body = when {
        metal?.chrome == true -> Brush.linearGradient(listOf(Color(0xFFF4F6F8), Color(0xFFAAB1B9), Color(0xFFE7EAED), Color(0xFF8D959E)))
        metal != null -> Brush.verticalGradient(metal.plate)
        else -> Brush.verticalGradient(listOf(Color(0xFF0B1320), Color(0xFF050910)))
    }
    Box(
        modifier
            .background(body, RoundedCornerShape(30.dp))
            .neonBorder(Neon.Cyan, 30.dp, 0.6f),
    ) { content() }
}


/** Screen colours: a white screen on the Pod skin, otherwise the skin's display windows. */
internal class PodColors(val bg: Color, val ink: Color, val dim: Color, val bar: Brush, val barInk: Color, val sel: Brush, val selInk: Color, val fill: Color)

@Composable
internal fun podColors(): PodColors {
    val pod = com.koro.kororythm.ui.skin.SkinState.current == com.koro.kororythm.ui.skin.SkinId.POD
    return if (pod) {
        PodColors(
            bg = Color(0xFFFBFCFD), ink = Color(0xFF111418), dim = Color(0xFF59616B),
            bar = Brush.verticalGradient(listOf(Color(0xFFF1F3F6), Color(0xFFCDD3DA))), barInk = Color(0xFF111418),
            sel = Brush.verticalGradient(listOf(Color(0xFF5AA2F5), Color(0xFF1E6FE0))), selInk = Color.White, fill = Color(0xFF1E6FE0),
        )
    } else {
        val lcdInk = if (Neon.isHiFi) Neon.LcdText else Neon.Ink
        PodColors(
            bg = if (Neon.isHiFi) Neon.Lcd else Color(0xFF050B14), ink = lcdInk, dim = lcdInk.copy(alpha = 0.6f),
            bar = Brush.verticalGradient(listOf(lcdInk.copy(alpha = 0.18f), lcdInk.copy(alpha = 0.08f))), barInk = lcdInk,
            sel = Brush.verticalGradient(listOf(Neon.Orange.copy(alpha = 0.85f), Neon.Orange.copy(alpha = 0.6f))), selInk = Color.White, fill = if (Neon.isHiFi) Neon.LcdText else Neon.Cyan,
        )
    }
}

@Composable
private fun PodScreen(st: PlayerUiState, mode: PodMode, menu: PodMenuState, hint: WheelHint, volume: Float, modifier: Modifier, onTap: (Int) -> Unit) {
    val c = podColors()
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(c.bg)
            .neonBorder(Neon.Dim, 8.dp, 0.6f),
    ) {
        // title bar
        Row(Modifier.fillMaxWidth().background(c.bar).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            NeonText(if (st.isPlaying) "▶" else "❚❚", Modifier.width(18.dp), size = 9.sp, color = c.barInk, glow = null)
            NeonText(
                when (mode) { PodMode.MENU -> menu.top.title; else -> "Now Playing" },
                Modifier.weight(1f), size = 11.sp, weight = FontWeight.Bold, color = c.barInk, glow = null,
            )
            Canvas(Modifier.size(22.dp, 10.dp)) { // battery
                drawRoundRect(c.barInk, size = Size(size.width - 3f, size.height), cornerRadius = CornerRadius(2f), style = Stroke(1.4f))
                drawRect(c.barInk, Offset(size.width - 3f, size.height * 0.3f), Size(3f, size.height * 0.4f))
                drawRect(Color(0xFF4BC15A), Offset(2f, 2f), Size((size.width - 7f) * 0.8f, size.height - 4f))
            }
        }
        if (mode == PodMode.MENU) {
            PodMenuList(menu, PodInk(c.ink, c.dim, c.sel, c.selInk), Modifier.fillMaxSize(), onTap)
            return@Column
        }
        val cur = st.current
        Row(Modifier.fillMaxWidth().weight(1f).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.45f)) { ArtThumb(null, Modifier.fillMaxWidth(), size = 140.dp, audio = cur?.uri) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(0.55f)) {
                NeonText(cur?.title ?: "No song", size = 13.sp, weight = FontWeight.Bold, color = c.ink, glow = null, maxLines = 2)
                NeonText(cur?.artist ?: "", size = 11.sp, color = c.ink, glow = null)
                NeonText(cur?.album ?: "", size = 11.sp, color = c.dim, glow = null)
                Spacer(Modifier.height(6.dp))
                if (st.queue.isNotEmpty()) NeonText("${st.currentIndex + 1} of ${st.queue.size}", size = 10.sp, color = c.dim, glow = null)
            }
        }
        // progress / scrub / volume bar
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            val f = when (hint) {
                WheelHint.VOLUME -> volume
                else -> if (st.durationMs > 0) st.positionMs.toFloat() / st.durationMs else 0f
            }.coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(c.dim.copy(alpha = 0.25f), cornerRadius = r)
                drawRoundRect(Brush.verticalGradient(listOf(c.fill.copy(alpha = 0.7f), c.fill)), size = Size(size.width * f, size.height), cornerRadius = r)
                if (mode == PodMode.SCRUB && hint != WheelHint.VOLUME) {
                    val x = size.width * f
                    val p = Path().apply { moveTo(x, -2f); lineTo(x + 6f, size.height / 2); lineTo(x, size.height + 2f); lineTo(x - 6f, size.height / 2); close() }
                    drawPath(p, c.ink)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                when {
                    hint == WheelHint.VOLUME -> NeonText("🔈  Volume  ${(volume * 100).toInt()}%", size = 10.sp, color = c.ink, glow = null)
                    else -> {
                        NeonText(formatTime(st.positionMs), size = 10.sp, color = c.ink, glow = null)
                        Spacer(Modifier.weight(1f))
                        if (mode == PodMode.SCRUB) NeonText("Scrubbing", size = 10.sp, color = c.fill, glow = null)
                        Spacer(Modifier.weight(1f))
                        NeonText("-" + formatTime((st.durationMs - st.positionMs).coerceAtLeast(0)), size = 10.sp, color = c.ink, glow = null)
                    }
                }
            }
        }
    }
}


enum class WheelButton { MENU, PREV, NEXT, PLAY, CENTER }

/** The click wheel: drag around the ring to scroll, press the ring's labels or the centre. */
@Composable
fun ClickWheel(modifier: Modifier, onTick: (Int) -> Unit, onPress: (WheelButton) -> Unit, playing: Boolean) {
    val tick by rememberUpdatedState(onTick)
    val press by rememberUpdatedState(onPress)
    val pod = com.koro.kororythm.ui.skin.SkinState.current == com.koro.kororythm.ui.skin.SkinId.POD
    val metal = Neon.metal
    val ring = when {
        pod -> Brush.radialGradient(listOf(Color(0xFFF3F4F6), Color(0xFFE2E5E9)))
        metal?.chrome == true -> Brush.sweepGradient(listOf(Color(0xFFE9ECEF), Color(0xFF9EA6AF), Color(0xFFF6F7F9), Color(0xFF8B939C), Color(0xFFE9ECEF)))
        metal != null -> Brush.radialGradient(metal.face)
        else -> Brush.radialGradient(listOf(Color(0xFF0E1A2A), Color(0xFF060C16)))
    }
    // Darker than the classic grey so the labels clear 3:1 on the light ring.
    val label = if (pod || metal != null) Color(0xFF636B75) else Neon.Cyan
    val centerFace = when {
        pod -> Brush.verticalGradient(listOf(Color.White, Color(0xFFEDEFF2)))
        metal != null -> Brush.verticalGradient(metal.face)
        else -> Brush.radialGradient(listOf(Color(0xFF13233A), Color(0xFF07101C)))
    }
    BoxWithConstraints(
        modifier.semantics { contentDescription = "Click wheel. Spin to scroll; press Menu, Previous, Next, Play or the centre" }
            .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val c = Offset(size.width / 2f, size.height / 2f)
                val outer = size.width / 2f
                fun ang(p: Offset) = atan2(p.y - c.y, p.x - c.x)
                var last = ang(down.position)
                var acc = 0f
                var turned = false
                val step = (PI / 12).toFloat() // a tick every 15°
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull() ?: break
                    if (!ch.pressed) {
                        if (!turned) {
                            val d = ch.position - c
                            val dist = hypot(d.x, d.y)
                            val b = when {
                                dist < outer * 0.36f -> WheelButton.CENTER
                                dist > outer -> null
                                kotlin.math.abs(d.y) > kotlin.math.abs(d.x) -> if (d.y < 0) WheelButton.MENU else WheelButton.PLAY
                                else -> if (d.x < 0) WheelButton.PREV else WheelButton.NEXT
                            }
                            b?.let { press(it) }
                        }
                        break
                    }
                    val d0 = ch.position - c
                    if (hypot(d0.x, d0.y) > outer * 0.3f) {
                        val a = ang(ch.position)
                        var delta = a - last
                        if (delta > PI) delta -= (2 * PI).toFloat()
                        if (delta < -PI) delta += (2 * PI).toFloat()
                        last = a
                        acc += delta
                        while (acc > step) { tick(1); acc -= step; turned = true }
                        while (acc < -step) { tick(-1); acc += step; turned = true }
                        if (kotlin.math.abs(acc) > step / 3) turned = true
                    }
                    ch.consume()
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        val wheelSize = minOf(maxWidth, maxHeight)
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Color.Black.copy(alpha = 0.18f), r, center + Offset(0f, 3f))
            drawCircle(ring, r)
            drawCircle(Color.White.copy(alpha = if (pod) 0.9f else 0.25f), r, style = Stroke(1.5f))
            drawCircle(Color.Black.copy(alpha = 0.12f), r * 0.38f + 2f, center + Offset(0f, 1.5f))
            drawCircle(centerFace, r * 0.38f)
            drawCircle(Color.Black.copy(alpha = 0.15f), r * 0.38f, style = Stroke(1f))
            if (metal?.chrome == true) {
                drawCircle(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent), Offset(0f, 0f), Offset(size.width, size.height)), r)
            }
            // ⏮ ⏭ ⏯ drawn as shapes (fonts vary), all on the same ring radius as MENU.
            val s = r * 0.085f
            val rr = r * 0.69f
            fun tri(cx: Float, cy: Float, dir: Float) {
                val p = Path().apply { moveTo(cx - s * dir, cy - s); lineTo(cx + s * dir, cy); lineTo(cx - s * dir, cy + s); close() }
                drawPath(p, label)
            }
            val y = center.y
            val bw = s * 0.45f
            // previous: |◀◀ centred on the left of the ring
            val lx = center.x - rr
            tri(lx - s * 0.35f, y, -1f); tri(lx + s * 1.05f, y, -1f)
            drawRect(label, Offset(lx - s * 1.35f - bw - s * 0.2f, y - s), Size(bw, s * 2))
            // next: ▶▶| mirrored on the right
            val rx = center.x + rr
            tri(rx + s * 0.35f, y, 1f); tri(rx - s * 1.05f, y, 1f)
            drawRect(label, Offset(rx + s * 1.35f + s * 0.2f, y - s), Size(bw, s * 2))
            // play / pause at the bottom
            val by = center.y + rr
            tri(center.x - s * 1.1f, by, 1f)
            drawRect(label, Offset(center.x + s * 0.4f, by - s), Size(bw * 1.15f, s * 2))
            drawRect(label, Offset(center.x + s * 1.3f, by - s), Size(bw * 1.15f, s * 2))
        }
        NeonText("MENU", Modifier.offset(y = -(wheelSize / 2) * 0.69f), size = 13.sp, weight = FontWeight.Bold, color = label, glow = null, spacing = 2.sp)
    }
}
