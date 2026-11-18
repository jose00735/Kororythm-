package com.koro.kororythm.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class DjDspTest {

    private val fs = 44100

    private fun pullAll(fx: OutroFx, frames: Int, ch: Int = 2): FloatArray {
        val out = FloatArray(frames * ch)
        var got = 0
        val chunk = FloatArray(1024 * ch)
        while (got < frames) {
            val n = fx.pull(chunk, minOf(1024, frames - got))
            if (n == 0) break
            System.arraycopy(chunk, 0, out, got * ch, n * ch)
            got += n
        }
        return out.copyOf(got * ch)
    }

    @Test fun crossfadeGoesFromFullToSilentWithEqualPower() {
        val fx = OutroFx(Transition.CROSSFADE, fs, 2, 1000)
        fx.push(FloatArray(fs * 2 * 2) { 0.5f }, fs * 2)
        val out = pullAll(fx, fs)
        assertEquals(fs * 2, out.size)
        assertEquals(0.5f, out[0], 0.01f)
        assertEquals(0.5f * 0.7071f, out[(fs / 2) * 2], 0.01f) // halfway: -3 dB
        assertTrue(abs(out[out.size - 1]) < 0.01f)
        assertTrue(fx.finished)
        assertEquals(1.0, IntroFx.equalPowerSum(0.37), 1e-9)
    }

    @Test fun brakeSlowsDownSoItConsumesLessThanItPlays() {
        val fx = OutroFx(Transition.VINYL_BRAKE, fs, 1, 1000)
        // a 440 Hz tone; the brake lowers its pitch as it slows
        val input = FloatArray(fs) { sin(2 * PI * 440 * it / fs).toFloat() }
        fx.push(input, fs)
        val out = pullAll(fx, fs, ch = 1)
        assertEquals(fs, out.size) // plays the full second…
        // …and near the end the tone is much slower: count zero crossings in first vs last 0.2 s
        fun crossings(a: FloatArray, from: Int, to: Int) = (from + 1 until to).count { (a[it - 1] < 0) != (a[it] < 0) }
        val early = crossings(out, 0, fs / 5)
        val late = crossings(out, fs * 3 / 5, fs * 4 / 5)
        assertTrue("early $early late $late", late < early / 2)
    }

    @Test fun rewindWaitsForHistoryThenPlaysBackwards() {
        val fx = OutroFx(Transition.REWIND, fs, 1, 1200)
        val history = Transition.REWIND.historyMs * fs / 1000
        val probe = FloatArray(10)
        fx.push(FloatArray(history / 2) { 0.1f }, history / 2)
        assertEquals("still collecting history", 0, fx.pull(probe, 10))
        // a rising ramp: playing it backwards must give falling values
        val ramp = FloatArray(history) { it.toFloat() / history }
        val fx2 = OutroFx(Transition.REWIND, fs, 1, 1200)
        fx2.push(ramp, history)
        val out = pullAll(fx2, fs / 2, ch = 1)
        assertTrue(fx2.started)
        assertTrue("starts near the end of the ramp", out[0] > 0.95f)
        assertTrue("goes backwards", out[fs / 4] < out[100] && out[fs / 2 - 1] < out[fs / 4])
    }

    @Test fun introFadesInFromSilence() {
        val fx = IntroFx(Transition.CROSSFADE, fs, 1, 1000)
        val buf = FloatArray(fs) { 1f }
        fx.process(buf, fs)
        assertEquals(0f, buf[0], 0.001f)
        assertEquals(0.7071f, buf[fs / 2], 0.01f)
        assertEquals(1f, buf[fs - 1], 0.01f)
        assertTrue(fx.finished)
    }

    @Test fun liveFxOffLeavesAudioAloneAndEchoRepeatsOnTheBeat() {
        val e = LiveFxEngine(fs, 1)
        e.update(LiveFx(), 120f)
        val buf = FloatArray(1000) { (it % 10) / 10f }
        val copy = buf.copyOf()
        e.process(buf, 1000)
        assertTrue(buf.contentEquals(copy))

        e.update(LiveFx(echo = true), 120f)
        val n = fs
        val imp = FloatArray(n).also { it[0] = 1f }
        e.process(imp, n)
        val delay = (60.0 / 120 * 0.75 * fs).toInt() // 3/4 beat at 120 BPM = 375 ms
        assertEquals(0.5f, imp[delay], 0.01f)
    }

    @Test fun beatRepeatLoopsTheSlice() {
        val e = LiveFxEngine(fs, 1)
        e.update(LiveFx(repeatBeats = 0.25f), 120f) // 1/4 beat = 125 ms
        val len = (60.0 / 120 * 0.25 * fs).toInt()
        val buf = FloatArray(len * 4) { it.toFloat() / (len * 4) } // rising ramp
        e.process(buf, buf.size)
        // the second slice repeats the first one (away from the click-guard edges)
        assertEquals(buf[100], buf[len + 100], 1e-6f)
        assertEquals(buf[len / 2], buf[2 * len + len / 2], 1e-6f)
    }

    @Test fun loudnessMatchesEbuReference() {
        // EBU Tech 3341: a stereo 1 kHz sine with -20 dBFS peak level on both channels reads -20 LUFS
        val a = TrackAnalyzer(48000, 2)
        val n = 48000 * 10
        val amp = 0.1f // -20 dBFS peak
        val buf = FloatArray(n * 2) { i -> (amp * sin(2 * PI * 997 * (i / 2) / 48000)).toFloat() }
        a.feed(buf, n)
        assertEquals(-20.0, a.loudnessLufs()!!, 0.3)
        assertEquals(2.0, a.gainDb()!!, 0.3)
    }

    private fun drumTrack(bpm: Double, seconds: Int): FloatArray {
        val n = fs * seconds
        val buf = FloatArray(n)
        val beat = 60.0 / bpm * fs
        var t = 0.0
        while (t < n) {
            val start = t.toInt()
            for (k in 0 until 6000) { // kick: 55 Hz thump
                if (start + k >= n) break
                buf[start + k] += (0.9 * sin(2 * PI * 55 * k / fs) * exp(-k / 1500.0)).toFloat()
            }
            t += beat
        }
        // a little hi-hat noise on the off-beats
        var seed = 1L
        t = beat / 2
        while (t < n) {
            val s = t.toInt()
            for (k in 0 until 800) {
                if (s + k >= n) break
                seed = seed * 6364136223846793005L + 1442695040888963407L
                buf[s + k] += (((seed ushr 33) % 2000 - 1000) / 1000.0 * 0.15 * exp(-k / 200.0)).toFloat()
            }
            t += beat
        }
        return buf
    }

    @Test fun detectsTempoOfDrumTracks() {
        for (bpm in listOf(128.0, 95.0, 174.0)) {
            val a = TrackAnalyzer(fs, 1)
            val d = drumTrack(bpm, 30)
            a.feed(d, d.size)
            val got = a.bpm()
            assertNotNull(got)
            assertEquals("tempo $bpm", bpm, got!!.toDouble(), 1.5)
        }
    }
}
