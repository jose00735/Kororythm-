package com.koro.kororythm.library

import com.koro.kororythm.ui.wizards.AlbumDraft
import com.koro.kororythm.ui.wizards.AlbumWizardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {

    @Test fun fileNameWithNumberArtistAndTitle() {
        assertEquals("Grid Pilots" to "Neon Runway", TagRules.parseFileName("03 - Grid Pilots - Neon Runway.flac"))
        assertEquals("Grid Pilots" to "Neon Runway", TagRules.parseFileName("03. Grid Pilots - Neon Runway.mp3"))
    }

    @Test fun fileNameWithTitleOnly() {
        assertEquals(null to "Neon Runway", TagRules.parseFileName("Neon_Runway.mp3"))
        assertEquals(null to "Neon Runway", TagRules.parseFileName("12 Neon Runway.flac"))
    }

    @Test fun numbersAndYears() {
        assertEquals(3, TagRules.parseNumber("3/12"))
        assertEquals(7, TagRules.parseNumber("07"))
        assertNull(TagRules.parseNumber("x"))
        assertEquals(2001, TagRules.parseYear("2001-05-01"))
        assertEquals(1999, TagRules.parseYear("19990101T000000.000Z"))
        assertNull(TagRules.parseYear("n/a"))
    }

    @Test fun audioDetection() {
        assertTrue(TagRules.isAudio("audio/flac", "a.bin"))
        assertTrue(TagRules.isAudio(null, "song.MP3"))
        assertFalse(TagRules.isAudio("audio/ogg", "song.ogg"))
        assertFalse(TagRules.isAudio("image/jpeg", "cover.jpg"))
    }

    @Test fun missingTags() {
        assertTrue(TagRules.tagsMissing(null, "A", "B"))
        assertTrue(TagRules.tagsMissing("T", "", "B"))
        assertFalse(TagRules.tagsMissing("T", "A", "B"))
    }

    @Test fun contentKeySpotsTheSameFile() {
        val a = ByteArray(1000) { (it % 251).toByte() }
        val b = a.copyOf()
        assertEquals(TagRules.contentKey(a, a.size, 5_000_000), TagRules.contentKey(b, b.size, 5_000_000))
        assertNotEquals(TagRules.contentKey(a, a.size, 5_000_000), TagRules.contentKey(a, a.size, 5_000_001))
        b[10] = 0
        assertNotEquals(TagRules.contentKey(a, a.size, 5_000_000), TagRules.contentKey(b, b.size, 5_000_000))
    }

    @Test fun searchQueryIsSafePrefixMatch() {
        assertEquals("neon* run*", TagRules.ftsQuery("Neon run"))
        assertEquals("ac* dc*", TagRules.ftsQuery("AC/DC"))
        assertEquals("beyoncé*", TagRules.ftsQuery("\"Beyoncé\" -"))
        assertNull(TagRules.ftsQuery("  -* "))
    }

    @Test fun musicBrainzQueryEscapesQuotes() {
        assertEquals("recording:\"Say \\\"Hi\\\"\" AND artist:\"Grid\"", MusicBrainz.buildQuery("Say \"Hi\"", "Grid"))
        assertEquals("recording:\"Solo\"", MusicBrainz.buildQuery("Solo", null))
    }

    @Test fun musicBrainzParsesBestMatchesFirst() {
        val json = """
        {"recordings":[
          {"id":"1","score":72,"title":"Neon Runway",
           "artist-credit":[{"name":"Grid Pilots","joinphrase":" feat. "},{"name":"Volt","joinphrase":""}],
           "releases":[{"title":"Night Drive","date":"2019-03-01"}]},
          {"id":"2","score":100,"title":"Neon Runway",
           "artist-credit":[{"name":"Grid Pilots","joinphrase":""}],
           "first-release-date":"2018",
           "releases":[{"title":"Runways","date":"2018-07-12"}]},
          {"id":"3","score":95,"title":"Neon Runway",
           "artist-credit":[{"name":"Grid Pilots","joinphrase":""}],
           "releases":[{"title":"Runways","date":"2018-07-12"}]},
          {"id":"4","score":40,"title":"Neon Runway (Live)","artist-credit":[{"name":"Grid Pilots"}]}
        ]}
        """.trimIndent()
        val s = MusicBrainz.parse(json)
        assertEquals(3, s.size)
        assertEquals(MusicBrainz.Suggestion("Neon Runway", "Grid Pilots", "Runways", 2018, 100), s[0])
        assertEquals("Grid Pilots feat. Volt", s[1].artist)
        assertEquals(2019, s[1].year)
        assertEquals("Neon Runway (Live)", s[2].title)
        assertNull(s[2].album)
    }

    @Test fun albumWizardPrefillsFromMostCommonTags() {
        fun t(id: Long, album: String?, artist: String?, year: Int?, genre: String?) = TrackEntity(
            id = id, uri = "u$id", title = "T$id", artist = artist, album = album, albumArtist = null, genre = genre,
            year = year, trackNo = null, discNo = null, durationMs = 1000, bitrateKbps = null, sampleRate = null,
            bits = null, codec = null, sizeBytes = 1, contentKey = "k$id", artPath = null, source = "folder",
            sourceRoot = null, dateAdded = 0, tagsMissing = false,
        )
        val d = AlbumDraft(
            selected = listOf(
                t(1, "Runways", "Grid Pilots", 2018, "Synthwave"),
                t(2, "Runways", "Grid Pilots", 2018, null),
                t(3, "Other", "Volt", 2020, "Synthwave"),
            ),
            genre = "Mine",
        )
        val p = AlbumWizardViewModel.prefill(d)
        assertEquals("Runways", p.title)
        assertEquals("Grid Pilots", p.artist)
        assertEquals("2018", p.year)
        assertEquals("Mine", p.genre) // the user's own entry is kept
    }
}
