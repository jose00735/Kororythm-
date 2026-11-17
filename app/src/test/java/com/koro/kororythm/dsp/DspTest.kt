package com.koro.kororythm.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {

    private val fs = 44100

    /** Steady-state RMS gain (dB) of [eq] for a sine at [freq]. */
    private fun gainDb(eq: EqEngine, freq: Double): Double {
        val n = fs // 1 s, mono
        val buf = FloatArray(n) { (0.1 * sin(2 * PI * freq * it / fs)).toFloat() }
        eq.process(buf, n)
        val skip = n / 2 // ignore the filter's settling time
        var inSq = 0.0; var outSq = 0.0
        for (i in skip until n) {
            val x = 0.1 * sin(2 * PI * freq * i / fs)
            inSq += x * x; outSq += buf[i] * buf[i].toDouble()
        }
        return 20 * log10(sqrt(outSq / inSq))
    }

    @Test fun flatEqIsBitTransparent() {
        val eq = EqEngine(fs, 2)
        eq.update(EqSettings())
        assertTrue(eq.isBypassed)
        val buf = FloatArray(512) { (it % 7) / 10f - 0.3f }
        val copy = buf.copyOf()
        eq.process(buf, 256)
        assertTrue(buf.contentEquals(copy))
    }

    @Test fun peakingBandHitsItsGainAtCentre() {
        val eq = EqEngine(fs, 1)
        val bands = MutableList(10) { 0f }.also { it[4] = 6f } // +6 dB at 1 kHz
        eq.update(EqSettings(bandsDb = bands))
        assertEquals(6.0, gainDb(eq, 1000.0), 0.3)
    }

    @Test fun peakingBandLeavesFarFrequenciesAlone() {
        val eq = EqEngine(fs, 1)
        val bands = MutableList(10) { 0f }.also { it[0] = 9f } // +9 dB at 60 Hz
        eq.update(EqSettings(bandsDb = bands))
        assertEquals(0.0, gainDb(eq, 8000.0), 0.3)
    }

    @Test fun preampScalesLevel() {
        val eq = EqEngine(fs, 1)
        eq.update(EqSettings(preampDb = -6f))
        assertEquals(-6.0, gainDb(eq, 1000.0), 0.1)
    }

    @Test fun boostLiftsBassAndKeepsHeadroomInMids() {
        val bass = EqEngine(fs, 1).apply { update(EqSettings(boost = true)) }
        val mids = EqEngine(fs, 1).apply { update(EqSettings(boost = true)) }
        val bassGain = gainDb(bass, 40.0)
        val midGain = gainDb(mids, 1000.0)
        assertTrue("bass gain $bassGain", bassGain > 0.5)
        assertEquals(-3.0, midGain, 0.6)
    }

    @Test fun softClipNeverExceedsFullScale() {
        var peak = 0.0
        for (i in -400..400) peak = max(peak, abs(EqEngine.softClip(i / 100.0)))
        assertTrue(peak <= 1.0)
        assertEquals(0.5, EqEngine.softClip(0.5), 0.0) // linear below the knee
    }

    @Test fun fftFindsASine() {
        val n = 1024
        val fft = Fft(n)
        val re = DoubleArray(n) { sin(2 * PI * 64 * it / n) }
        val im = DoubleArray(n)
        fft.transform(re, im)
        val mags = DoubleArray(n / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        assertEquals(64, mags.indices.maxBy { mags[it] })
        assertEquals(n / 2.0, mags[64], 1e-6)
    }

    @Test fun analyzerEmitsAboutSixtyFramesPerSecond() {
        val a = SpectrumAnalyzer(fs)
        var frames = 0
        var lastBars = FloatArray(0)
        val tone = FloatArray(fs) { (0.5 * sin(2 * PI * 1000.0 * it / fs)).toFloat() }
        a.feed(tone, tone.size) { frames++; lastBars = it.bars }
        assertEquals(60, frames)
        val loudest = lastBars.indices.maxBy { lastBars[it] }
        // 1 kHz sits about halfway up a 40 Hz..16 kHz log scale.
        assertTrue("loudest bar $loudest", loudest in 28..40)
    }
}
