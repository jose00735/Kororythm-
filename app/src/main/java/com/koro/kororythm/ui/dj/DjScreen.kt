package com.koro.kororythm.ui.dj

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koro.kororythm.dj.LiveFx
import com.koro.kororythm.dj.Transition
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.playback.PlayerViewModel
import com.koro.kororythm.ui.common.NeonScreen
import com.koro.kororythm.ui.player.NeonHSlider
import com.koro.kororythm.ui.player.formatTime
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder

open class DjCallbacks {
    open fun close() {}
    open fun mixNow() {}
    open fun transition(t: Transition) {}
    open fun duration(ms: Int) {}
    open fun autoMix() {}
    open fun tempo(v: Float) {}
    open fun keyLock() {}
    open fun fx(transform: (LiveFx) -> LiveFx) {}
    open fun repeat(beats: Float) {}
    open fun cue(slot: Int) {}
    open fun clearCue(slot: Int) {}
    open fun analyze() {}
    open fun leveling() {}
    open fun bitPerfect() {}
}

@Composable
fun DjScreen(vm: DjViewModel, player: PlayerViewModel, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ps by player.state.collectAsStateWithLifecycle()
    LaunchedEffect(ps.current?.uri) { vm.setCurrent(ps.current?.uri) }
    val cb = object : DjCallbacks() {
        override fun close() = onClose()
        override fun mixNow() = player.next()
        override fun transition(t: Transition) = vm.setTransition(t)
        override fun duration(ms: Int) = vm.setDuration(ms)
        override fun autoMix() = vm.toggleAutoMix()
        override fun tempo(v: Float) = vm.setTempo(v, player)
        override fun keyLock() = vm.toggleKeyLock(player)
        override fun fx(transform: (LiveFx) -> LiveFx) = vm.fx(transform)
        override fun repeat(beats: Float) = vm.setRepeat(beats)
        override fun cue(slot: Int) = vm.cue(slot, ps.positionMs, player)
        override fun clearCue(slot: Int) = vm.clearCue(slot)
        override fun analyze() = vm.analyze()
        override fun leveling() = vm.toggleLeveling()
        override fun bitPerfect() = vm.toggleBitPerfect()
    }
    DjContent(ui, ps, cb)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DjContent(ui: DjUi, ps: PlayerUiState, cb: DjCallbacks) {
    NeonScreen(title = "DJ", onBack = cb::close, backLabel = "◂ PLAYER") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- now playing ----
            Card {
                val cur = ps.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        NeonText(cur?.title ?: "Nothing playing", size = 15.sp, weight = FontWeight.Bold)
                        NeonText(cur?.artist ?: "Load music from the library", size = 11.sp, color = Neon.Dim, glow = null)
                    }
                    Column(
                        Modifier.background(Neon.Lcd, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        val bpm = ui.bpm?.let { "%.1f".format(it * ui.dj.tempo) } ?: "---.-"
                        NeonText(bpm, size = 20.sp, weight = FontWeight.Bold, color = Neon.LcdText, glow = Neon.LcdText)
                        NeonText("BPM", size = 8.sp, color = Neon.LcdText.copy(alpha = 0.6f), glow = null, spacing = 2.sp)
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NeonText("${formatTime(ps.positionMs)} / ${formatTime(ps.durationMs)}", Modifier.weight(1f), size = 11.sp, color = Neon.Cyan, glow = null)
                    val next = ps.queue.getOrNull(ps.currentIndex + 1)
                    NeonText(next?.let { "NEXT: ${it.title}" } ?: "Last track", Modifier.weight(1.4f), size = 10.sp, color = Neon.Dim, glow = null)
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NeonButton(
                        ui.running?.let { "MIXING · ${it.label}" } ?: "MIX NOW ▸",
                        cb::mixNow, active = ui.running != null, accent = Neon.Orange,
                    )
                    Spacer(Modifier.width(8.dp))
                    NeonButton(if (ui.dj.autoMix) "AUTO-MIX ON" else "AUTO-MIX OFF", cb::autoMix, active = ui.dj.autoMix)
                }
            }

            // ---- transition ----
            Card("TRANSITION ON NEXT") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Transition.entries.forEach { t -> NeonButton(t.label, { cb.transition(t) }, active = ui.dj.transition == t) }
                }
                if (ui.dj.transition != Transition.CUT) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        NeonText("LENGTH", Modifier.width(64.dp), size = 9.sp, color = Neon.Dim, glow = null, spacing = 2.sp)
                        NeonHSlider(ui.dj.durationMs / 1000f, { cb.duration((it * 1000).toInt()) }, Modifier.weight(1f), range = 0.5f..8f)
                        NeonText("%.1f s".format(ui.dj.durationMs / 1000f), Modifier.width(52.dp).padding(start = 8.dp), size = 11.sp, color = Neon.Cyan, glow = null)
                    }
                }
                NeonText(
                    when (ui.dj.transition) {
                        Transition.CUT -> "Straight to the next song, like classic Winamp."
                        Transition.CROSSFADE -> "Equal-power fade: the songs overlap at the same loudness."
                        Transition.ECHO_OUT -> "The old song cuts to a beat-synced echo tail while the next one comes in."
                        Transition.FILTER_SWEEP -> "Low-pass closes the old song, high-pass opens the new one."
                        Transition.VINYL_BRAKE -> "The old song slows to a stop like a turntable, then the next drops."
                        Transition.REWIND -> "The last seconds spin backwards, then the next song drops."
                        Transition.SCRATCH -> "A few back-and-forth scratches, then the next song drops."
                    },
                    Modifier.padding(top = 8.dp), size = 10.sp, color = Neon.Dim, glow = null, maxLines = 2,
                )
            }

            // ---- tempo ----
            Card("TEMPO ±8%") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeonHSlider(ui.dj.tempo, cb::tempo, Modifier.weight(1f), range = 0.92f..1.08f, center = 1f, accent = Neon.Orange)
                    NeonText("%+.1f%%".format((ui.dj.tempo - 1f) * 100), Modifier.width(64.dp).padding(start = 8.dp), size = 12.sp, color = Neon.Orange, glow = null)
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeonButton("KEY LOCK", cb::keyLock, active = ui.dj.keyLock)
                    NeonButton("RESET", { cb.tempo(1f) })
                }
            }

            // ---- FX pads ----
            Card("FX PADS") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeonButton("ECHO", { cb.fx { it.copy(echo = !it.echo) } }, active = ui.fx.echo, accent = Neon.Orange)
                    NeonButton("FLANGER", { cb.fx { it.copy(flanger = !it.flanger) } }, active = ui.fx.flanger, accent = Neon.Orange)
                    NeonButton("GATE", { cb.fx { it.copy(gate = !it.gate) } }, active = ui.fx.gate, accent = Neon.Orange)
                    NeonButton("BITCRUSH", { cb.fx { it.copy(bitcrush = !it.bitcrush) } }, active = ui.fx.bitcrush, accent = Neon.Orange)
                    NeonButton("REPEAT ½", { cb.repeat(0.5f) }, active = ui.fx.repeatBeats == 0.5f, accent = Neon.Orange)
                    NeonButton("REPEAT ¼", { cb.repeat(0.25f) }, active = ui.fx.repeatBeats == 0.25f, accent = Neon.Orange)
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    NeonText("FILTER", Modifier.width(64.dp), size = 9.sp, color = Neon.Dim, glow = null, spacing = 2.sp)
                    NeonHSlider(ui.fx.filter, { v -> cb.fx { it.copy(filter = if (kotlin.math.abs(v) < 0.06f) 0f else v) } }, Modifier.weight(1f), range = -1f..1f, center = 0f)
                    NeonText(
                        when {
                            ui.fx.filter < 0 -> "LOW"
                            ui.fx.filter > 0 -> "HIGH"
                            else -> "OFF"
                        },
                        Modifier.width(52.dp).padding(start = 8.dp), size = 11.sp, color = Neon.Cyan, glow = null,
                    )
                }
                NeonText(
                    "Echo and repeat follow the song's BPM${if (ui.bpm == null) " (120 until it's analysed)" else ""}.",
                    Modifier.padding(top = 4.dp), size = 10.sp, color = Neon.Dim, glow = null, maxLines = 2,
                )
            }

            // ---- hot cues ----
            Card("HOT CUES") {
                if (ui.trackId == null) {
                    NeonText("Hot cues work on library tracks.", size = 11.sp, color = Neon.Dim, glow = null)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (slot in 0 until 4) {
                            val c = ui.cues.firstOrNull { it.slot == slot }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .background(if (c != null) Neon.Orange.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(8.dp))
                                    .neonBorder(if (c != null) Neon.Orange else Neon.Cyan, 8.dp, if (c != null) 1f else 0.5f)
                                    .combinedClickable(onClick = { cb.cue(slot) }, onLongClick = { cb.clearCue(slot) }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    NeonText("ABCD"[slot].toString(), size = 15.sp, weight = FontWeight.Bold, color = if (c != null) Neon.Orange else Neon.Cyan)
                                    NeonText(c?.let { formatTime(it.positionMs) } ?: "SET", size = 9.sp, color = Neon.Dim, glow = null)
                                }
                            }
                        }
                    }
                    NeonText("Tap an empty pad to save, tap again to jump, long-press to clear.", Modifier.padding(top = 6.dp), size = 10.sp, color = Neon.Dim, glow = null, maxLines = 2)
                }
            }

            // ---- library analysis + output ----
            Card("SOUND") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        NeonText("BPM & loudness: ${ui.analyzed} of ${ui.total} tracks", size = 11.sp, color = Neon.Ink, glow = null)
                        NeonText("Measured in the background after imports.", size = 9.sp, color = Neon.Dim, glow = null)
                    }
                    NeonButton("ANALYZE", cb::analyze)
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        NeonText("Volume levelling", size = 11.sp, color = Neon.Ink, glow = null)
                        NeonText("Turns loud songs down to match quieter ones.", size = 9.sp, color = Neon.Dim, glow = null)
                    }
                    NeonButton(if (ui.dj.leveling) "ON" else "OFF", cb::leveling, active = ui.dj.leveling)
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        NeonText(
                            if (ui.bitPerfectActive) "Bit-perfect · ACTIVE" else "Bit-perfect (USB DAC, Android 14+)",
                            size = 11.sp, color = if (ui.bitPerfectActive) Neon.Orange else Neon.Ink, glow = null,
                        )
                        NeonText(
                            ui.bitPerfectNote ?: "Sends untouched audio to your DAC; EQ, FX and transitions pause.",
                            size = 9.sp, color = Neon.Dim, glow = null, maxLines = 2,
                        )
                    }
                    NeonButton(if (ui.bitPerfectWanted) "ON" else "OFF", cb::bitPerfect, active = ui.bitPerfectWanted, accent = Neon.Orange)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Card(title: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Neon.Panel, RoundedCornerShape(10.dp)).neonBorder(Neon.Cyan, 10.dp, 0.6f).padding(12.dp),
    ) {
        title?.let { NeonText(it, Modifier.padding(bottom = 8.dp), size = 9.sp, color = if (Neon.isHiFi) Neon.Ink else Neon.Cyan, spacing = 2.sp, weight = FontWeight.Bold) }
        content()
    }
}
