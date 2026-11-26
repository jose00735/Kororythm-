package com.koro.kororythm.ui.pod

import com.koro.kororythm.library.PlaylistSummary
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.playback.TrackInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodMenuTest {

    private fun t(id: Long, title: String, artist: String?, album: String?, year: Int?, genre: String?, no: Int) = TrackEntity(
        id = id, uri = "u$id", title = title, artist = artist, album = album, albumArtist = null, genre = genre, year = year,
        trackNo = no, discNo = 1, durationMs = 200_000, bitrateKbps = 900, sampleRate = 44100, bits = 16, codec = "FLAC",
        sizeBytes = 1, contentKey = "k$id", artPath = null, source = "folder", sourceRoot = null, dateAdded = 0, tagsMissing = false,
        genreMain = genre,
    )

    private val tracks = listOf(
        t(1, "Neon Runway", "Grid Pilots", "Runways", 1994, "Electronic", 1),
        t(2, "Lightcycle Lullaby", "Grid Pilots", "Runways", 1994, "Electronic", 2),
        t(3, "Midnight Circuit", "Voltage Bloom", "Bloomfield", 2003, "Rock", 1),
        t(4, "Analog Horizon", "Grid Pilots", "Night Shift", 2011, "Electronic", 1),
        t(5, "Parrot in Orbit", null, null, null, null, 1),
    )

    private val catalog = object : PodCatalog {
        override suspend fun tracks() = tracks
        override suspend fun playlists() = listOf(PlaylistSummary(9, "Road trip", 2, 400_000, null))
        override suspend fun playlistTracks(id: Long) = listOf(tracks[2], tracks[0])
    }

    private class Recorder : PlayerActions {
        var played: Pair<List<TrackInfo>, Int>? = null
        var source: String? = null
        var jumped = -1
        var shuffles = 0
        override fun playTracks(tracks: List<TrackInfo>, start: Int, source: String?) { played = tracks to start; this.source = source }
        override fun playPause() {}; override fun play() {}; override fun pause() {}; override fun stop() {}
        override fun next() {}; override fun previous() {}; override fun seekTo(ms: Long) {}; override fun playAt(index: Int) { jumped = index }
        override fun toggleShuffle() { shuffles++ }; override fun cycleRepeat() {}; override fun removeAt(index: Int) {}; override fun clear() {}
        override fun setBand(i: Int, db: Float) {}; override fun setPreamp(db: Float) {}; override fun toggleEq() {}
        override fun toggleBoost() {}; override fun applyPreset(name: String) {}; override fun resetEq() {}
    }

    private fun menu(a: Recorder, st: PlayerUiState = PlayerUiState()) = PodMenuState(PodTree(catalog, a) { st })
    private fun PodMenuState.labels() = top.items.map { it.label }
    private fun PodMenuState.open(label: String, a: PlayerActions) = runBlocking { select(a, top.items.indexOfFirst { it.label == label }) }

    @Test fun rootAndMusic() {
        val a = Recorder(); val m = menu(a)
        assertEquals(listOf("Music", "Up Next", "Now Playing", "Shuffle Songs", "Settings"), m.labels())
        m.open("Music", a)
        assertEquals(listOf("Playlists", "Artists", "Albums", "Songs", "Genres", "Decades"), m.labels())
        assertEquals(listOf("1", "3", "4", "5", "3", "4"), m.top.items.map { it.detail })
    }

    @Test fun artistWithSeveralAlbumsGetsAllSongs() {
        val a = Recorder(); val m = menu(a)
        m.open("Music", a); m.open("Artists", a)
        assertEquals(listOf("Grid Pilots", "Unknown artist", "Voltage Bloom"), m.labels())
        m.open("Grid Pilots", a)
        assertEquals(listOf("All Songs", "Night Shift", "Runways"), m.labels())
        m.open("Runways", a)
        assertEquals(listOf("Shuffle", "Neon Runway", "Lightcycle Lullaby"), m.labels())
        val r = m.open("Lightcycle Lullaby", a)
        assertEquals(PodResult.NOW_PLAYING, r)
        assertEquals(1, a.played!!.second)
        assertEquals("Runways", a.source)
    }

    @Test fun decadesAndPlaylists() {
        val a = Recorder(); val m = menu(a)
        m.open("Music", a); m.open("Decades", a)
        assertEquals(listOf("1990s", "2000s", "2010s", "No year"), m.labels())
        assertTrue(m.back())
        m.open("Playlists", a); m.open("Road trip", a)
        assertEquals(listOf("Shuffle", "Midnight Circuit", "Neon Runway"), m.labels())
    }

    @Test fun backAndWheel() {
        val a = Recorder(); val m = menu(a)
        m.tick(-1); assertEquals(0, m.top.cursor)
        m.tick(10); assertEquals(4, m.top.cursor)
        assertFalse(m.back())
        m.open("Music", a); assertEquals(1, m.direction)
        assertTrue(m.back()); assertEquals(-1, m.direction)
        assertEquals("Kororythm", m.top.title)
        assertEquals(0, m.top.cursor) // Music stays highlighted
    }

    @Test fun upNextJumps() {
        val a = Recorder()
        val q = tracks.take(3).map { it.toTrackInfo() }
        val m = menu(a, PlayerUiState(queue = q, currentIndex = 1))
        m.open("Up Next", a)
        assertEquals("♪", m.top.items[1].detail)
        assertEquals(PodResult.NOW_PLAYING, runBlocking { m.select(a, 2) })
        assertEquals(2, a.jumped)
    }

    @Test fun shuffleSongsAndSettings() {
        val a = Recorder(); val m = menu(a)
        assertEquals(PodResult.NOW_PLAYING, m.open("Shuffle Songs", a))
        assertEquals(5, a.played!!.first.size)
        m.open("Settings", a)
        assertEquals(PodResult.REFRESH, m.open("Shuffle", a))
        assertEquals(1, a.shuffles)
    }
}
