package com.koro.kororythm.dj

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The effect on the OUTGOING song during a transition. Runs on the "ghost" player that keeps
 * playing the old song while the main player starts the next one. Works on interleaved float
 * frames; may output more or fewer frames than it receives (brake slows down, rewind and scratch
 * first collect [Transition.historyMs] of audio, then play it backwards / back and forth).
 */
class OutroFx(
    val mode: Transition,
    private val sampleRate: Int,
    private val channels: Int,
    durationMs: Int,
    bpm: Float? = null,
) {
    private val total = max(1, (durationMs.toLong() * sampleRate / 1000).toInt())
    private val history = (mode.historyMs.toLong() * sampleRate / 1000).toInt()
    private var produced = 0

    // FIFO of input frames
    private var fifo = FloatArray(max(4096, (history + sampleRate) * channels))
    private var fifoFrames = 0
    private var readPos = 0.0 // fractional frame index into the FIFO (brake)

    // echo
    private val echoLen = ((if (bpm != null && bpm > 0) 60.0 / bpm * 0.75 else 0.375) * sampleRate).toInt().coerceAtLeast(1)
    private val echoBuf = FloatArray(echoLen * channels)
    private var echoPos = 0

    // filter sweep (one low-pass per channel)
    private val lp = Array(channels) { OnePoleBiquad() }

    /** True once the effect is audibly playing (after collecting history for rewind / scratch). */
    var started = false
        private set
    val finished: Boolean get() = produced >= total

    fun push(input: FloatArray, frames: Int) {
        ensure((fifoFrames + frames) * channels)
        System.arraycopy(input, 0, fifo, fifoFrames * channels, frames * channels)
        fifoFrames += frames
    }

    /** Writes up to [maxFrames] frames into [out]; returns how many were written. */
    fun pull(out: FloatArray, maxFrames: Int): Int {
        if (mode.historyMs > 0 && !started && fifoFrames < history) return 0 // still collecting
        var n = 0
        while (n < maxFrames) {
            if (produced >= total) { // effect over: silence
                for (c in 0 until channels) out[n * channels + c] = 0f
                n++
                continue
            }
            val t = produced.toDouble() / total
            val ok = when (mode) {
                Transition.CUT -> { silence(out, n); true }
                Transition.CROSSFADE -> passthrough(out, n, cos(t * PI / 2).toFloat()) // equal-power fade out
                Transition.ECHO_OUT -> echo(out, n, t)
                Transition.FILTER_SWEEP -> filter(out, n, t)
                Transition.VINYL_BRAKE -> brake(out, n, t)
                Transition.REWIND -> rewind(out, n, t)
                Transition.SCRATCH -> scratch(out, n, t)
            }
            if (!ok) break
            produced++
            n++
        }
        if (n > 0) started = true
        compact()
        return n
    }

    // ---- modes ----

    private fun silence(out: FloatArray, n: Int) { for (c in 0 until channels) out[n * channels + c] = 0f }

    /** Takes the next input frame (rate 1) and scales it. */
    private fun passthrough(out: FloatArray, n: Int, gain: Float): Boolean {
        val i = readPos.toInt()
        if (i >= fifoFrames) return false
        for (c in 0 until channels) out[n * channels + c] = fifo[i * channels + c] * gain
        readPos += 1.0
        return true
    }

    private fun echo(out: FloatArray, n: Int, t: Double): Boolean {
        val i = readPos.toInt()
        if (i >= fifoFrames) return false
        val inGain = if (t < 0.25) (1 - t / 0.25).toFloat() else 0f // the song itself fades fast…
        val outGain = if (t > 0.6) (1 - (t - 0.6) / 0.4).toFloat() else 1f // …the echo tail rings on
        for (c in 0 until channels) {
            val x = fifo[i * channels + c] * inGain
            val d = echoBuf[echoPos * channels + c]
            val y = x + d * 0.6f
            echoBuf[echoPos * channels + c] = y
            out[n * channels + c] = (x + d * 0.8f) * outGain
        }
        echoPos = (echoPos + 1) % echoLen
        readPos += 1.0
        return true
    }

    private fun filter(out: FloatArray, n: Int, t: Double): Boolean {
        val i = readPos.toInt()
        if (i >= fifoFrames) return false
        if (produced % 64 == 0) {
            val cutoff = 18000.0 * (150.0 / 18000.0).pow(t) // exponential sweep 18 kHz -> 150 Hz
            lp.forEach { it.setLowPass(sampleRate, min(cutoff, sampleRate * 0.45), 0.9) }
        }
        val gain = if (t > 0.7) (1 - (t - 0.7) / 0.3).toFloat() else 1f
        for (c in 0 until channels) out[n * channels + c] = (lp[c].process(fifo[i * channels + c].toDouble()) * gain).toFloat()
        readPos += 1.0
        return true
    }

    /** Turntable power-off: playback speed falls to zero, pitch with it. */
    private fun brake(out: FloatArray, n: Int, t: Double): Boolean {
        val rate = (1 - t).pow(1.6)
        if (!interp(out, n, readPos, if (t > 0.9) ((1 - t) / 0.1).toFloat() else 1f)) return false
        readPos += rate
        return true
    }

    /** Spins the last seconds backwards, speeding up from 1x to 3.5x. */
    private fun rewind(out: FloatArray, n: Int, t: Double): Boolean {
        val dur = total.toDouble() / sampleRate
        // position (seconds back from the end) = integral of speed 1 + 2.5 t
        val back = (t + 1.25 * t * t) * dur * sampleRate
        val pos = history - 2 - back // newest frame we can interpolate from
        if (pos < 0) { silence(out, n); return true }
        interp(out, n, pos, if (t > 0.85) ((1 - t) / 0.15).toFloat() else 1f)
        return true
    }

    /** Back-and-forth record scratches over the last moment of the song. */
    private fun scratch(out: FloatArray, n: Int, t: Double): Boolean {
        val sec = produced.toDouble() / sampleRate
        val period = 0.2
        val amp = 0.25 * sampleRate
        val pos = history - 2 - amp * (1 - cos(2 * PI * sec / period)) / 2
        val gain = (if (t > 0.85) (1 - t) / 0.15 else 1.0).toFloat() * (0.6f + 0.4f * abs(sin(PI * sec / period)).toFloat())
        interp(out, n, pos, gain)
        return true
    }

    private fun abs(v: Double) = if (v < 0) -v else v

    private fun interp(out: FloatArray, n: Int, pos: Double, gain: Float): Boolean {
        val i = pos.toInt()
        if (i + 1 >= fifoFrames) return false
        val f = (pos - i).toFloat()
        for (c in 0 until channels) {
            val a = fifo[i * channels + c]
            val b = fifo[(i + 1) * channels + c]
            out[n * channels + c] = (a + (b - a) * f) * gain
        }
        return true
    }

    // ---- FIFO housekeeping ----

    private fun ensure(samples: Int) {
        if (samples > fifo.size) fifo = fifo.copyOf(max(samples, fifo.size * 2))
    }

    /** Drops consumed frames (forward-reading modes only; rewind/scratch keep their history). */
    private fun compact() {
        if (mode.historyMs > 0) {
            // keep only the history window; extra input after it is never read
            if (fifoFrames > history + 1) fifoFrames = history + 1
            return
        }
        val drop = readPos.toInt()
        if (drop > 8192) {
            System.arraycopy(fifo, drop * channels, fifo, 0, (fifoFrames - drop) * channels)
            fifoFrames -= drop
            readPos -= drop
        }
    }
}

