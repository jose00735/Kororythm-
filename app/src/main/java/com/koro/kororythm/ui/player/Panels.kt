package com.koro.kororythm.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.koro.kororythm.AudioBus
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.koro.kororythm.devices.OutputDevice
import com.koro.kororythm.devices.OutputKind
import com.koro.kororythm.dsp.EqEngine
import com.koro.kororythm.dsp.EqPresets
import com.koro.kororythm.dsp.EqSettings
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.TrackInfo
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonPanel
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.SkinState
import com.koro.kororythm.ui.skin.drawParrot
import com.koro.kororythm.ui.skin.neonBorder
import com.koro.kororythm.ui.viz.VisualizerView
import com.koro.kororythm.ui.viz.VizMode
import com.koro.kororythm.ui.viz.VizState

fun outputColor(kind: OutputKind): Color = when (kind) {
    OutputKind.USB_DAC -> Neon.Orange
    OutputKind.WIRED -> Neon.Cyan
    OutputKind.BLUETOOTH -> Neon.Blue
    else -> Neon.Dim
}

/** Lamp showing where the sound is going right now. */
@Composable
fun OutputLamp(output: OutputDevice, modifier: Modifier = Modifier) {
    val c = outputColor(output.kind)
    val text = buildString {
        append(output.kind.label)
        if (output.kind == OutputKind.USB_DAC) output.formatLabel?.let { append(" · ").append(it) }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) {
            drawCircle(c.copy(alpha = 0.35f), size.minDimension / 2)
            drawCircle(c, size.minDimension / 4)
        }
        Spacer(Modifier.width(5.dp))
        NeonText(text, size = 9.sp, color = c, glow = c, spacing = 1.5.sp, weight = FontWeight.Bold)
    }
}

private fun streamLine(t: TrackInfo?): String {
    if (t == null) return "— kbps · — kHz"
    val parts = mutableListOf<String>()
    parts += t.bitrateKbps?.let { "$it kbps" } ?: "— kbps"
    parts += t.sampleRate?.let { if (it % 1000 == 0) "${it / 1000} kHz" else "%.1f kHz".format(it / 1000.0) } ?: "— kHz"
    t.bits?.let { parts += "$it bit" }
    t.codec?.let { parts += it }
    return parts.joinToString(" · ")
}

// ------------------------------------------------------------------ main panel

