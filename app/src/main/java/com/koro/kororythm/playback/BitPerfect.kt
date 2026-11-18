package com.koro.kororythm.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.koro.kororythm.AudioBus
import com.koro.kororythm.devices.OutputKind
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Bit-perfect output to USB DACs (Android 14+). When it's on and the DAC supports it, Kororythm
 * asks Android for a BIT_PERFECT mixer at the song's own sample rate, turns every effect off,
 * and sends 24-bit songs as 32-bit float (which holds 24-bit samples exactly) instead of
 * cutting them to 16 bits.
 */
@UnstableApi
class BitPerfectController(private val context: Context) {

    private val am = context.getSystemService(AudioManager::class.java)
    private var applied: Pair<Int, Int>? = null // (rate, encoding)
    private var appliedDevice: AudioDeviceInfo? = null

    /** Wants a float (hi-res) path for the current song. Read by [SwitchingAudioSink]. */
    @Volatile var wantFloat = false
        private set

    /**
     * Called when the song or the output changes. Returns true if the audio path must be rebuilt
     * (new sample rate / format), which the service does with a quick re-prepare.
     */
    fun update(platformAttrs: android.media.AudioAttributes, sampleRate: Int?, bits: Int?): Boolean {
        val out = AudioBus.output.value
        if (!AudioBus.bitPerfectWanted.value) return off(null)
        if (Build.VERSION.SDK_INT < 34) return off("Bit-perfect needs Android 14 or newer.")
        if (out.kind != OutputKind.USB_DAC) return off("Plug in a USB DAC to use bit-perfect.")
        if (sampleRate == null) return off("Waiting for a song.")
        return on34(platformAttrs, out.id, sampleRate, bits ?: 16)
    }

    @RequiresApi(34)
    private fun on34(attrs: android.media.AudioAttributes, deviceId: Int, rate: Int, bits: Int): Boolean {
        val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
            ?: return off("The DAC isn't available.")
        val wanted = if (bits > 16) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        if (applied == rate to wanted && appliedDevice?.id == device.id && AudioBus.bitPerfectActive.value) return false
        val choice = am.getSupportedMixerAttributes(device).firstOrNull {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                it.format.sampleRate == rate && it.format.encoding == wanted
        } ?: return off("This DAC/phone doesn't offer bit-perfect at ${rate / 1000.0} kHz ${if (bits > 16) "24-bit" else "16-bit"}.")
        val ok = runCatching { am.setPreferredMixerAttributes(attrs, device, choice) }.getOrDefault(false)
        if (!ok) return off("Android refused the bit-perfect mixer.")
        val changed = applied != rate to wanted || !AudioBus.bitPerfectActive.value
        applied = rate to wanted
        appliedDevice = device
        wantFloat = wanted == AudioFormat.ENCODING_PCM_FLOAT
        AudioBus.bitPerfectActive.value = true
        AudioBus.bitPerfectNote.value = "Bit-perfect: ${rate / 1000.0} kHz ${if (bits > 16) "24-bit" else "16-bit"}. Set the volume on your DAC."
        return changed
    }

    private fun off(note: String?): Boolean {
        val wasOn = AudioBus.bitPerfectActive.value
        if (Build.VERSION.SDK_INT >= 34) appliedDevice?.let { d ->
            runCatching {
                am.clearPreferredMixerAttributes(
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build(), d,
                )
            }
        }
        applied = null
        appliedDevice = null
        wantFloat = false
        AudioBus.bitPerfectActive.value = false
        AudioBus.bitPerfectNote.value = note
        return wasOn
    }
}

/**
 * An AudioSink that switches between the normal path (16-bit, with EQ / DJ / visualizer
 * processors) and the hi-res float path (no processing) each time the player configures it.
 * Built as a dynamic proxy so every AudioSink method is forwarded without listing them.
 */
@UnstableApi
object SwitchingAudioSink {
    private val broadcast = setOf(
        "setListener", "setPlayerId", "setClock", "setAudioAttributes", "setAudioSessionId", "setAuxEffectInfo",
        "setVolume", "setPlaybackParameters", "setSkipSilenceEnabled", "setPreferredDevice", "enableTunnelingV21",
        "disableTunneling", "setOffloadMode", "release", "reset",
    )

    fun create(normal: AudioSink, hiRes: AudioSink, wantFloat: () -> Boolean): AudioSink {
        var active = normal
        return Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { proxy, m, args ->
            val a = args ?: emptyArray()
            try {
                when (m.name) {
                    "configure" -> {
                        val target = if (wantFloat()) hiRes else normal
                        if (target !== active) { active.reset(); active = target }
                        m.invoke(active, *a)
                    }
                    in broadcast -> {
                        val r = m.invoke(active, *a)
                        val other = if (active === normal) hiRes else normal
                        runCatching { m.invoke(other, *a) }
                        r
                    }
                    "equals" -> a.firstOrNull() === proxy
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "SwitchingAudioSink(${if (active === normal) "normal" else "hi-res"})"
                    else -> m.invoke(active, *a)
                }
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as AudioSink
    }
}
