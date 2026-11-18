package com.koro.kororythm.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/**
 * The DJ panel's effect pads, applied live to the main player:
 * echo (beat-synced), flanger, bitcrush, trance gate, a one-knob low/high-pass filter and
 * beat repeat (loops the last ½ or ¼ beat while the song keeps running underneath).
 * Interleaved float samples, processed in place.
 */
class LiveFxEngine(private val sampleRate: Int, private val channels: Int) {

    private var bpm = 120f
    private var fx = LiveFx()

    // echo: 3/4 beat
    private var echoBuf = FloatArray(0)
    private var echoLen = 1
    private var echoPos = 0

    // flanger: 1..6 ms modulated delay
    private val flLen = (sampleRate * 0.012).toInt() + 2
    private val flBuf = FloatArray(flLen * channels)
    private var flPos = 0
    private var flPhase = 0.0

    // gate
    private var gatePhase = 0.0
    private var gateGain = 1f

    // filter knob
    private val filt = Array(channels) { OnePoleBiquad() }
    private var filterApplied = Float.NaN

    // beat repeat
    private var repBuf = FloatArray(0)
    private var repLen = 0
    private var repFill = 0
    private var repPos = 0
    private var repActive = 0f

    // bitcrush sample-and-hold
    private val hold = FloatArray(channels)
    private var holdCount = 0

    fun update(newFx: LiveFx, newBpm: Float?) {
        val b = newBpm?.takeIf { it in 40f..250f } ?: 120f
        if (b != bpm || echoBuf.isEmpty()) {
            bpm = b
            echoLen = max(1, (60.0 / bpm * 0.75 * sampleRate).toInt())
            echoBuf = FloatArray(echoLen * channels)
            echoPos = 0
        }
        if (newFx.filter != filterApplied) {
            filterApplied = newFx.filter
            val k = abs(newFx.filter).toDouble()
            if (newFx.filter < 0) filt.forEach { it.setLowPass(sampleRate, 20000.0 * (180.0 / 20000.0).pow(k), 1.0) }
            else if (newFx.filter > 0) filt.forEach { it.setHighPass(sampleRate, 20.0 * (4000.0 / 20.0).pow(k), 1.0) }
        }
        if (newFx.repeatBeats != repActive) {
            repActive = newFx.repeatBeats
            if (repActive > 0f) {
                repLen = max(64, (60.0 / bpm * repActive * sampleRate).toInt())
                repBuf = FloatArray(repLen * channels)
                repFill = 0
                repPos = 0
            }
        }
        if (!newFx.echo && fx.echo) echoBuf.fill(0f)
        fx = newFx
    }

    val active: Boolean get() = fx.anyOn

    fun process(buf: FloatArray, frames: Int) {
        if (!fx.anyOn) return
        val beatSamples = 60.0 / bpm * sampleRate
        var i = 0
        for (f in 0 until frames) {
            // beat repeat first: record one slice, then loop it
            if (repActive > 0f) {
                if (repFill < repLen) {
                    for (c in 0 until channels) repBuf[repFill * channels + c] = buf[i + c]
                    repFill++
                } else {
                    val edge = minOf(repPos, repLen - repPos, 64) / 64f // tiny fades stop clicks at the loop point
                    for (c in 0 until channels) buf[i + c] = repBuf[repPos * channels + c] * edge
                    repPos = (repPos + 1) % repLen
                }
            }
            if (fx.filter != 0f) for (c in 0 until channels) buf[i + c] = filt[c].process(buf[i + c].toDouble()).toFloat()
            if (fx.flanger) {
                flPhase += 0.25 / sampleRate
                val d = (0.001 + 0.0025 * (1 + sin(2 * PI * flPhase))) * sampleRate
                for (c in 0 until channels) {
                    val rp = (flPos - d + flLen) % flLen
                    val k = rp.toInt()
                    val fr = (rp - k).toFloat()
                    val a = flBuf[k * channels + c]
                    val b = flBuf[((k + 1) % flLen) * channels + c]
                    val delayed = a + (b - a) * fr
                    val x = buf[i + c]
                    flBuf[flPos * channels + c] = x + delayed * 0.55f
                    buf[i + c] = (x + delayed) * 0.6f
                }
                flPos = (flPos + 1) % flLen
            }
            if (fx.echo) {
                for (c in 0 until channels) {
                    val d = echoBuf[echoPos * channels + c]
                    val x = buf[i + c]
                    echoBuf[echoPos * channels + c] = x + d * 0.45f
                    buf[i + c] = x + d * 0.5f
                }
                echoPos = (echoPos + 1) % echoLen
            }
            if (fx.gate) {
                gatePhase = (gatePhase + 1.0 / (beatSamples / 4)) % 1.0 // 1/16 notes
                val target = if (gatePhase < 0.5) 1f else 0.05f
                gateGain += (target - gateGain) * 0.01f
                for (c in 0 until channels) buf[i + c] *= gateGain
            }
            if (fx.bitcrush) {
                if (holdCount == 0) for (c in 0 until channels) hold[c] = round(buf[i + c] * 31f) / 31f // ~6-bit, 1/4 rate
                holdCount = (holdCount + 1) % 4
                for (c in 0 until channels) buf[i + c] = hold[c]
            }
            for (c in 0 until channels) buf[i + c] = buf[i + c].coerceIn(-1f, 1f)
            i += channels
        }
    }

    fun reset() {
        echoBuf.fill(0f); flBuf.fill(0f); filt.forEach { it.reset() }; repFill = 0; repPos = 0
    }
}
