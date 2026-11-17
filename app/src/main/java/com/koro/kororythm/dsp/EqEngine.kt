package com.koro.kororythm.dsp

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.tanh

/** Everything the user can set on the EQ panel. */
data class EqSettings(
    val enabled: Boolean = true,
    val preampDb: Float = 0f,
    val bandsDb: List<Float> = List(EqEngine.BAND_FREQS.size) { 0f },
    val boost: Boolean = false,
    val presetName: String = "Flat",
) {
    /** True when processing would change nothing, so audio can pass through untouched. */
    val isTransparent: Boolean
        get() = !boost && (!enabled || (preampDb == 0f && bandsDb.all { it == 0f }))
}

/**
 * Winamp-style 10-band graphic EQ + preamp, plus the optional "Boost" loudness profile
 * (low/high shelves with 3 dB of headroom and a soft clipper). Works on interleaved
 * float samples in the range -1..1.
 */
class EqEngine(private val sampleRate: Int, private val channels: Int) {

    companion object {
        /** Classic Winamp band centres, Hz. */
        val BAND_FREQS = doubleArrayOf(60.0, 170.0, 310.0, 600.0, 1000.0, 3000.0, 6000.0, 12000.0, 14000.0, 16000.0)
        const val BAND_Q = 1.41
        const val MAX_DB = 12f

        const val BOOST_HEADROOM_DB = -3.0
        const val BOOST_LOW_SHELF_HZ = 105.0
        const val BOOST_LOW_DB = 4.0
        const val BOOST_HIGH_SHELF_HZ = 9000.0
        const val BOOST_HIGH_DB = 3.0

        private const val CLIP_KNEE = 0.9

        /** Soft clipper: linear below the knee, smoothly saturates to 1.0 above it. */
        fun softClip(x: Double): Double {
            val ax = abs(x)
            if (ax <= CLIP_KNEE) return x
            val over = (ax - CLIP_KNEE) / (1 - CLIP_KNEE)
            return sign(x) * (CLIP_KNEE + (1 - CLIP_KNEE) * tanh(over))
        }
    }

    private val bands = Array(BAND_FREQS.size) { Biquad(channels) }
    private val bandActive = BooleanArray(BAND_FREQS.size)
    private val lowShelf = Biquad(channels)
    private val highShelf = Biquad(channels)
    private var preGain = 1.0
    private var boost = false
    private var bypass = true
    private var applied: EqSettings? = null

    fun update(s: EqSettings) {
        if (s == applied) return
        applied = s
        bypass = s.isTransparent
        val nyquistGuard = sampleRate * 0.45
        for (i in BAND_FREQS.indices) {
            val db = if (s.enabled) s.bandsDb[i].toDouble() else 0.0
            // Skip flat bands and bands too close to Nyquist (e.g. 16 kHz at 32 kHz).
            bandActive[i] = db != 0.0 && BAND_FREQS[i] < nyquistGuard
            if (bandActive[i]) bands[i].setPeaking(sampleRate, BAND_FREQS[i], BAND_Q, db)
        }
        boost = s.boost
        var gainDb = if (s.enabled) s.preampDb.toDouble() else 0.0
        if (boost) {
            gainDb += BOOST_HEADROOM_DB
            lowShelf.setLowShelf(sampleRate, BOOST_LOW_SHELF_HZ, BOOST_LOW_DB)
            highShelf.setHighShelf(sampleRate, minOf(BOOST_HIGH_SHELF_HZ, nyquistGuard), BOOST_HIGH_DB)
        }
        preGain = 10.0.pow(gainDb / 20.0)
    }

    val isBypassed: Boolean get() = bypass

    /** Processes [frames] interleaved frames of [buf] in place. */
    fun process(buf: FloatArray, frames: Int) {
        if (bypass) return
        var idx = 0
        for (f in 0 until frames) {
            for (ch in 0 until channels) {
                var x = buf[idx] * preGain
                if (boost) {
                    x = lowShelf.process(x, ch)
                    x = highShelf.process(x, ch)
                }
                for (b in bands.indices) if (bandActive[b]) x = bands[b].process(x, ch)
                buf[idx] = softClip(x).toFloat()
                idx++
            }
        }
    }

    fun reset() {
        bands.forEach { it.reset() }
        lowShelf.reset(); highShelf.reset()
    }
}

/** Built-in presets (dB per band, 60 Hz .. 16 kHz). */
object EqPresets {
    val all: LinkedHashMap<String, List<Float>> = linkedMapOf(
        "Flat" to List(10) { 0f },
        "Bass" to listOf(7f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Rock" to listOf(5f, 3f, -2f, -4f, -1f, 2f, 5f, 6f, 6f, 6f),
        "Pop" to listOf(-1f, 2f, 4f, 5f, 3f, 0f, -1f, -1f, -1f, -1f),
        "Vocal" to listOf(-3f, -2f, 0f, 3f, 5f, 5f, 3f, 1f, 0f, -1f),
        "Dance" to listOf(6f, 5f, 2f, 0f, 0f, -3f, -4f, -4f, 0f, 0f),
        "Treble" to listOf(0f, 0f, 0f, 0f, 0f, 2f, 5f, 7f, 8f, 8f),
    )
}
