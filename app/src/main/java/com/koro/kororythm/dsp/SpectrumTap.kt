package com.koro.kororythm.dsp

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Receives a copy of the processed audio (through Media3's TeeAudioProcessor) and turns it
 * into visualizer frames. Frames are queued; the UI drains them at real-time speed while
 * music plays, so the bars stay roughly in step with what you hear.
 * No microphone permission is needed, unlike the system Visualizer.
 */
@UnstableApi
object SpectrumTap : TeeAudioProcessor.AudioBufferSink {

    private const val MAX_QUEUED = 90 // ~1.5 s of frames

    private val queue = ConcurrentLinkedQueue<VizFrame>()
    @Volatile private var analyzer: SpectrumAnalyzer? = null
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var mono = FloatArray(0)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        analyzer = if (sampleRateHz > 0) SpectrumAnalyzer(sampleRateHz) else null
        queue.clear()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val a = analyzer ?: return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_FLOAT -> 4
            C.ENCODING_PCM_16BIT -> 2
            else -> return
        }
        val frames = b.remaining() / (bytesPerSample * channels)
        if (mono.size < frames) mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += if (bytesPerSample == 2) b.getShort() / 32768f else b.getFloat()
            }
            mono[f] = sum / channels
        }
        a.feed(mono, frames) { frame ->
            queue.add(frame)
            while (queue.size > MAX_QUEUED) queue.poll()
        }
    }

    /** Takes the next frame, or null if none are waiting. */
    fun poll(): VizFrame? = queue.poll()

    fun clear() = queue.clear()
}
