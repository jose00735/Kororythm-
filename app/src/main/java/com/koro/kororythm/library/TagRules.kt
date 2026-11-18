package com.koro.kororythm.library

import java.security.MessageDigest

/** Small pure helpers used by the scanner and search (unit-tested on the JVM). */
object TagRules {

    val AUDIO_MIMES = setOf("audio/flac", "audio/x-flac", "audio/mpeg", "audio/mp3")
    private val AUDIO_EXT = setOf("flac", "mp3")

    fun isAudio(mime: String?, name: String?): Boolean =
        (mime != null && mime.lowercase() in AUDIO_MIMES) ||
            (name != null && name.substringAfterLast('.', "").lowercase() in AUDIO_EXT)

    /**
     * Guesses artist and title from a file name when the tags are empty.
     * "03 - Grid Pilots - Neon Runway.flac" -> ("Grid Pilots", "Neon Runway")
     * "Neon Runway.mp3" -> (null, "Neon Runway")
     */
    fun parseFileName(fileName: String): Pair<String?, String> {
        var base = fileName.substringBeforeLast('.').replace('_', ' ').trim()
        base = base.replace(Regex("^\\d{1,3}\\s*[-.)]?\\s+"), "") // leading track number
        val parts = base.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            parts.size >= 2 -> parts[parts.size - 2] to parts.last()
            parts.size == 1 -> null to parts[0]
            else -> null to fileName
        }
    }

    /** "3/12" -> 3, "07" -> 7, junk -> null. */
    fun parseNumber(raw: String?): Int? =
        raw?.trim()?.substringBefore('/')?.toIntOrNull()?.takeIf { it > 0 }

    /** "2001-05-01" or "2001" -> 2001. */
    fun parseYear(raw: String?): Int? =
        raw?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it in 1000..2999 }

    fun tagsMissing(titleFromTags: String?, artist: String?, album: String?): Boolean =
        titleFromTags.isNullOrBlank() || artist.isNullOrBlank() || album.isNullOrBlank()

    /** Same first 64 KB and same size = same file, even if it lives in two places. */
    fun contentKey(head: ByteArray, headLen: Int, size: Long): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(head, 0, headLen)
        return md.digest().joinToString("") { "%02x".format(it) } + ":" + size
    }

    /**
     * Turns what the user typed into an FTS4 MATCH expression with prefix search:
     * "neon run" -> "neon* run*". Returns null for an empty query.
     */
    fun ftsQuery(text: String): String? {
        val tokens = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return if (tokens.isEmpty()) null else tokens.joinToString(" ") { "${it.lowercase()}*" }
    }
}