@Composable
fun MainPanel(
    st: PlayerUiState,
    viz: VizState,
    output: OutputDevice,
    vm: PlayerActions,
    onAddFiles: () -> Unit,
    shaded: Boolean,
    onToggleShade: () -> Unit,
    modifier: Modifier = Modifier,
    onEditTags: (String) -> Unit = {},
    onOpenDj: () -> Unit = {},
    onLyrics: () -> Unit = {},
) {
    var vizMode by rememberSaveable { mutableStateOf(VizMode.BARS) }
    var remaining by rememberSaveable { mutableStateOf(false) }
    val cur = st.current
    val time = if (remaining && st.durationMs > 0) "-" + formatTime(st.durationMs - st.positionMs) else formatTime(st.positionMs)
    val marquee = cur?.let { "${st.currentIndex + 1}. ${listOfNotNull(it.artist, it.title).joinToString(" - ")} (${formatTime(it.durationMs)})" }
        ?: "KORORYTHM  ·  TAP EJECT TO LOAD FLAC OR MP3"

    NeonPanel(
        title = "KORORYTHM",
        shaded = shaded,
        onToggleShade = onToggleShade,
        modifier = modifier,
        shadeContent = {
            NeonText(time, size = 14.sp, weight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            NeonText(marquee, Modifier.weight(1f).basicMarquee(), size = 11.sp, color = Neon.Cyan)
            Spacer(Modifier.width(8.dp))
            TransportButton(if (st.isPlaying) TIcon.PAUSE else TIcon.PLAY, vm::playPause, size = 30.dp)
            Spacer(Modifier.width(6.dp))
            TransportButton(TIcon.NEXT, vm::next, size = 30.dp)
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val hifi = Neon.isHiFi
            val lcd = if (hifi) Modifier.background(Neon.Lcd, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp) else Modifier
            Column(lcd.clickable { remaining = !remaining }) {
                NeonText(
                    if (st.isPlaying) "▶ PLAY" else if (st.positionMs > 0) "❚❚ PAUSE" else "■ STOP",
                    size = 9.sp,
                    color = when {
                        hifi -> Neon.LcdText.copy(alpha = if (st.isPlaying) 1f else 0.5f)
                        st.isPlaying -> Neon.Orange
                        else -> Neon.Dim
                    },
                    glow = null, spacing = 2.sp,
                )
                NeonText(time, size = 34.sp, weight = FontWeight.Bold, spacing = 2.sp, color = if (hifi) Neon.LcdText else Neon.Ink, glow = if (hifi) Neon.LcdText else Neon.Cyan)
            }
            Spacer(Modifier.width(12.dp))
            VisualizerView(
                viz, vizMode,
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .background(Neon.Lcd, RoundedCornerShape(4.dp))
                    .clickable { vizMode = if (vizMode == VizMode.BARS) VizMode.SCOPE else VizMode.BARS }
                    .padding(4.dp),
            )
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 6.dp)
                .background(Neon.Lcd, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            NeonText(marquee, Modifier.basicMarquee(), size = 13.sp, color = Neon.LcdText, glow = Neon.LcdText)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            NeonText(streamLine(cur), Modifier.weight(1f), size = 10.sp, color = Neon.Dim, glow = null)
            Spacer(Modifier.width(8.dp))
            OutputLamp(output)
        }
        NeonSeekBar(st.positionMs, st.durationMs, { vm.seekTo(it) }, Modifier.padding(top = 2.dp))
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(TIcon.PREV, vm::previous)
            TransportButton(TIcon.PLAY, vm::play, accent = if (st.isPlaying) Neon.Orange else Neon.Cyan)
            TransportButton(TIcon.PAUSE, vm::pause)
            TransportButton(TIcon.STOP, vm::stop)
            TransportButton(TIcon.NEXT, vm::next)
            Spacer(Modifier.weight(1f))
            TransportButton(TIcon.EJECT, onAddFiles, accent = Neon.Orange)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NeonButton("SHUFFLE", vm::toggleShuffle, active = st.shuffle)
            NeonButton(
                when (st.repeatMode) {
                    Player.REPEAT_MODE_ALL -> "REPEAT ALL"
                    Player.REPEAT_MODE_ONE -> "REPEAT ONE"
                    else -> "REPEAT OFF"
                },
                vm::cycleRepeat,
                active = st.repeatMode != Player.REPEAT_MODE_OFF,
            )
            Spacer(Modifier.weight(1f))
            cur?.let { c -> NeonButton("✎", { onEditTags(c.uri) }) }
            NeonButton("♪ LYRICS", onLyrics)
            NeonButton("DJ", onOpenDj, accent = Neon.Orange)
        }
    }
}

// ------------------------------------------------------------------ equalizer

private val BAND_LABELS = listOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")

