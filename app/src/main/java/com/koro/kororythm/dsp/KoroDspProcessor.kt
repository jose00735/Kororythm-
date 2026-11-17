package com.koro.kororythm.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.koro.kororythm.AudioBus
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 audio processor that runs the 10-band EQ, preamp and Boost profile.
 * Accepts 16-bit and float PCM and outputs the same format. When the EQ is flat
 * and Boost is off, samples pass through untouched.
 */
@UnstableApi
class KoroDspProcessor : BaseAudioProcessor() {

    private var engine: EqEngine? = null
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        val fmt = inputAudioFormat
        engine = if (fmt.sampleRate > 0) EqEngine(fmt.sampleRate, fmt.channelCount) else null
    }

    override fun onReset() {
        engine = null
        scratch = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val eng = engine
        val settings = AudioBus.eq.value
        eng?.update(settings)

        if (eng == null || eng.isBypassed) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val fmt = inputAudioFormat
        inputBuffer.order(ByteOrder.nativeOrder())
        if (fmt.encoding == C.ENCODING_PCM_16BIT) {
            val samples = size / 2
            ensureScratch(samples)
            for (i in 0 until samples) scratch[i] = inputBuffer.getShort() / 32768f
            eng.process(scratch, samples / fmt.channelCount)
            for (i in 0 until samples) {
                val v = (scratch[i] * 32767f).toInt().coerceIn(-32768, 32767)
                out.putShort(v.toShort())
            }
        } else {
            val samples = size / 4
            ensureScratch(samples)
            for (i in 0 until samples) scratch[i] = inputBuffer.getFloat()
            eng.process(scratch, samples / fmt.channelCount)
            for (i in 0 until samples) out.putFloat(scratch[i])
        }
        out.flip()
    }

    private fun ensureScratch(n: Int) {
        if (scratch.size < n) scratch = FloatArray(n)
    }
}
