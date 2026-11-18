package com.koro.kororythm

import com.koro.kororythm.devices.DeviceEvent
import com.koro.kororythm.dj.DjSettings
import com.koro.kororythm.dj.LiveFx
import com.koro.kororythm.dj.Transition
import java.util.concurrent.atomic.AtomicReference
import com.koro.kororythm.devices.OutputDevice
import com.koro.kororythm.dsp.EqSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared, thread-safe state between the playback service (audio thread) and the UI.
 * Everything lives in one process, so plain flows are enough.
 */
object AudioBus {
    /** EQ panel settings; read by the audio thread on every buffer. */
    val eq = MutableStateFlow(EqSettings())

    /** True while audio is actually playing (drives the visualizer clock). */
    val playing = MutableStateFlow(false)

    /** Current audio route (speaker, headphones, Bluetooth, USB DAC). */
    val output = MutableStateFlow(OutputDevice.SPEAKER)

    /** Plug / unplug events, debounced, for the animations. */
    val deviceEvents = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 8)

    // ---- DJ ----
    val dj = MutableStateFlow(DjSettings())
    val fx = MutableStateFlow(LiveFx())
    /** Tempo of the current track from the analysis, if known. */
    val bpm = MutableStateFlow<Float?>(null)
    /** The transition running right now, for the UI. */
    val transition = MutableStateFlow<Transition?>(null)
    /** Set just before the main player jumps to the next song; its first samples fade in. */
    val pendingIntro = AtomicReference<Pair<Transition, Int>?>(null)

    // ---- bit-perfect (USB DACs, Android 14+) ----
    /** The user's switch. */
    val bitPerfectWanted = MutableStateFlow(false)
    /** True while the DAC is really receiving untouched audio (the lamp). */
    val bitPerfectActive = MutableStateFlow(false)
    /** Why bit-perfect isn't active, for the UI. */
    val bitPerfectNote = MutableStateFlow<String?>(null)
}
