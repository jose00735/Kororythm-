package com.koro.kororythm.dsp

import android.content.Context
import android.content.SharedPreferences
import com.koro.kororythm.AudioBus

/** Saves and restores the EQ panel between launches. */
object EqStore {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("eq", Context.MODE_PRIVATE)
        val bands = prefs.getString("bands", null)
            ?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == EqEngine.BAND_FREQS.size }
            ?: List(EqEngine.BAND_FREQS.size) { 0f }
        AudioBus.eq.value = EqSettings(
            enabled = prefs.getBoolean("enabled", true),
            preampDb = prefs.getFloat("preamp", 0f),
            bandsDb = bands,
            boost = prefs.getBoolean("boost", false),
            presetName = prefs.getString("preset", "Flat") ?: "Flat",
        )
    }

    fun update(transform: (EqSettings) -> EqSettings) {
        val s = transform(AudioBus.eq.value)
        AudioBus.eq.value = s
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putFloat("preamp", s.preampDb)
            .putString("bands", s.bandsDb.joinToString(","))
            .putBoolean("boost", s.boost)
            .putString("preset", s.presetName)
            .apply()
    }
}
