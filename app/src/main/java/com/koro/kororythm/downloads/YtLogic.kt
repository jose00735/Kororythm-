package com.koro.kororythm.downloads

import java.util.Locale

/** What a download is saved as. */
enum class DlFormat(val label: String, val hint: String) {
    MP3_V0("MP3 · VBR V0", "~245 kbps MP3, plays everywhere; tagged with cover art"),
    ORIGINAL("Original stream", "The audio exactly as the site serves it (usually Opus or AAC), no re-encoding"),
}

/** One audio stream offered by the site. */
data class AudioStream(val formatId: String?, val acodec: String?, val abrKbps: Int, val sampleRate: Int, val ext: String?)

/** Pure helpers for the Downloads screen (no Android, unit-tested). */
object YtLogic {

    private val urlRegex = Regex("https?://[^\\s<>\"']+")

    /** First link in shared text ("Check this out https://youtu.be/abc?si=x"). */
    fun extractUrl(text: String?): String? =
        text?.let { urlRegex.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?') }

    fun isYoutube(url: String): Boolean =
        Regex("^https?://(www\\.|m\\.|music\\.)?(youtube\\.com|youtu\\.be)/", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private val noise = Regex(
        "\\s*[(\\[](official( music| lyric| audio| hd)?( video| audio| visualizer)?|lyrics?( video)?|audio|video( oficial)?|" +
            "hd|hq|4k|visualizer|music video|videoclip|video oficial|letra|official|remaster(ed)?( \\d{4})?)[)\\]]",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Artist and title from a video title and channel name:
     * "Daft Punk - Get Lucky (Official Video)" → (Daft Punk, Get Lucky);
     * a "Daft Punk - Topic" channel gives the artist directly.
     */
    fun guessArtistTitle(videoTitle: String, uploader: String?): Pair<String?, String> {
        val clean = noise.replace(videoTitle, "").replace(Regex("\\s{2,}"), " ").trim()
        val dash = Regex("\\s+[-–—|]\\s+").find(clean)
        if (dash != null) {
            val a = clean.substring(0, dash.range.first).trim()
            val t = clean.substring(dash.range.last + 1).trim().trim('"', '“', '”')
            if (a.isNotEmpty() && t.isNotEmpty()) return a to t
        }
        val channel = uploader?.trim()?.removeSuffix(" - Topic")?.removeSuffix("VEVO")?.trim()?.takeIf { it.isNotEmpty() }
        return channel to clean.trim('"', '“', '”')
    }

    /** YYYYMMDD → year. */
    fun year(uploadDate: String?): Int? = uploadDate?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }

    /** Best audio-only stream: highest bitrate, Opus preferred on ties (it sounds better at the same rate). */
    fun bestAudio(streams: List<AudioStream>): AudioStream? =
        streams.filter { it.abrKbps > 0 && (it.acodec ?: "none") != "none" }
            .maxWithOrNull(compareBy<AudioStream> { it.abrKbps }.thenBy { if (it.acodec?.startsWith("opus") == true) 1 else 0 })

    fun codecName(acodec: String?): String = when {
        acodec == null -> "audio"
        acodec.startsWith("opus") -> "Opus"
        acodec.startsWith("mp4a") -> "AAC"
        acodec.startsWith("vorbis") -> "Vorbis"
        acodec.startsWith("mp3") -> "MP3"
        acodec.startsWith("flac") -> "FLAC"
        else -> acodec.substringBefore('.')
    }

    /** Honest label: what the site really serves, and what the saved file will be. */
    fun qualityLabel(src: AudioStream?, format: DlFormat): String {
        val source = src?.let { "${codecName(it.acodec)} ${it.abrKbps} kbps" + if (it.sampleRate > 0) " · ${khz(it.sampleRate)} kHz" else "" } ?: "unknown"
        return when (format) {
            DlFormat.MP3_V0 -> "Source $source → MP3 V0 (sound stays at source quality)"
            DlFormat.ORIGINAL -> "Source $source, saved as is"
        }
    }

    /** "youtube;opus;160": stored in the MP3 so Upgrade quality knows its real bitrate. */
    fun sourceNote(extractor: String?, src: AudioStream?): String =
        listOf((extractor ?: "web").lowercase(Locale.ROOT), codecName(src?.acodec).lowercase(Locale.ROOT), (src?.abrKbps ?: 0).toString()).joinToString(";")

    /** File name safe on Android's FAT/exFAT cards: "Artist - Title.mp3". */
    fun fileName(artist: String?, title: String, ext: String): String {
        val base = (if (artist.isNullOrBlank()) title else "$artist - $title")
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").replace(Regex("\\s+"), " ").trim().trimEnd('.')
            .take(120).ifEmpty { "download" }
        return "$base.$ext"
    }

    /** Parses yt-dlp's "[download]  42.3% of ..." lines into 0..1 (null if not a progress line). */
    fun progressOf(line: String): Float? =
        Regex("\\[download]\\s+(\\d{1,3}(?:\\.\\d+)?)%").find(line)?.groupValues?.get(1)?.toFloatOrNull()?.div(100f)

    fun mimeFor(ext: String): String = when (ext.lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "opus", "ogg" -> "audio/ogg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "webm" -> "audio/webm"
        "flac" -> "audio/flac"
        else -> "audio/*"
    }

    private fun khz(hz: Int) = if (hz % 1000 == 0) "${hz / 1000}" else String.format(Locale.US, "%.1f", hz / 1000.0)
}
