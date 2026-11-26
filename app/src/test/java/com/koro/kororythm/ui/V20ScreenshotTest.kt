package com.koro.kororythm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.koro.kororythm.library.PlaylistSummary
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.playback.TrackInfo
import com.koro.kororythm.ui.player.CoverLayout
import com.koro.kororythm.ui.player.LayoutHooks
import com.koro.kororythm.ui.player.PodLayout
import com.koro.kororythm.ui.player.PodMode
import com.koro.kororythm.ui.player.PrismLayout
import com.koro.kororythm.ui.pod.BrowseSheet
import com.koro.kororythm.ui.pod.BrowseState
import com.koro.kororythm.ui.pod.PodCatalog
import com.koro.kororythm.ui.pod.PodMenuState
import com.koro.kororythm.ui.pod.PodTree
import com.koro.kororythm.ui.skin.SkinId
import com.koro.kororythm.ui.skin.SkinState
import com.koro.kororythm.ui.viz.VizState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sin

/** v2.0: iPod menus in the Pod layout, the browse sheet elsewhere, the Windows 7 bar in Prism. */
class V20ScreenshotTest {

    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
        maxPercentDifference = 0.1,
    )

    @After fun reset() { SkinState.set(SkinId.NEON); BrowseState.open = false }

    private object NoActions : PlayerActions {
        override fun playPause() {}; override fun play() {}; override fun pause() {}; override fun stop() {}
        override fun next() {}; override fun previous() {}; override fun seekTo(ms: Long) {}; override fun playAt(index: Int) {}
        override fun toggleShuffle() {}; override fun cycleRepeat() {}; override fun removeAt(index: Int) {}; override fun clear() {}
        override fun setBand(i: Int, db: Float) {}; override fun setPreamp(db: Float) {}; override fun toggleEq() {}
        override fun toggleBoost() {}; override fun applyPreset(name: String) {}; override fun resetEq() {}
    }

    private fun t(id: Long, title: String, artist: String, album: String, year: Int, genre: String) = TrackEntity(
        id = id, uri = "u$id", title = title, artist = artist, album = album, albumArtist = null, genre = genre, year = year,
        trackNo = id.toInt(), discNo = 1, durationMs = 200_000 + id * 7000, bitrateKbps = 900, sampleRate = 44100, bits = 16, codec = "FLAC",
        sizeBytes = 1, contentKey = "k$id", artPath = null, source = "folder", sourceRoot = null, dateAdded = 0, tagsMissing = false, genreMain = genre,
    )

    private val tracks = listOf(
        t(1, "Neon Runway", "Grid Pilots", "Runways", 1994, "Electronic"),
        t(2, "Lightcycle Lullaby", "Grid Pilots", "Runways", 1994, "Electronic"),
        t(3, "Midnight Circuit", "Voltage Bloom", "Bloomfield", 2003, "Rock"),
        t(4, "Analog Horizon", "Static Coast", "Coastlines", 2011, "Ambient"),
        t(5, "Parrot in Orbit", "Koro Sound System", "Orbit", 2019, "Electronic"),
        t(6, "Cyan Static", "Voltage Bloom", "Bloomfield", 2003, "Rock"),
        t(7, "Ferrofluid", "Magnet Choir", "Fields", 2008, "Electronic"),
        t(8, "Tidal Glass", "Static Coast", "Coastlines", 2011, "Ambient"),
        t(9, "Halo Drift", "Orbit Kids", "Satellites", 1998, "Pop"),
    )
    private val catalog = object : PodCatalog {
        override suspend fun tracks() = tracks
        override suspend fun playlists() = listOf(PlaylistSummary(1, "Road trip", 12, 0, null), PlaylistSummary(2, "Late night", 30, 0, null))
        override suspend fun playlistTracks(id: Long) = tracks.take(4)
    }

    private val queue = tracks.take(6).map { it.toTrackInfo() }
    private val state = PlayerUiState(connected = true, isPlaying = true, positionMs = 83_000, durationMs = 312_000, queue = queue, currentIndex = 1)

    private fun menu(vararg path: String) = PodMenuState(PodTree(catalog, NoActions) { state }).also { m ->
        runBlocking { path.forEach { p -> m.select(NoActions, m.top.items.indexOfFirst { it.label == p }) } }
    }

    private fun viz() = VizState().apply {
        val b = FloatArray(64) { i -> (0.85f * exp(-i / 40f) + 0.18f * sin(i * 0.9f) + 0.15f).coerceIn(0.05f, 0.98f) }
        bars = b; peaks = FloatArray(64) { (b[it] + 0.08f).coerceAtMost(1f) }
        scope = FloatArray(128) { (0.6 * sin(it * 0.25) * sin(it * 0.03)).toFloat() }
    }

    @Test fun podMusicMenu() = listOf(SkinId.POD, SkinId.NEON).forEach { s ->
        SkinState.set(s)
        val m = menu("Music").also { it.tick(1) }
        paparazzi.snapshot("200_pod_music_${s.name.lowercase()}") { PodLayout(state, NoActions, LayoutHooks(), startMode = PodMode.MENU, menuState = m) }
    }

    @Test fun podArtistAlbums() {
        SkinState.set(SkinId.CHROME)
        val m = menu("Music", "Artists").also { it.tick(3) }
        paparazzi.snapshot("201_pod_artists_chrome") { PodLayout(state, NoActions, LayoutHooks(), startMode = PodMode.MENU, menuState = m) }
    }

    @Test fun podSettings() {
        SkinState.set(SkinId.POD)
        val m = menu("Settings", "Skin")
        paparazzi.snapshot("202_pod_settings_skin") { PodLayout(state, NoActions, LayoutHooks(), startMode = PodMode.MENU, menuState = m) }
    }

    @Test fun browseSheetOverCover() = listOf(SkinId.NEON, SkinId.HIFI).forEach { s ->
        SkinState.set(s); BrowseState.open = true
        val m = menu("Music", "Albums").also { it.tick(2) }
        paparazzi.snapshot("203_browse_${s.name.lowercase()}") {
            Box(Modifier.fillMaxSize()) { CoverLayout(state, NoActions, LayoutHooks()); BrowseSheet(state, NoActions, m) }
        }
    }

    @Test fun prismWmpBar() = SkinId.entries.forEach { s ->
        SkinState.set(s)
        paparazzi.snapshot("204_prism_wmp_${s.name.lowercase()}") { PrismLayout(state.copy(shuffle = true, repeatMode = 2), NoActions, viz(), LayoutHooks(), frozenFrames = 90) }
    }
}