@Composable
fun EqPanel(eq: EqSettings, vm: PlayerActions, shaded: Boolean, onToggleShade: () -> Unit, modifier: Modifier = Modifier) {
    NeonPanel(
        title = "EQUALIZER",
        shaded = shaded,
        onToggleShade = onToggleShade,
        modifier = modifier,
        shadeContent = {
            NeonText(
                "EQ ${if (eq.enabled) "ON" else "OFF"} · BOOST ${if (eq.boost) "ON" else "OFF"} · ${eq.presetName.uppercase()}",
                size = 11.sp, color = Neon.Cyan,
            )
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NeonButton("ON", vm::toggleEq, active = eq.enabled)
            NeonButton("BOOST", vm::toggleBoost, active = eq.boost, accent = Neon.Orange)
            NeonButton("PRESET: ${eq.presetName.uppercase()}", {
                val names = EqPresets.all.keys.toList()
                val next = names[(names.indexOf(eq.presetName) + 1).mod(names.size)]
                vm.applyPreset(next)
            })
            Spacer(Modifier.weight(1f))
            NeonButton("RESET", vm::resetEq)
        }
        EqCurve(eq, Modifier.fillMaxWidth().height(28.dp).padding(top = 6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NeonVSlider(eq.preampDb, vm::setPreamp, accent = Neon.Orange)
                NeonText("PRE", size = 8.sp, color = Neon.Dim, glow = null)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(1.dp).height(110.dp).background(Neon.Cyan.copy(alpha = 0.3f)))
            Spacer(Modifier.width(4.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in EqEngine.BAND_FREQS.indices) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        NeonVSlider(eq.bandsDb[i], { vm.setBand(i, it) })
                        NeonText(BAND_LABELS[i], size = 8.sp, color = Neon.Dim, glow = null, spacing = 0.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Winamp's little curve preview above the sliders. */
@Composable
private fun EqCurve(eq: EqSettings, modifier: Modifier) {
    Canvas(modifier) {
        val n = eq.bandsDb.size
        val mid = size.height / 2
        drawLine(Neon.Dim.copy(alpha = 0.4f), Offset(0f, mid), Offset(size.width, mid), 1f)
        val path = Path()
        for (i in 0 until n) {
            val x = size.width * (i + 0.5f) / n
            val y = mid - (eq.bandsDb[i] / EqEngine.MAX_DB) * mid * 0.9f
            if (i == 0) path.moveTo(0f, y)
            path.lineTo(x, y)
            if (i == n - 1) path.lineTo(size.width, y)
        }
        val c = if (eq.enabled) Neon.Cyan else Neon.Dim
        drawPath(path, c.copy(alpha = 0.3f), style = Stroke(4.dp.toPx()))
        drawPath(path, c, style = Stroke(1.2.dp.toPx()))
    }
}

// ------------------------------------------------------------------ playlist

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistPanel(
    st: PlayerUiState,
    vm: PlayerActions,
    onAddFiles: () -> Unit,
    shaded: Boolean,
    onToggleShade: () -> Unit,
    modifier: Modifier = Modifier,
    onSaveQueue: (List<String>) -> Unit = {},
) {
    val summary = "${st.queue.size} TRACKS · ${formatTime(st.totalMs)}"
    NeonPanel(
        title = "PLAYLIST",
        shaded = shaded,
        onToggleShade = onToggleShade,
        modifier = modifier,
        shadeContent = { NeonText(summary, size = 11.sp, color = Neon.Cyan) },
    ) {
        val listState = rememberLazyListState()
        // The list is split into runs: songs from the list you started, and songs you queued on
        // the fly (play next / add to end). Each run gets a header, so the two read apart.
        val runs = remember(st.queue) { queueRuns(st.queue) }
        LaunchedEffect(st.currentIndex, runs) {
            if (st.currentIndex >= 0) {
                val headersBefore = runs.count { it.first <= st.currentIndex }
                listState.animateScrollToItem((st.currentIndex + headersBefore - 1).coerceAtLeast(0))
            }
        }
        var flow by rememberSaveable { mutableStateOf(false) }
        if (flow && st.queue.size > 1) {
            Box(Modifier.fillMaxWidth().height(330.dp)) {
                com.koro.kororythm.ui.library.CoverFlow(
                    st.queue.map { com.koro.kororythm.ui.library.AlbumCard(it.title, it.artist ?: "Unknown artist", listOfNotNull(it.codec, formatTime(it.durationMs)).joinToString(" · "), null, null, it.uri) },
                    onOpen = vm::playAt, onPlay = vm::playAt,
                    initial = st.currentIndex.coerceAtLeast(0), actionLabel = "▶ PLAY", highlight = st.currentIndex,
                )
            }
        } else if (st.queue.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f, fill = false).padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                NeonText("Tap + ADD to load FLAC or MP3 files", size = 12.sp, color = Neon.Dim, glow = null)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).padding(top = 4.dp), state = listState) {
                runs.forEach { run ->
                    item(key = "h${run.first}") { QueueHeader(st, run) }
                    for (i in run) {
                        val t = st.queue[i]
                        item(key = "$i:${t.uri}") { QueueRow(st, vm, i, t) }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeonButton("+ ADD", onAddFiles, accent = Neon.Orange)
            NeonButton("CLEAR", vm::clear)
            if (st.queue.isNotEmpty()) NeonButton("SAVE ☰", { onSaveQueue(st.queue.map { it.uri }) })
            if (st.queue.size > 1) NeonButton(if (flow) "▦" else "◫", { flow = !flow }, active = flow)
            Spacer(Modifier.weight(1f))
            NeonText(summary, size = 10.sp, color = Neon.Dim, glow = null)
        }
        NeonText("Long-press a track to remove it", size = 9.sp, color = Neon.Dim.copy(alpha = 0.7f), glow = null)
    }
}

/** Consecutive index ranges of the queue that share an origin (queued on the fly or not). */
internal fun queueRuns(q: List<com.koro.kororythm.playback.TrackInfo>): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var start = 0
    for (i in 1..q.size) {
        if (i == q.size || q[i].queued != q[start].queued) { if (start < i) out += start until i; start = i }
    }
    return out
}

@Composable
private fun QueueHeader(st: PlayerUiState, run: IntRange) {
    val queued = st.queue[run.first].queued
    val n = run.last - run.first + 1
    val label = when {
        !queued -> "FROM " + (st.source ?: "the playlist").uppercase()
        // A queued run sitting after the started list was added "to the end".
        run.first > 0 && run.last == st.queue.lastIndex && !st.queue[run.first - 1].queued -> "QUEUED BY YOU · AT THE END"
        else -> "QUEUED BY YOU · PLAYS NEXT"
    }
    val c = if (queued) Neon.Orange else Neon.Cyan
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 12.dp).background(c, RoundedCornerShape(2.dp)))
        NeonText(label, Modifier.padding(start = 6.dp).weight(1f), size = 9.sp, color = c, glow = null, spacing = 2.sp, weight = FontWeight.Bold)
        NeonText(if (n == 1) "1 SONG" else "$n SONGS", size = 9.sp, color = Neon.Dim, glow = null)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(st: PlayerUiState, vm: PlayerActions, i: Int, t: com.koro.kororythm.playback.TrackInfo) {
    val current = i == st.currentIndex
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (current) Neon.Orange.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(4.dp))
            .combinedClickable(onClick = { vm.playAt(i) }, onLongClick = { vm.removeAt(i) })
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (current) Neon.Orange else Neon.Ink
        NeonText("${i + 1}.", Modifier.width(30.dp), size = 11.sp, color = Neon.Dim, glow = null)
        Column(Modifier.weight(1f)) {
            NeonText(t.title, size = 12.sp, color = c, glow = if (current) Neon.Orange else null)
            t.artist?.let { NeonText(it, size = 10.sp, color = Neon.Dim, glow = null) }
        }
        NeonText(t.codec ?: "", Modifier.padding(horizontal = 6.dp), size = 9.sp, color = Neon.Dim, glow = null)
        NeonText(formatTime(t.durationMs), size = 11.sp, color = c, glow = null)
    }
}

/** Small header: parrot mark + name. */
@Composable
fun Header(output: OutputDevice, modifier: Modifier = Modifier, onOpenLibrary: () -> Unit = {}) {
    // STYLE opens the skin and layout chooser; the choice is remembered.
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(34.dp)) { drawParrot(Offset.Zero, size.minDimension) }
        Spacer(Modifier.width(8.dp))
        NeonText("KORORYTHM", Modifier.weight(1f).padding(end = 6.dp), size = 16.sp, weight = FontWeight.Bold, spacing = 4.sp)
        // Lit when the DAC is really getting untouched audio; dim when it could (switch it on in DJ ▸ Sound).
        val bpActive by AudioBus.bitPerfectActive.collectAsState()
        if (output.bitPerfectCapable || bpActive) {
            val c = if (bpActive) Neon.Orange else Neon.Dim
            Box(Modifier.neonBorder(c, 4.dp, if (bpActive) 1f else 0.4f).padding(horizontal = 6.dp, vertical = 2.dp)) {
                NeonText(if (bpActive) "BIT-PERFECT" else "BP READY", size = 8.sp, color = c, glow = if (bpActive) c else null, weight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
        }
        com.koro.kororythm.ui.pod.WheelIconButton()
        Spacer(Modifier.width(4.dp))
        NeonButton("STYLE", { StyleSheetState.open = true })
        Spacer(Modifier.width(6.dp))
        NeonButton("LIBRARY", onOpenLibrary, accent = Neon.Orange)
    }
}
