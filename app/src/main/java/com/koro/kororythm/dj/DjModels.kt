package com.koro.kororythm.dj

import android.content.Context
import android.content.SharedPreferences
import com.koro.kororythm.AudioBus

/**
 * How Next (or the end of a track) moves to the next song.
 * [overlap]: the next song starts right away and mixes in; otherwise it "drops" after the effect.
 * [historyMs]: how much already-played audio the effect needs (rewind / scratch play it backwards).
 */
enum class Transition(val label: String, val defaultMs: Int, val overlap: Boolean, val historyMs: Int = 0) {
    CUT("CUT", 0, false),
    CROSSFADE("CROSSFADE", 4000, true),
    ECHO_OUT("ECHO OUT", 3000, true),
    FILTER_SWEEP("FILTER SWEEP", 4000, true),
    VINYL_BRAKE("VINYL BRAKE", 1000, false),
    REWIND("REWIND", 1200, false, historyMs = 3400),
    SCRATCH("SCRATCH", 800, false, historyMs = 1500),
}

data class DjSettings(
    val transition: Transition = Transition.CROSSFADE,
    val durationMs: Int = Transition.CROSSFADE.defaultMs,
    /** Mix into the next track automatically near the end of each song. */
    val autoMix: Boolean = true,
    /** Playback speed, 0.92..1.08 (±8 %, like a turntable pitch fader). */
    val tempo: Float = 1f,
    /** Keep the musical key when the tempo changes (time-stretch). */
    val keyLock: Boolean = true,
    /** Volume levelling from the loudness analysis (turns loud tracks down to match). */
    val leveling: Boolean = false,
)

/** Live effect pads on the main player. */
data class LiveFx(
    val echo: Boolean = false,
    val flanger: Boolean = false,
    val bitcrush: Boolean = false,
    val gate: Boolean = false,
    /** -1 = low-pass fully closed .. 0 = off .. +1 = high-pass fully open. */
    val filter: Float = 0f,
    /** Beat repeat length in beats (0 = off, e.g. 0.5 or 0.25). */
    val repeatBeats: Float = 0f,
) {
    val anyOn: Boolean get() = echo || flanger || bitcrush || gate || filter != 0f || repeatBeats > 0f
}

/** Saves the DJ settings between launches (the FX pads always start off). */
object DjStore {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("dj", Context.MODE_PRIVATE).also { prefs = it }
        val t = runCatching { Transition.valueOf(p.getString("transition", null) ?: "CROSSFADE") }.getOrDefault(Transition.CROSSFADE)
        AudioBus.dj.value = DjSettings(
            transition = t,
            durationMs = p.getInt("duration", t.defaultMs),
            autoMix = p.getBoolean("autoMix", true),
            tempo = 1f, // tempo resets to 0 % on each launch, like a deck
            keyLock = p.getBoolean("keyLock", true),
            leveling = p.getBoolean("leveling", false),
        )
        AudioBus.bitPerfectWanted.value = p.getBoolean("bitPerfect", false)
    }

    fun setBitPerfect(on: Boolean) {
        AudioBus.bitPerfectWanted.value = on
        prefs?.edit()?.putBoolean("bitPerfect", on)?.apply()
    }

    fun update(transform: (DjSettings) -> DjSettings) {
        val s = transform(AudioBus.dj.value)
        AudioBus.dj.value = s
        prefs?.edit()
            ?.putString("transition", s.transition.name)
            ?.putInt("duration", s.durationMs)
            ?.putBoolean("autoMix", s.autoMix)
            ?.putBoolean("keyLock", s.keyLock)
            ?.putBoolean("leveling", s.leveling)
            ?.apply()
    }
}
