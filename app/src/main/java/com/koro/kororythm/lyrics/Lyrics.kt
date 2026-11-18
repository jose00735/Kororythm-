package com.koro.kororythm.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** One timed line of lyrics. */
data class LrcLine(val timeMs: Long, val text: String)

/** Lyrics found online: [synced] is LRC text ("[00:27.93] line"), [plain] is just the words. */
data class FoundLyrics(val synced: String?, val plain: String?, val instrumental: Boolean, val source: String = "LRCLIB")

object Lrc {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val offsetTag = Regex("^\\[offset:\\s*([+-]?\\d+)\\s*]", RegexOption.IGNORE_CASE)

    /**
     * Parses LRC text. Handles several stamps on one line ("[00:10.00][01:20.00] chorus"),
     * 2- or 3-digit fractions and the [offset:±ms] tag; skips metadata lines like [ar:…].
     */
    fun parse(text: String): List<LrcLine> {
        var offset = 0L
        val out = mutableListOf<LrcLine>()
        for (raw in text.lines()) {
            val line = raw.trim()
            val off = offsetTag.find(line)
            if (off != null) { offset = off.groupValues[1].toLong(); continue }
            var rest = line
            val times = mutableListOf<Long>()
            while (true) {
                val m = stamp.find(rest) ?: break
                if (m.range.first != 0) break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                times += min * 60_000 + sec * 1000 + ms
                rest = rest.substring(m.range.last + 1)
            }
            if (times.isEmpty()) continue
            val words = rest.trim()
            // LRC offset: positive means lyrics come earlier
            times.forEach { out += LrcLine((it - offset).coerceAtLeast(0), words) }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Index of the line being sung at [positionMs], or -1 before the first line. */
    fun currentIndex(lines: List<LrcLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** Plain words from LRC text (for saving into files that want unsynced lyrics). */
    fun toPlain(text: String): String = parse(text).joinToString("\n") { it.text }
}

/** Lyrics from LRCLIB (lrclib.net): free, no account, many time-synced lyrics. */
object LrcLib {
    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "Kororythm/1.1 (https://github.com/jose00735/Kororythm-)"

    suspend fun find(title: String, artist: String?, album: String?, durationSec: Int?): FoundLyrics? = withContext(Dispatchers.IO) {
        // 1) exact match by title + artist (+ album, duration when known)
        if (!artist.isNullOrBlank()) {
            val q = buildString {
                append("track_name=").append(enc(title)).append("&artist_name=").append(enc(artist))
                if (!album.isNullOrBlank()) append("&album_name=").append(enc(album))
                if (durationSec != null && durationSec > 0) append("&duration=").append(durationSec)
            }
            get("$BASE/get?$q")?.let { parseGet(it)?.let { f -> return@withContext f } }
        }
        // 2) search, then pick the closest duration that has lyrics
        val q = if (artist.isNullOrBlank()) "q=${enc(title)}" else "track_name=${enc(title)}&artist_name=${enc(artist)}"
        get("$BASE/search?$q")?.let { pickBest(it, durationSec) }
    }

    fun parseGet(json: String): FoundLyrics? {
        val o = JSONObject(json)
        val synced = o.optString("syncedLyrics").takeIf { o.has("syncedLyrics") && !o.isNull("syncedLyrics") && it.isNotBlank() }
        val plain = o.optString("plainLyrics").takeIf { o.has("plainLyrics") && !o.isNull("plainLyrics") && it.isNotBlank() }
        val instrumental = o.optBoolean("instrumental", false)
        if (synced == null && plain == null && !instrumental) return null
        return FoundLyrics(synced, plain, instrumental)
    }

    fun pickBest(json: String, durationSec: Int?): FoundLyrics? {
        val arr = JSONArray(json)
        var best: JSONObject? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val hasSynced = !o.isNull("syncedLyrics") && o.optString("syncedLyrics").isNotBlank()
            val hasPlain = !o.isNull("plainLyrics") && o.optString("plainLyrics").isNotBlank()
            if (!hasSynced && !hasPlain) continue
            val d = if (durationSec != null && o.has("duration") && !o.isNull("duration")) abs(o.getDouble("duration") - durationSec) else 0.0
            if (durationSec != null && d > 10) continue // probably another version of the song
            val score = d + (if (hasSynced) 0.0 else 5.0) + i * 0.01
            if (score < bestScore) { bestScore = score; best = o }
        }
        return best?.let { parseGet(it.toString()) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    }
}
