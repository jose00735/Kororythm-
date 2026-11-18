package com.koro.kororythm.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.koro.kororythm.AudioBus
import com.koro.kororythm.dj.IntroFx
import com.koro.kororythm.dj.Transition
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 audio processor that runs the 10-band EQ, preamp and Boost profile.
 * Accepts 16-bit and float PCM and outputs the same format. When the EQ is flat
 * and Boost is off, samples pass through untouched.
 */
@UnstableApi
class KoroDspProcessor(private val main: Boolean = true) : BaseAudioProcessor() {

    private var engine: EqEngine? = null
    private var scratch = FloatArray(0)
    private var intro: IntroFx? = null
    private var introReq: Pair<Transition, Int>? = null
    private var introStarted = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        val fmt = inputAudioFormat
        engine = if (fmt.sampleRate > 0) EqEngine(fmt.sampleRate, fmt.channelCount) else null
        if (main) {
            // A DJ transition jumped here: the new song fades in. A second flush (format change)
            // before any audio keeps the same request.
            val req = AudioBus.pendingIntro.getAndSet(null) ?: introReq.takeIf { !introStarted }
            introReq = req
            introStarted = false
            intro = if (req != null && fmt.sampleRate > 0 && req.first.overlap) IntroFx(req.first, fmt.sampleRate, fmt.channelCount, req.second) else null
        }
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
        val fade = intro
        if (fade != null) introStarted = true

        // Bit-perfect: nothing may touch the samples.
        if (eng == null || AudioBus.bitPerfectActive.value || (eng.isBypassed && fade == null)) {
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
            applyIntro(samples / fmt.channelCount)
            for (i in 0 until samples) {
                val v = (scratch[i] * 32767f).toInt().coerceIn(-32768, 32767)
                out.putShort(v.toShort())
            }
        } else {
            val samples = size / 4
            ensureScratch(samples)
            for (i in 0 until samples) scratch[i] = inputBuffer.getFloat()
            eng.process(scratch, samples / fmt.channelCount)
            applyIntro(samples / fmt.channelCount)
            for (i in 0 until samples) out.putFloat(scratch[i])
        }
        out.flip()
    }

    private fun applyIntro(frames: Int) {
        val fade = intro ?: return
        fade.process(scratch, frames)
        if (fade.finished) { intro = null; introReq = null }
    }

    private fun ensureScratch(n: Int) {
        if (scratch.size < n) scratch = FloatArray(n)
    }
}
