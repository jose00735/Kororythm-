package com.koro.kororythm.devices

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.koro.kororythm.AudioBus

enum class OutputKind(val label: String, val priority: Int) {
    SPEAKER("SPEAKER", 0),
    OTHER("OUTPUT", 1),
    BLUETOOTH("BLUETOOTH", 2),
    WIRED("HEADPHONES", 3),
    USB_DAC("USB DAC", 4),
}

data class OutputDevice(
    val kind: OutputKind,
    val name: String,
    val maxSampleRate: Int? = null,
    val maxBits: Int? = null,
    /** Android 14+ only: the phone offers bit-perfect output to this DAC. */
    val bitPerfectCapable: Boolean = false,
    val id: Int = -1,
) {
    companion object {
        val SPEAKER = OutputDevice(OutputKind.SPEAKER, "Phone speaker")
    }

    /** e.g. "32-bit / 384 kHz", or null if the device didn't say. */
    val formatLabel: String?
        get() {
            val parts = listOfNotNull(
                maxBits?.let { "$it-bit" },
                maxSampleRate?.let { "${formatKhz(it)} kHz" },
            )
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" / ")
        }
}

private fun formatKhz(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000}" else String.format(java.util.Locale.US, "%.1f", hz / 1000.0)

sealed class DeviceEvent {
    data class Plugged(val device: OutputDevice) : DeviceEvent()
    data class Unplugged(val device: OutputDevice) : DeviceEvent()
}

/**
 * Watches audio outputs with AudioManager.registerAudioDeviceCallback, works out the active
 * route (USB DAC > wired > Bluetooth > speaker) and publishes debounced plug/unplug events.
 */
class DeviceWatcher(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val present = LinkedHashMap<Int, OutputDevice>()
    private var route: OutputDevice = OutputDevice.SPEAKER
    private var registeredAt = 0L

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            added.filter { it.isSink }.mapNotNull(::describe).forEach { present[it.id] = it }
            scheduleRouteCheck()
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            removed.forEach { present.remove(it.id) }
            scheduleRouteCheck()
        }
    }

    private val routeCheck = Runnable { checkRoute() }

    fun start() {
        registeredAt = SystemClock.elapsedRealtime()
        audioManager.registerAudioDeviceCallback(callback, handler)
    }

    fun stop() = audioManager.unregisterAudioDeviceCallback(callback)

    /** Debounce: USB DACs often appear as several devices in a burst. */
    private fun scheduleRouteCheck() {
        handler.removeCallbacks(routeCheck)
        handler.postDelayed(routeCheck, DEBOUNCE_MS)
    }

    private fun checkRoute() {
        val newRoute = present.values.maxByOrNull { it.kind.priority } ?: OutputDevice.SPEAKER
        val old = route
        route = newRoute
        AudioBus.output.value = newRoute
        // Devices already connected at app start are not "plugged in" — no animation.
        if (SystemClock.elapsedRealtime() - registeredAt < STARTUP_QUIET_MS) return
        if (newRoute.kind == old.kind && newRoute.name == old.name) return
        val event = if (newRoute.kind.priority > old.kind.priority) {
            DeviceEvent.Plugged(newRoute)
        } else {
            DeviceEvent.Unplugged(old)
        }
        AudioBus.deviceEvents.tryEmit(event)
    }

    private fun describe(info: AudioDeviceInfo): OutputDevice? {
        val kind = when (info.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> OutputKind.USB_DAC
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> OutputKind.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputKind.BLUETOOTH
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.SPEAKER
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> OutputKind.OTHER
            else -> if (Build.VERSION.SDK_INT >= 31 &&
                (info.type == AudioDeviceInfo.TYPE_BLE_HEADSET || info.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
            ) OutputKind.BLUETOOTH else null
        } ?: return null

        val rates = info.sampleRates
        val maxRate = if (rates.isNotEmpty()) rates.max() else null
        val maxBits = info.encodings.maxOfOrNull { enc ->
            when (enc) {
                AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                AudioFormat.ENCODING_PCM_16BIT -> 16
                else -> 0
            }
        }?.takeIf { it > 0 }

        val bitPerfect = kind == OutputKind.USB_DAC && Build.VERSION.SDK_INT >= 34 &&
            runCatching {
                audioManager.getSupportedMixerAttributes(info).any {
                    it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                }
            }.getOrDefault(false)

        val name = info.productName?.toString()?.takeIf { it.isNotBlank() } ?: kind.label
        return OutputDevice(kind, name, maxRate, maxBits, bitPerfect, info.id)
    }

    companion object {
        private const val DEBOUNCE_MS = 500L
        private const val STARTUP_QUIET_MS = 1500L
    }
}
