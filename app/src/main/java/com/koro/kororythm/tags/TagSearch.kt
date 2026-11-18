package com.koro.kororythm.tags

import com.koro.kororythm.library.MusicBrainz
import com.koro.kororythm.library.TagRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One candidate set of tags from a public music database. */
data class TagMatch(
    val source: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
    val trackNo: Int?,
    val trackTotal: Int?,
    val discNo: Int?,
    val genre: String?,
    val coverUrl: String?,
    val score: Int,
)

/**
 * Searches two free public databases at once: MusicBrainz (open music encyclopedia, covers
 * from the Cover Art Archive) and Apple's iTunes Search API (good covers and genres).
 */
object TagSearch {

    const val MB = "MusicBrainz"
    const val ITUNES = "iTunes"
    private const val USER_AGENT = "Kororythm/1.0 ( https://github.com/jose00735/Kororythm- )"
    private val mbGate = Mutex()
    private var mbLast = 0L

    suspend fun search(title: String, artist: String?): List<TagMatch> = coroutineScope {
        val mb = async { runCatching { musicBrainz(title, artist) }.getOrDefault(emptyList()) }
        val it = async { runCatching { itunes(listOfNotNull(artist, title).joinToString(" ")) }.getOrDefault(emptyList()) }
        mb.await() + it.await()
    }

    private suspend fun musicBrainz(title: String, artist: String?): List<TagMatch> {
        mbGate.withLock {
            val wait = 1100 - (System.currentTimeMillis() - mbLast)
            if (wait > 0) delay(wait)
            mbLast = System.currentTimeMillis()
        }
        val url = "https://musicbrainz.org/ws/2/recording/?fmt=json&limit=6&query=" +
            URLEncoder.encode(MusicBrainz.buildQuery(title, artist), "UTF-8")
        return parseMusicBrainz(get(url))
    }

    private suspend fun itunes(term: String): List<TagMatch> {
        val url = "https://itunes.apple.com/search?media=music&entity=song&limit=6&term=" + URLEncoder.encode(term, "UTF-8")
        return parseItunes(get(url))
    }

    fun parseMusicBrainz(json: String): List<TagMatch> {
        val recs = JSONObject(json).optJSONArray("recordings") ?: return emptyList()
        val out = mutableListOf<TagMatch>()
        val seen = HashSet<String>()
        for (i in 0 until recs.length()) {
            val r = recs.getJSONObject(i)
            val title = r.optString("title").takeIf { it.isNotBlank() } ?: continue
            val artist = r.optJSONArray("artist-credit")?.let { a ->
                (0 until a.length()).joinToString("") { j -> a.getJSONObject(j).let { it.optString("name") + it.optString("joinphrase") } }
            }?.takeIf { it.isNotBlank() }
            val rel = r.optJSONArray("releases")?.takeIf { it.length() > 0 }?.getJSONObject(0)
            val medium = rel?.optJSONArray("media")?.takeIf { it.length() > 0 }?.getJSONObject(0)
            val track = medium?.optJSONArray("track")?.takeIf { it.length() > 0 }?.getJSONObject(0)
            val relId = rel?.optString("id")?.takeIf { it.isNotBlank() }
            val relArtist = rel?.optJSONArray("artist-credit")?.let { a ->
                (0 until a.length()).joinToString("") { j -> a.getJSONObject(j).let { it.optString("name") + it.optString("joinphrase") } }
            }?.takeIf { it.isNotBlank() }
            val m = TagMatch(
                source = MB,
                title = title,
                artist = artist,
                album = rel?.optString("title")?.takeIf { it.isNotBlank() },
                albumArtist = relArtist,
                year = TagRules.parseYear(rel?.optString("date") ?: r.optString("first-release-date")),
                trackNo = TagRules.parseNumber(track?.optString("number")),
                trackTotal = medium?.optInt("track-count", 0)?.takeIf { it > 0 },
                discNo = medium?.optInt("position", 0)?.takeIf { it > 0 },
                genre = null,
                coverUrl = relId?.let { "https://coverartarchive.org/release/$it/front-500" },
                score = r.optInt("score", 0),
            )
            if (seen.add("${m.title}|${m.artist}|${m.album}")) out += m
        }
        return out.sortedByDescending { it.score }.take(5)
    }

    fun parseItunes(json: String): List<TagMatch> {
        val res = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<TagMatch>()
        for (i in 0 until res.length()) {
            val r = res.getJSONObject(i)
            val title = r.optString("trackName").takeIf { it.isNotBlank() } ?: continue
            out += TagMatch(
                source = ITUNES,
                title = title,
                artist = r.optString("artistName").takeIf { it.isNotBlank() },
                album = r.optString("collectionName").takeIf { it.isNotBlank() },
                albumArtist = r.optString("collectionArtistName").takeIf { it.isNotBlank() },
                year = TagRules.parseYear(r.optString("releaseDate")),
                trackNo = r.optInt("trackNumber", 0).takeIf { it > 0 },
                trackTotal = r.optInt("trackCount", 0).takeIf { it > 0 },
                discNo = r.optInt("discNumber", 0).takeIf { it > 0 },
                genre = r.optString("primaryGenreName").takeIf { it.isNotBlank() },
                coverUrl = r.optString("artworkUrl100").takeIf { it.isNotBlank() }?.replace("100x100bb", "600x600bb"),
                score = 90 - i, // iTunes returns best first
            )
        }
        return out.take(5)
    }

    /** Downloads a cover image (max 8 MB). */
    suspend fun fetchCover(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(url)
            try {
                if (c.responseCode !in 200..299) return@runCatching null
                val o = ByteArrayOutputStream()
                c.inputStream.use { ins ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        if (o.size() > 8 * 1024 * 1024) return@runCatching null
                    }
                }
                o.toByteArray()
            } finally { c.disconnect() }
        }.getOrNull()
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/json, image/*")
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = true
    }
}

/** File-name helpers for the editor: tags -> name, and name -> tags. */
object TagNaming {
    enum class Pattern(val label: String) {
        ARTIST_TITLE("Artist - Title"),
        NUM_ARTIST_TITLE("01 - Artist - Title"),
        NUM_TITLE("01 - Title"),
    }

    private val illegal = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    fun sanitize(name: String): String = name.replace(illegal, "_").replace(Regex("\\s+"), " ").trim().trim('.').take(180)

    fun build(p: Pattern, trackNo: Int?, artist: String?, title: String?): String {
        val n = trackNo?.let { "%02d".format(it) }
        val a = artist?.takeIf { it.isNotBlank() }
        val t = title?.takeIf { it.isNotBlank() } ?: "Untitled"
        val parts = when (p) {
            Pattern.ARTIST_TITLE -> listOfNotNull(a, t)
            Pattern.NUM_ARTIST_TITLE -> listOfNotNull(n, a, t)
            Pattern.NUM_TITLE -> listOfNotNull(n, t)
        }
        return sanitize(parts.joinToString(" - "))
    }

    /** "03 - Grid Pilots - Neon Runway" -> (3, "Grid Pilots", "Neon Runway"). */
    fun parse(baseName: String): Triple<Int?, String?, String> {
        val num = Regex("^\\s*(\\d{1,3})\\s*[-.)]?\\s+").find(baseName)?.groupValues?.get(1)?.toIntOrNull()
        val (artist, title) = TagRules.parseFileName("$baseName.x")
        return Triple(num, artist, title)
    }
}
