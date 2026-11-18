package com.koro.kororythm.library

import java.text.Normalizer
import java.util.Locale

/** Decade filter: 1990 for 1994, [UNKNOWN] when a song has no year. */
object Decades {
    const val UNKNOWN = -1

    fun of(year: Int?): Int = if (year == null || year < 1000) UNKNOWN else year / 10 * 10

    /** "1990s", "2000s", or "No year". */
    fun label(decade: Int): String = if (decade == UNKNOWN) "No year" else "${decade}s"

    /** True when no decade is picked (show everything) or the song's decade is picked. */
    fun matches(selected: Set<Int>, year: Int?): Boolean = selected.isEmpty() || of(year) in selected
}

/** Finds library songs that are the same song as a new download, so it can replace them. */
object SongMatch {

    private val bracket = Regex("[(\\[{][^)\\]}]*[)\\]}]")
    private val feat = Regex("\\s+(feat\\.?|ft\\.?|featuring|con|with|x)\\s+.*$", RegexOption.IGNORE_CASE)
    private val noise = Regex(
        "\\b(official|video|audio|lyrics?|letra|hd|hq|4k|remaster(ed)?|visualizer|videoclip|oficial|music)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** "La Gota Fría (Official Video) [HD]" → "la gota fria". */
    fun normTitle(s: String): String {
        var t = s.replace(bracket, " ")
        t = t.replace(feat, "")
        t = t.replace(noise, " ")
        return fold(t)
    }

    /** First credited artist, folded: "Edward Maya ft Vika Jigulina" → "edward maya". */
    fun normArtist(s: String?): String =
        fold((s ?: "").split(Regex("\\s*(,|&|;|/|\\bfeat\\.?|\\bft\\.?|\\bfeaturing|\\bx\\b|\\by\\b)\\s*", RegexOption.IGNORE_CASE)).first())

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /**
     * Same song: titles equal once cleaned (or one contains the other and is at least 5
     * letters), and artists agree when both are known.
     */
    fun same(titleA: String, artistA: String?, titleB: String, artistB: String?): Boolean {
        val ta = normTitle(titleA)
        val tb = normTitle(titleB)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val titleOk = ta == tb || (minOf(ta.length, tb.length) >= 5 && (ta.contains(tb) || tb.contains(ta)))
        if (!titleOk) return false
        val aa = normArtist(artistA)
        val ab = normArtist(artistB)
        return aa.isEmpty() || ab.isEmpty() || aa == ab || aa.contains(ab) || ab.contains(aa)
    }

    /** Library songs that match, best (exact title) first; never [exceptId]. */
    fun find(all: List<TrackEntity>, title: String, artist: String?, exceptId: Long = -1): List<TrackEntity> =
        all.filter { it.id != exceptId && same(title, artist, it.title, it.artist) }
            .sortedBy { if (normTitle(it.title) == normTitle(title)) 0 else 1 }
}
