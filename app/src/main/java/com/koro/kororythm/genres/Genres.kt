package com.koro.kororythm.genres

import org.json.JSONObject
import java.util.Locale

/** A genre name with how strongly a source backs it. */
data class Vote(val name: String, val weight: Double)

/**
 * Sorts genre names into a broad genre plus a finer sub-genre.
 * "synthwave" → Electronic / Synthwave, "vallenato" → Latin / Vallenato, "rock" → Rock / –.
 */
object GenreMap {

    val MAIN = listOf(
        "Rock", "Pop", "Electronic", "Hip Hop", "R&B & Soul", "Latin", "Metal", "Jazz", "Classical",
        "Reggae", "Country", "Folk", "Blues", "Funk & Disco", "Soundtrack", "World",
    )

    // First match wins, so the more specific families come first.
    private val rules: List<Pair<Regex, String>> = listOf(
        "metal|grindcore|djent|deathcore" to "Metal",
        "reggaeton|dembow|salsa|bachata|merengue|cumbia|vallenato|champeta|bolero|tango|bossa nova|samba|\\bmpb\\b|ranchera|mariachi|" +
            "\\bbanda\\b|norte[ñn]o|corrido|\\blatin|porro|son cubano|timba|\\btropical\\b|urbano|guaracha|huayno|joropo|perreo|mexicano|regional mexican|m[uú]sica popular|llanera|carranga" to "Latin",
        "hip[ -]?hop|\\brap\\b|\\btrap\\b|\\bdrill\\b|grime|boom bap|gangsta|crunk|phonk" to "Hip Hop",
        "reggae|\\bska\\b|\\bdub\\b|dancehall|rocksteady|lovers rock" to "Reggae",
        "soundtrack|film score|\\bscore\\b|video game music|anime|musical theatre|show tunes" to "Soundtrack",
        "classical|baroque|opera|orchestral|chamber music|symphon|choral|romanticism|minimalism|contemporary classical|concerto|sonata" to "Classical",
        "punk|grunge|shoegaze|\\bemo\\b|post-rock|\\brock|alternative|indie$|^indie\\b(?!.*pop)|new wave|britpop|psychedelic|garage rock|stoner" to "Rock",
        "\\bsoul\\b|r&b|r'n'b|rhythm and blues|\\brnb\\b|neo soul|gospel|motown|quiet storm|new jack swing" to "R&B & Soul",
        "\\bfunk|disco|boogie|go-go" to "Funk & Disco",
        "jazz|swing|bebop|big band|bossa|fusion|smooth jazz|dixieland" to "Jazz",
        "blues" to "Blues",
        "country|bluegrass|americana|honky tonk" to "Country",
        "folk|singer[-/ ]songwriter|acoustic|celtic|cantautor|trova|nueva canci[oó]n" to "Folk",
        "\\bpop\\b|-pop|pop$|^pop|k-pop|j-pop|c-pop|city pop|schlager|ballad" to "Pop",
        "house|techno|trance|\\bedm\\b|electro|synth|dubstep|drum (and|&|n) bass|\\bdnb\\b|jungle|breakbeat|\\bidm\\b|ambient|downtempo|" +
            "chillout|chill-?out|lo-?fi|vaporwave|\\bgarage\\b|hardstyle|hardcore|chiptune|eurobeat|eurodance|big beat|trip hop|glitch|footwork|" +
            "electronic|\\bdance\\b|future bass|moombahton|\\bbass music\\b|industrial|darkwave|new age" to "Electronic",
        "afro|world|k-|bhangra|flamenco|fado|chanson|highlife|soukous|gnawa|raï|klezmer" to "World",
    ).map { (p, g) -> Regex(p, RegexOption.IGNORE_CASE) to g }

    /** Broad genre of one name, or null if it isn't a genre we know (e.g. "seen live"). */
    fun mainOf(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        MAIN.firstOrNull { it.equals(n, ignoreCase = true) }?.let { return it }
        when (n.lowercase(Locale.ROOT)) {
            "hip-hop", "rap" -> return "Hip Hop"
            "r&b", "rnb", "soul" -> return "R&B & Soul"
            "dance", "edm", "electronica" -> return "Electronic"
            "funk", "disco" -> return "Funk & Disco"
        }
        return rules.firstOrNull { (r, _) -> r.containsMatchIn(n) }?.second
    }

