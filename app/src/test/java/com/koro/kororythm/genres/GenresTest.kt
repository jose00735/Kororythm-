package com.koro.kororythm.genres

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenresTest {

    @Test fun subGenresLandInTheRightFamily() {
        val cases = mapOf(
            "synthwave" to "Electronic", "darksynth" to "Electronic", "drum and bass" to "Electronic", "deep house" to "Electronic",
            "vallenato" to "Latin", "champeta" to "Latin", "cumbia" to "Latin", "reggaeton" to "Latin", "salsa" to "Latin", "Pop Latino" to "Latin",
            "trap latino" to "Latin", "boom bap" to "Hip Hop", "Hip-Hop/Rap" to "Hip Hop", "dancehall" to "Reggae", "dubstep" to "Electronic",
            "pop punk" to "Rock", "post-punk" to "Rock", "indie rock" to "Rock", "indie pop" to "Pop", "synth-pop" to "Pop", "k-pop" to "Pop",
            "melodic death metal" to "Metal", "neo soul" to "R&B & Soul", "R&B/Soul" to "R&B & Soul", "nu-disco" to "Funk & Disco",
            "bebop" to "Jazz", "delta blues" to "Blues", "bluegrass" to "Country", "Singer/Songwriter" to "Folk", "baroque" to "Classical",
            "video game music" to "Soundtrack", "afrobeats" to "World", "Alternative" to "Rock", "Dance" to "Electronic",
        )
        cases.forEach { (name, main) -> assertEquals(name, main, GenreMap.mainOf(name)) }
        assertNull(GenreMap.mainOf("seen live"))
        assertNull(GenreMap.mainOf("british"))
    }

    @Test fun votesPickMainAndSubGenre() {
        val (main, sub) = GenreMap.classify(
            listOf(
                Vote("synthwave", 9.0), Vote("electronic", 4.0), Vote("retrowave", 3.0),
                Vote("seen live", 20.0), Vote("pop", 2.0),
            ),
        )
        assertEquals("Electronic", main)
        assertEquals("Synthwave", sub)
        // only the broad name → no sub-genre
        assertEquals("Rock" to null, GenreMap.classify(listOf(Vote("rock", 3.0), Vote("Rock", 1.0))))
        assertEquals(null to null, GenreMap.classify(listOf(Vote("favorites", 5.0))))
        assertEquals("Drum and Bass", GenreMap.pretty("drum and bass"))
        assertEquals("Synth-Pop", GenreMap.pretty("synth-pop"))
    }

    private val recordingSearch = """
        {"recordings":[
          {"id":"r0","score":100,"title":"Neon Runway","artist-credit":[{"name":"Somebody Else","artist":{"id":"x"}}],"tags":[{"count":5,"name":"pop"}]},
          {"id":"r1","score":98,"title":"Neon Runway","artist-credit":[{"name":"Grid Pilots","joinphrase":"","artist":{"id":"art-1","name":"Grid Pilots"}}],
           "tags":[{"count":3,"name":"synthwave"},{"count":1,"name":"seen live"}],
           "releases":[
             {"id":"rel-c","release-group":{"id":"rg-comp","primary-type":"Compilation"}},
             {"id":"rel-a","release-group":{"id":"rg-album","primary-type":"Album"}}]}
        ]}
    """.trimIndent()

    @Test fun musicBrainzMatchingAndGenres() {
        val m = GenreParse.recording(recordingSearch, "Grid Pilots")!!
        assertEquals("rg-album", m.releaseGroupId)
        assertEquals("art-1", m.artistId)
        assertEquals(listOf("synthwave", "seen live"), m.tags.map { it.name })
        assertNull(GenreParse.recording(recordingSearch, "Nobody At All"))

        val rg = """{"id":"rg-album","genres":[{"count":4,"name":"synthwave"},{"count":2,"name":"electronic"}],"tags":[{"count":1,"name":"80s"}]}"""
        val votes = m.tags + GenreParse.entityGenres(rg, 2.0)
        assertEquals("Electronic" to "Synthwave", GenreMap.classify(votes))

        val artists = """{"artists":[{"id":"a9","score":100,"name":"Silvestre Dangond"},{"id":"a8","score":80,"name":"Silvestre"}]}"""
        assertEquals("a9", GenreParse.artistId(artists, "Silvestre Dangond"))
        val itunes = """{"results":[{"artistName":"Silvestre Dangond & Juancho de la Espriella","primaryGenreName":"Vallenato"}]}"""
        assertEquals("Vallenato", GenreParse.itunesGenre(itunes, "Silvestre Dangond"))
        assertEquals("Latin" to "Vallenato", GenreMap.classify(listOf(Vote("Vallenato", 2.0))))
    }
}