/** Plain RBJ low-/high-pass biquad on doubles, one channel. */
class OnePoleBiquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var z1 = 0.0; private var z2 = 0.0

    fun setLowPass(fs: Int, f: Double, q: Double) {
        val w = 2 * PI * f / fs; val alpha = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + alpha
        b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = b0; a1 = -2 * c / a0; a2 = (1 - alpha) / a0
    }

    fun setHighPass(fs: Int, f: Double, q: Double) {
        val w = 2 * PI * f / fs; val alpha = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + alpha
        b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = b0; a1 = -2 * c / a0; a2 = (1 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }
}

/**
 * The INCOMING song's entry during an overlapping transition: equal-power fade-in, or a
 * high-pass that opens up (filter sweep). Applied on the main player from the new song's
 * first sample.
 */
class IntroFx(private val mode: Transition, private val sampleRate: Int, private val channels: Int, durationMs: Int) {
    private val total = max(1, (durationMs.toLong() * sampleRate / 1000).toInt())
    private var n = 0
    private val hp = Array(channels) { OnePoleBiquad() }
    val finished: Boolean get() = n >= total

    fun process(buf: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            val t = (n.toDouble() / total).coerceAtMost(1.0)
            when (mode) {
                Transition.FILTER_SWEEP -> {
                    if (n % 64 == 0) {
                        val cutoff = 2500.0 * (20.0 / 2500.0).pow(t) // high-pass 2.5 kHz -> 20 Hz
                        hp.forEach { it.setHighPass(sampleRate, cutoff, 0.8) }
                    }
                    val g = sin(min(1.0, t / 0.4) * PI / 2).toFloat()
                    for (c in 0 until channels) { buf[i] = (hp[c].process(buf[i].toDouble()) * g).toFloat(); i++ }
                }
                Transition.ECHO_OUT -> {
                    val g = sin(min(1.0, t / 0.5) * PI / 2).toFloat()
                    for (c in 0 until channels) { buf[i] *= g; i++ }
                }
                else -> {
                    val g = sin(t * PI / 2).toFloat() // equal-power fade in
                    for (c in 0 until channels) { buf[i] *= g; i++ }
                }
            }
            n++
        }
    }

    companion object {
        /** Equal power: the two fades always add up to the same loudness. */
        fun equalPowerSum(t: Double) = sqrt(cos(t * PI / 2).pow(2) + sin(t * PI / 2).pow(2))
    }
}