    /** True for names that only say the broad genre ("rock", "electronic music"). */
    private fun isGeneric(name: String, main: String): Boolean {
        val n = name.lowercase(Locale.ROOT).removeSuffix(" music").trim()
        return n == main.lowercase(Locale.ROOT) || n in setOf("electronica", "edm", "dance", "rap", "hip-hop", "r&b", "soul", "funk", "disco", "latin", "world")
    }

    /**
     * Picks the broad genre with the most weight behind it, then the strongest finer name
     * inside that genre as the sub-genre.
     */
    fun classify(votes: List<Vote>): Pair<String?, String?> {
        val mapped = votes.mapNotNull { v -> mainOf(v.name)?.let { m -> Triple(v.name.trim(), m, v.weight) } }
        if (mapped.isEmpty()) return null to null
        val main = mapped.groupBy { it.second }.maxByOrNull { (_, l) -> l.sumOf { it.third } }!!.key
        val sub = mapped.filter { it.second == main && !isGeneric(it.first, main) }
            .groupBy { it.first.lowercase(Locale.ROOT) }
            .maxByOrNull { (_, l) -> l.sumOf { it.third } }
            ?.value?.first()?.first?.let(::pretty)
        return main to sub
    }

    /** "drum and bass" → "Drum and Bass", "synth-pop" → "Synth-Pop", "r&b" stays sensible. */
    fun pretty(name: String): String {
        val small = setOf("and", "of", "the", "n", "y", "de", "la")
        return name.trim().split(' ').mapIndexed { i, w ->
            if (i > 0 && w.lowercase(Locale.ROOT) in small) w.lowercase(Locale.ROOT)
            else w.split('-').joinToString("-") { p -> if (p.length <= 3 && p.any { it == '&' }) p.uppercase(Locale.ROOT) else p.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
        }.joinToString(" ")
    }
}

/** Reading MusicBrainz / iTunes answers for the genre lookup (pure, unit-tested). */
object GenreParse {

    /** Best recording match: its tags, plus the album (release group) and artist ids to ask next. */
    data class Match(val score: Int, val tags: List<Vote>, val releaseGroupId: String?, val artistId: String?)

    private fun norm(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun recording(json: String, artist: String): Match? {
        val recs = JSONObject(json).optJSONArray("recordings") ?: return null
        val want = norm(artist)
        for (i in 0 until recs.length()) {
            val r = recs.getJSONObject(i)
            val score = r.optInt("score", 0)
            if (score < 85) continue
            val credits = r.optJSONArray("artist-credit")
            val names = (0 until (credits?.length() ?: 0)).joinToString(" ") { credits!!.getJSONObject(it).optString("name") }
            if (want.isNotEmpty() && !norm(names).contains(want) && !want.contains(norm(names).ifEmpty { "\u0000" })) continue
            val artistId = credits?.optJSONObject(0)?.optJSONObject("artist")?.optString("id")?.takeIf { it.isNotEmpty() }
            val rg = r.optJSONArray("releases")?.let { rel ->
                // Prefer an official album over compilations.
                (0 until rel.length()).map { rel.getJSONObject(it) }
                    .sortedBy { if (it.optJSONObject("release-group")?.optString("primary-type") == "Album") 0 else 1 }
                    .firstNotNullOfOrNull { it.optJSONObject("release-group")?.optString("id")?.takeIf { s -> s.isNotEmpty() } }
            }
            return Match(score, votes(r, "tags", 3.0), rg, artistId)
        }
        return null
    }

    /** "genres" (curated) and "tags" (free-form) of a release group or artist lookup. */
    fun entityGenres(json: String, weight: Double): List<Vote> {
        val o = JSONObject(json)
        return votes(o, "genres", weight) + votes(o, "tags", weight * 0.5)
    }

    fun artistId(json: String, artist: String): String? {
        val arr = JSONObject(json).optJSONArray("artists") ?: return null
        val want = norm(artist)
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (a.optInt("score", 0) >= 90 && norm(a.optString("name")) == want) return a.optString("id").takeIf { it.isNotEmpty() }
        }
        return null
    }

    fun itunesGenre(json: String, artist: String): String? {
        val arr = JSONObject(json).optJSONArray("results") ?: return null
        val want = norm(artist)
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (norm(r.optString("artistName")).let { it.contains(want) || want.contains(it) }) {
                return r.optString("primaryGenreName").takeIf { it.isNotEmpty() && it != "Music" }
            }
        }
        return null
    }

    private fun votes(o: JSONObject, key: String, weight: Double): List<Vote> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = t.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Vote(name, weight * (1 + t.optInt("count", 1).coerceIn(1, 20)) / 2.0)
        }
    }
}
