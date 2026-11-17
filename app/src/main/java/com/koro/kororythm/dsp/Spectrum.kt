package com.koro.kororythm.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place iterative radix-2 FFT. */
class Fft(val size: Int) {
    init { require(size > 0 && size and (size - 1) == 0) { "size must be a power of two" } }

    private val cosT = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinT = DoubleArray(size / 2) { -sin(2 * PI * it / size) }

    fun transform(re: DoubleArray, im: DoubleArray) {
        // bit reversal
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]; val wi = sinT[k * step]
                    val a = i + k; val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** One visualizer frame: spectrum bars (0..1) and oscilloscope samples (-1..1). */
class VizFrame(val bars: FloatArray, val scope: FloatArray)

/**
 * Turns a mono sample stream into [VizFrame]s, one every [hop] samples (≈60 per second).
 * Bars are log-spaced from 40 Hz to 16 kHz and mapped from -72..0 dBFS to 0..1.
 */
class SpectrumAnalyzer(
    private val sampleRate: Int,
    val barCount: Int = 64,
    val scopePoints: Int = 128,
    private val fftSize: Int = 2048,
) {
    val hop: Int = max(1, sampleRate / 60)
    private val fft = Fft(fftSize)
    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1)) }
    private val ring = FloatArray(fftSize)
    private var writePos = 0
    private var sinceLast = 0
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val binLo = IntArray(barCount)
    private val binHi = IntArray(barCount)

    init {
        val fMin = 40.0
        val fMax = min(16000.0, sampleRate / 2.0 * 0.95)
        val binHz = sampleRate.toDouble() / fftSize
        for (b in 0 until barCount) {
            val f0 = fMin * (fMax / fMin).pow(b.toDouble() / barCount)
            val f1 = fMin * (fMax / fMin).pow((b + 1).toDouble() / barCount)
            binLo[b] = max(1, (f0 / binHz).toInt())
            binHi[b] = max(binLo[b], (f1 / binHz).toInt())
        }
    }

    /** Feeds samples; calls [emit] for every finished frame. */
    fun feed(samples: FloatArray, count: Int, emit: (VizFrame) -> Unit) {
        for (i in 0 until count) {
            ring[writePos] = samples[i]
            writePos = (writePos + 1) and (fftSize - 1)
            if (++sinceLast >= hop) {
                sinceLast = 0
                emit(analyze())
            }
        }
    }

    fun reset() { ring.fill(0f); writePos = 0; sinceLast = 0 }

    private fun analyze(): VizFrame {
        for (i in 0 until fftSize) {
            re[i] = ring[(writePos + i) and (fftSize - 1)] * window[i]
            im[i] = 0.0
        }
        fft.transform(re, im)
        val norm = 4.0 / fftSize // Hann window coherent gain 0.5, one-sided spectrum
        val bars = FloatArray(barCount)
        for (b in 0 until barCount) {
            var peak = 0.0
            for (k in binLo[b]..binHi[b]) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) * norm
                if (mag > peak) peak = mag
            }
            val db = 20 * log10(peak + 1e-9)
            bars[b] = ((db + 72.0) / 72.0).coerceIn(0.0, 1.0).toFloat()
        }
        val scope = FloatArray(scopePoints)
        val span = fftSize / 2
        for (p in 0 until scopePoints) {
            val i = fftSize - span + p * span / scopePoints
            scope[p] = ring[(writePos + i) and (fftSize - 1)]
        }
        return VizFrame(bars, scope)
    }
}
