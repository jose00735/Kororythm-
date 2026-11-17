package com.koro.kororythm

import com.koro.kororythm.devices.DeviceEvent
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
}
