package com.koro.kororythm.dj

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.koro.kororythm.AudioBus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/** Converts a PCM buffer to floats and back, for the DJ processors. */
@UnstableApi
abstract class FloatPcmProcessor : BaseAudioProcessor() {
    protected var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    protected val bytesPerSample: Int get() = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) 4 else 2

    protected fun readFloats(input: ByteBuffer): Int {
        val b = input.order(ByteOrder.nativeOrder())
        val samples = b.remaining() / bytesPerSample
        if (scratch.size < samples) scratch = FloatArray(samples)
        if (bytesPerSample == 2) for (i in 0 until samples) scratch[i] = b.getShort() / 32768f
        else for (i in 0 until samples) scratch[i] = b.getFloat()
        return samples
    }

    protected fun writeFloats(src: FloatArray, samples: Int) {
        val out = replaceOutputBuffer(samples * bytesPerSample)
        if (bytesPerSample == 2) for (i in 0 until samples) out.putShort((src[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        else for (i in 0 until samples) out.putFloat(src[i])
        out.flip()
    }

    protected fun passThrough(input: ByteBuffer) {
        val out = replaceOutputBuffer(input.remaining())
        out.put(input)
        out.flip()
    }
}

/** Main player: the live effect pads (echo, flanger, gate, bitcrush, filter, beat repeat). */
@UnstableApi
class LiveFxProcessor : FloatPcmProcessor() {
    private var engine: LiveFxEngine? = null

    override fun onFlush() {
        val f = inputAudioFormat
        engine = if (f.sampleRate > 0) LiveFxEngine(f.sampleRate, f.channelCount) else null
    }

    override fun onReset() { engine = null }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val e = engine
        e?.update(AudioBus.fx.value, AudioBus.bpm.value)
        if (e == null || !e.active || AudioBus.bitPerfectActive.value) { passThrough(inputBuffer); return }
        val samples = readFloats(inputBuffer)
        e.process(scratch, samples / inputAudioFormat.channelCount)
        writeFloats(scratch, samples)
    }
}

/**
 * Ghost player: runs the outgoing-song effect. [arm] is called before the ghost is prepared;
 * the effect starts on the next flush (i.e. when the ghost starts playing).
 */
@UnstableApi
class OutroFxProcessor : FloatPcmProcessor() {
    data class Arm(val mode: Transition, val durationMs: Int, val bpm: Float?)

    private var engine: OutroFx? = null
    private var out = FloatArray(0)

    override fun onFlush() {
        val f = inputAudioFormat
        val a = armed.get()
        engine = if (a != null && f.sampleRate > 0) OutroFx(a.mode, f.sampleRate, f.channelCount, a.durationMs, a.bpm) else null
        current = engine
    }

    override fun onReset() { engine = null; current = null }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val e = engine ?: run { passThrough(inputBuffer); return }
        val ch = inputAudioFormat.channelCount
        val samples = readFloats(inputBuffer)
        val frames = samples / ch
        e.push(scratch, frames)
        if (out.size < samples) out = FloatArray(samples)
        val n = e.pull(out, frames) // paced by the input: one output frame per input frame
        if (n == 0) { inputBuffer.position(inputBuffer.limit()); return } // still collecting history
        writeFloats(out, n * ch)
    }

    companion object {
        private val armed = AtomicReference<Arm?>(null)
        @Volatile var current: OutroFx? = null
            private set

        fun arm(mode: Transition, durationMs: Int, bpm: Float?) = armed.set(Arm(mode, durationMs, bpm))
        fun disarm() { armed.set(null) }
    }
}
