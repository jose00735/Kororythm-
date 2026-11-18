package com.koro.kororythm.tags

import java.util.Locale

/**
 * Decides whether a replacement file really sounds better ("Upgrade quality"):
 * lossless beats lossy; a lossy file must have a clearly higher bitrate; a lossless file must
 * have more bits or a higher sample rate. YouTube downloads count at their real source
 * bitrate (stored in the file by the downloader), not at the bitrate they were re-encoded to.
 */
object Quality {

    data class Spec(val codec: String?, val bitrateKbps: Int?, val sampleRate: Int?, val bits: Int?) {
        val lossless: Boolean get() = codec?.uppercase() in LOSSLESS

        /** Data rate the sound really carries, in kbps (lossless: PCM rate of the source). */
        val effectiveKbps: Double
            get() = if (lossless) (bits ?: 16) * (sampleRate ?: 44100) * 2 / 1000.0 else (bitrateKbps ?: 0).toDouble()

        val label: String
            get() = buildString {
                append(codec?.uppercase() ?: "AUDIO")
                if (lossless) {
                    append(" · ").append(bits ?: 16).append("-bit")
                    sampleRate?.let { append(" / ").append(khz(it)).append(" kHz") }
                } else {
                    bitrateKbps?.let { append(" · ").append(it).append(" kbps") }
                }
            }
    }

    private val LOSSLESS = setOf("FLAC", "WAV", "ALAC", "AIFF")

    sealed class Verdict {
        abstract val reason: String
        data class Better(override val reason: String, val powerFactor: Double) : Verdict()
        data class NotBetter(override val reason: String) : Verdict()
    }

    fun compare(old: Spec, new: Spec): Verdict {
        val factor = if (old.effectiveKbps > 0) new.effectiveKbps / old.effectiveKbps else 1.0
        return when {
            new.lossless && !old.lossless -> Verdict.Better("${old.label} → ${new.label}: lossless now", factor)
            !new.lossless && old.lossless -> Verdict.NotBetter("${new.label} is lossy; it can't replace ${old.label}.")
            new.lossless && old.lossless -> {
                val more = (new.bits ?: 16) > (old.bits ?: 16) || (new.sampleRate ?: 0) > (old.sampleRate ?: 0)
                val less = (new.bits ?: 16) < (old.bits ?: 16) || (new.sampleRate ?: 0) < (old.sampleRate ?: 0)
                if (more && !less) Verdict.Better("${old.label} → ${new.label}: hi-res", factor)
                else Verdict.NotBetter("${new.label} isn't higher resolution than ${old.label}.")
            }
            else -> {
                val o = old.bitrateKbps ?: 0
                val n = new.bitrateKbps ?: 0
                if (n >= o + 16 && n >= o * 1.1) Verdict.Better("${old.label} → ${new.label}", factor)
                else Verdict.NotBetter("${new.label} isn't clearly better than ${old.label}.")
            }
        }
    }

    /** "×7.3" style power figure for the animation. */
    fun powerLabel(factor: Double): String = "×" + String.format(Locale.US, if (factor >= 10) "%.0f" else "%.1f", factor)

    private fun khz(hz: Int) = if (hz % 1000 == 0) "${hz / 1000}" else String.format(Locale.US, "%.1f", hz / 1000.0)

    /**
     * The downloader stores "youtube;opus;160" in an ID3 TXXX frame named KORORYTHM_SOURCE.
     * Reads it from the first bytes of an MP3 (the tag sits at the start).
     */
    fun sourceNote(head: ByteArray): String? {
        if (head.size < 10 || String(head, 0, 3, Charsets.ISO_8859_1) != "ID3") return null
        val major = head[3].toInt()
        val size = TagIo.syncsafe(head, 6)
        var p = 10
        val end = minOf(head.size, 10 + size)
        while (p + 10 <= end) {
            if (head[p].toInt() == 0) break
            val id = String(head, p, 4, Charsets.ISO_8859_1)
            val len = if (major == 4) TagIo.syncsafe(head, p + 4) else
                ((head[p + 4].toInt() and 0xFF) shl 24) or ((head[p + 5].toInt() and 0xFF) shl 16) or ((head[p + 6].toInt() and 0xFF) shl 8) or (head[p + 7].toInt() and 0xFF)
            if (len <= 0 || p + 10 + len > end) break
            if (id == "TXXX") {
                val body = head.copyOfRange(p + 10, p + 10 + len)
                val text = String(body, 1, body.size - 1, if (body[0].toInt() == 3) Charsets.UTF_8 else Charsets.ISO_8859_1)
                val parts = text.split('\u0000')
                if (parts.size >= 2 && parts[0] == SOURCE_DESC) return parts[1].trimEnd('\u0000')
            }
            p += 10 + len
        }
        return null
    }

    /** Applies a downloader source note: a re-encoded YouTube file counts at its source bitrate. */
    fun withSource(spec: Spec, note: String?): Spec {
        val kbps = note?.split(';')?.getOrNull(2)?.toIntOrNull() ?: return spec
        return spec.copy(bitrateKbps = minOf(spec.bitrateKbps ?: kbps, kbps))
    }

    const val SOURCE_DESC = "KORORYTHM_SOURCE"
}
