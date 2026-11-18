package com.koro.kororythm.dj

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Track analysis for the DJ module and volume levelling. Fed decoded audio in chunks.
 * - Loudness: EBU R128 integrated loudness (K-weighting, 400 ms blocks, -70 LUFS absolute and
 *   -10 LU relative gates). Gain = -18 LUFS (ReplayGain 2 reference) minus the measured value.
 * - Tempo: onset-strength envelope + autocorrelation, best peak between 70 and 180 BPM.
 */
class TrackAnalyzer(private val sampleRate: Int, private val channels: Int) {

    // K-weighting: high-shelf pre-filter + RLB high-pass (ITU-R BS.1770 coefficients for any rate)
    private val pre = Array(channels) { Iir() }
    private val rlb = Array(channels) { Iir() }

    init {
        // stage 1: shelving filter
        run {
            val f0 = 1681.974450955533; val g = 3.999843853973347; val q = 0.7071752369554196
            val k = tan(PI * f0 / sampleRate)
            val vh = Math.pow(10.0, g / 20); val vb = Math.pow(vh, 0.4996667741545416)
            val a0 = 1 + k / q + k * k
            val b = doubleArrayOf((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0)
            val a = doubleArrayOf(2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
            pre.forEach { it.set(b, a) }
        }
        // stage 2: high-pass
        run {
            val f0 = 38.13547087602444; val q = 0.5003270373238773
            val k = tan(PI * f0 / sampleRate)
            val a0 = 1 + k / q + k * k
            rlb.forEach { it.set(doubleArrayOf(1.0, -2.0, 1.0), doubleArrayOf(2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)) }
        }
    }

    private val blockLen = (0.4 * sampleRate).toInt()
    private val hop = (0.1 * sampleRate).toInt() // 75 % overlap
    private val sub = DoubleArray(channels)
    private var subCount = 0
    private val subPowers = ArrayList<Double>() // mean square per 100 ms, summed over channels
    // tempo: energy envelope at ~86 Hz (hop 512 @ 44.1 kHz)
    private val envHop = max(1, sampleRate / 86)
    private var envAcc = 0.0
    private var envCount = 0
    private val envelope = ArrayList<Float>()
    private val lowpass = Iir().apply {
        val w = 2 * PI * 200.0 / sampleRate; val alpha = sin(w) / (2 * 0.707); val c = cos(w); val a0 = 1 + alpha
        set(doubleArrayOf((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0), doubleArrayOf(-2 * c / a0, (1 - alpha) / a0))
    }
    private val lowpass2 = Iir().apply {
        val w = 2 * PI * 200.0 / sampleRate; val alpha = sin(w) / (2 * 0.707); val c = cos(w); val a0 = 1 + alpha
        set(doubleArrayOf((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0), doubleArrayOf(-2 * c / a0, (1 - alpha) / a0))
    }
    private var full = 0.0
    private var fullCount = 0
    private val envFull = ArrayList<Float>()

    /** Feeds interleaved float samples (-1..1). */
    fun feed(buf: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            var mono = 0.0
            for (c in 0 until channels) {
                val x = buf[i + c].toDouble()
                mono += x
                val y = rlb[c].process(pre[c].process(x))
                sub[c] += y * y
            }
            i += channels
            if (++subCount == hop) {
                subPowers += sub.sum() / hop
                sub.fill(0.0)
                subCount = 0
            }
            mono /= channels
            val low = lowpass2.process(lowpass.process(mono))
            envAcc += low * low
            full += mono * mono
            if (++envCount == envHop) {
                envelope += sqrt(envAcc / envHop).toFloat()
                envFull += sqrt(full / envHop).toFloat()
                envAcc = 0.0; full = 0.0; envCount = 0
            }
        }
    }

    /** Integrated loudness in LUFS, or null if the track is (nearly) silent. */
    fun loudnessLufs(): Double? {
        if (subPowers.size < 4) return null
        val blocks = (0..subPowers.size - 4).map { k -> (subPowers[k] + subPowers[k + 1] + subPowers[k + 2] + subPowers[k + 3]) / 4 }
        fun lufs(p: Double) = -0.691 + 10 * log10(p)
        val abs = blocks.filter { it > 0 && lufs(it) > -70 }
        if (abs.isEmpty()) return null
        val rel = lufs(abs.average()) - 10
        val gated = abs.filter { lufs(it) > rel }
        return lufs(gated.average())
    }

    /** ReplayGain-2-style track gain in dB (towards -18 LUFS). */
    fun gainDb(): Double? = loudnessLufs()?.let { -18.0 - it }

    /** Estimated tempo in BPM, or null if there is no clear beat. */
    fun bpm(): Float? {
        val env = if (envelope.average() > 1e-4) envelope else envFull
        if (env.size < 86 * 6) return null
        val rate = sampleRate.toDouble() / envHop
        // onset strength: half-wave rectified difference of the log envelope
        val onset = DoubleArray(env.size)
        for (k in 1 until env.size) onset[k] = max(0.0, ln(1e-6 + env[k]) - ln(1e-6 + env[k - 1]))
        val mean = onset.average()
        for (k in onset.indices) onset[k] -= mean
        val minLag = (rate * 60 / 180).toInt()
        val maxLag = (rate * 60 / 70).toInt() + 1
        var best = -1.0
        var bestLag = 0
        val scores = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (k in lag until onset.size) s += onset[k] * onset[k - lag]
            s /= (onset.size - lag)
            // gentle preference for tempos around 120 BPM
            val bpm = 60 * rate / lag
            val w = exp(-0.5 * Math.pow(ln(bpm / 120.0) / 0.6, 2.0))
            scores[lag] = s
            if (s * (0.6 + 0.4 * w) > best) { best = s * (0.6 + 0.4 * w); bestLag = lag }
        }
        if (bestLag == 0 || best <= 0) return null
        // parabolic interpolation for a sub-frame lag
        val y0 = scores.getOrElse(bestLag - 1) { scores[bestLag] }
        val y1 = scores[bestLag]
        val y2 = scores.getOrElse(bestLag + 1) { scores[bestLag] }
        val denom = y0 - 2 * y1 + y2
        val shift = if (denom != 0.0) (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5) else 0.0
        var bpm = 60 * rate / (bestLag + shift)
        while (bpm < 70) bpm *= 2
        while (bpm > 180) bpm /= 2
        return (Math.round(bpm * 10) / 10.0).toFloat()
    }

    private class Iir {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var z1 = 0.0; private var z2 = 0.0
        fun set(b: DoubleArray, a: DoubleArray) { b0 = b[0]; b1 = b[1]; b2 = b[2]; a1 = a[0]; a2 = a[1] }
        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }
}
