package com.koro.kororythm.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tag suggestions from MusicBrainz (free, open music data) for tracks with missing tags.
 * Respects MusicBrainz's limit of one request per second and sends a proper User-Agent.
 */
object MusicBrainz {

    data class Suggestion(val title: String, val artist: String?, val album: String?, val year: Int?, val score: Int)

    private const val USER_AGENT = "Kororythm/1.2 ( https://github.com/jose00735/Kororythm- )"
    private val gate = Mutex()
    private var lastCall = 0L

    /** The last [get] failed for network reasons (offline, timeout, rate limit), not "not found". */
    @Volatile var lastFailed = false
        private set

    /** Builds a Lucene query: recording:"title" AND artist:"artist". */
    fun buildQuery(title: String, artist: String?): String {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return buildString {
            append("recording:").append(q(title))
            if (!artist.isNullOrBlank()) append(" AND artist:").append(q(artist))
        }
    }

    suspend fun search(title: String, artist: String?): List<Suggestion> = withContext(Dispatchers.IO) {
        gate.withLock {
            val wait = 1100 - (System.currentTimeMillis() - lastCall)
            if (wait > 0) delay(wait)
            lastCall = System.currentTimeMillis()
        }
        val url = URL(
            "https://musicbrainz.org/ws/2/recording/?fmt=json&limit=5&query=" +
                URLEncoder.encode(buildQuery(title, artist), "UTF-8"),
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode != 200) return@withContext emptyList()
            parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** GET a MusicBrainz web-service path ("recording/?query=…"), one request per second; null on errors. */
    suspend fun get(pathAndQuery: String): String? = withContext(Dispatchers.IO) {
        gate.withLock {
            val wait = 1100 - (System.currentTimeMillis() - lastCall)
            if (wait > 0) delay(wait)
            lastCall = System.currentTimeMillis()
        }
        val sep = if (pathAndQuery.contains('?')) '&' else '?'
        val conn = URL("https://musicbrainz.org/ws/2/$pathAndQuery${sep}fmt=json").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            when (conn.responseCode) {
                200 -> { lastFailed = false; conn.inputStream.bufferedReader().use { it.readText() } }
                503 -> { lastFailed = true; delay(3000); null } // rate-limited: back off
                else -> { lastFailed = false; null } // e.g. 404: nothing there
            }
        } catch (e: java.io.IOException) {
            lastFailed = true
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Parses the /ws/2/recording search JSON into suggestions (best first, no repeats). */
    fun parse(json: String): List<Suggestion> {
        val recs = JSONObject(json).optJSONArray("recordings") ?: return emptyList()
        val out = LinkedHashMap<String, Suggestion>()
        for (i in 0 until recs.length()) {
            val r = recs.getJSONObject(i)
            val title = r.optString("title").takeIf { it.isNotBlank() } ?: continue
            val credits = r.optJSONArray("artist-credit")
            val artist = credits?.let { a ->
                (0 until a.length()).joinToString("") { j ->
                    val c = a.getJSONObject(j)
                    c.optString("name") + c.optString("joinphrase")
                }.takeIf { it.isNotBlank() }
            }
            val release = r.optJSONArray("releases")?.takeIf { it.length() > 0 }?.getJSONObject(0)
            val album = release?.optString("title")?.takeIf { it.isNotBlank() }
            val year = TagRules.parseYear(release?.optString("date") ?: r.optString("first-release-date"))
            val s = Suggestion(title, artist, album, year, r.optInt("score", 0))
            out.putIfAbsent("$title|$artist|$album", s)
        }
        return out.values.sortedByDescending { it.score }.take(3)
    }
}
