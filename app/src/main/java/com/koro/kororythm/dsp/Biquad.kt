package com.koro.kororythm.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One second-order IIR filter (RBJ audio-EQ cookbook), transposed direct form II,
 * with its own state per channel.
 */
class Biquad(channels: Int) {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    fun setPeaking(sampleRate: Int, freq: Double, q: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * freq / sampleRate
        val alpha = sin(w0) / (2 * q)
        val cosw = cos(w0)
        setNormalized(
            1 + alpha * a, -2 * cosw, 1 - alpha * a,
            1 + alpha / a, -2 * cosw, 1 - alpha / a,
        )
    }

    fun setLowShelf(sampleRate: Int, freq: Double, gainDb: Double) = setShelf(sampleRate, freq, gainDb, low = true)
    fun setHighShelf(sampleRate: Int, freq: Double, gainDb: Double) = setShelf(sampleRate, freq, gainDb, low = false)

    private fun setShelf(sampleRate: Int, freq: Double, gainDb: Double, low: Boolean) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * freq / sampleRate
        val cosw = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0) // shelf slope S = 1
        val twoSqrtAAlpha = 2 * sqrt(a) * alpha
        if (low) {
            setNormalized(
                a * ((a + 1) - (a - 1) * cosw + twoSqrtAAlpha),
                2 * a * ((a - 1) - (a + 1) * cosw),
                a * ((a + 1) - (a - 1) * cosw - twoSqrtAAlpha),
                (a + 1) + (a - 1) * cosw + twoSqrtAAlpha,
                -2 * ((a - 1) + (a + 1) * cosw),
                (a + 1) + (a - 1) * cosw - twoSqrtAAlpha,
            )
        } else {
            setNormalized(
                a * ((a + 1) + (a - 1) * cosw + twoSqrtAAlpha),
                -2 * a * ((a - 1) + (a + 1) * cosw),
                a * ((a + 1) + (a - 1) * cosw - twoSqrtAAlpha),
                (a + 1) - (a - 1) * cosw + twoSqrtAAlpha,
                2 * ((a - 1) - (a + 1) * cosw),
                (a + 1) - (a - 1) * cosw - twoSqrtAAlpha,
            )
        }
    }

    private fun setNormalized(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0
        a1 = na1 / na0; a2 = na2 / na0
    }

    fun process(x: Double, ch: Int): Double {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }

    fun reset() { z1.fill(0.0); z2.fill(0.0) }
}
