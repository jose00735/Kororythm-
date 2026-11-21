package com.koro.kororythm.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.koro.kororythm.devices.OutputDevice
import com.koro.kororythm.devices.OutputKind
import com.koro.kororythm.dsp.EqPresets
import com.koro.kororythm.dsp.EqSettings
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.playback.TrackInfo
import com.koro.kororythm.ui.library.LibraryCallbacks
import com.koro.kororythm.ui.library.LibraryContent
import com.koro.kororythm.ui.library.LibraryUi
import com.koro.kororythm.ui.player.CoverLayout
import com.koro.kororythm.ui.player.LayoutHooks
import com.koro.kororythm.ui.player.PlayerLayout
import com.koro.kororythm.ui.player.PodLayout
import com.koro.kororythm.ui.player.PodMode
import com.koro.kororythm.ui.player.PrismLayout
import com.koro.kororythm.ui.player.StyleSheet
import com.koro.kororythm.ui.player.StyleSheetState
import com.koro.kororythm.ui.skin.SkinId
import com.koro.kororythm.ui.skin.SkinState
import com.koro.kororythm.ui.viz.VizState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sin

/** v1.5: the new layouts (Cover Stage, Pod, Prism) and skins (Pod, Chrome Deck). */
class V15ScreenshotTest {

    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
        maxPercentDifference = 0.1,
    )

    @After fun reset() { SkinState.set(SkinId.NEON); StyleSheetState.open = false }

    private object NoActions : PlayerActions {
        override fun playPause() {}; override fun play() {}; override fun pause() {}; override fun stop() {}
        override fun next() {}; override fun previous() {}; override fun seekTo(ms: Long) {}; override fun playAt(index: Int) {}
        override fun toggleShuffle() {}; override fun cycleRepeat() {}; override fun removeAt(index: Int) {}; override fun clear() {}
        override fun setBand(i: Int, db: Float) {}; override fun setPreamp(db: Float) {}; override fun toggleEq() {}
        override fun toggleBoost() {}; override fun applyPreset(name: String) {}; override fun resetEq() {}
    }

    private val queue = listOf(
        TrackInfo("1", "Neon Runway", "Grid Pilots", "Runways", 245_000, 1012, 44100, 16, "FLAC"),
        TrackInfo("2", "Midnight Circuit", "Voltage Bloom", "Bloomfield", 312_000, 2304, 96000, 24, "FLAC"),
        TrackInfo("3", "Parrot in Orbit", "Koro Sound System", null, 198_000, 320, 44100, null, "MP3"),
        TrackInfo("4", "Lightcycle Lullaby", "Grid Pilots", "Runways", 276_000, 980, 44100, 16, "FLAC"),
        TrackInfo("5", "Analog Horizon", "Static Coast", null, 401_000, 256, 48000, null, "MP3"),
        TrackInfo("6", "Cyan Static", "Voltage Bloom", null, 233_000, 1100, 44100, 16, "FLAC"),
    )
    private val state = PlayerUiState(connected = true, isPlaying = true, positionMs = 83_000, durationMs = 312_000, queue = queue, currentIndex = 1)
    private val eq = EqSettings(bandsDb = EqPresets.all.getValue("Rock"), boost = true, presetName = "Rock")
    private val dac = OutputDevice(OutputKind.USB_DAC, "FiiO KA3", 384000, 32, bitPerfectCapable = true)
    private fun viz() = VizState().apply {
        val b = FloatArray(64) { i -> (0.85f * exp(-i / 40f) + 0.18f * sin(i * 0.9f) + 0.15f).coerceIn(0.05f, 0.98f) }
        bars = b; peaks = FloatArray(64) { (b[it] + 0.08f).coerceAtMost(1f) }
        scope = FloatArray(128) { (0.6 * sin(it * 0.25) * sin(it * 0.03)).toFloat() }
    }

    private fun skins(name: String, vararg ids: SkinId, content: @Composable () -> Unit) {
        ids.forEach { id -> SkinState.set(id); paparazzi.snapshot("${name}_${id.name.lowercase()}", content) }
        SkinState.set(SkinId.NEON)
    }

    @Test fun coverStage() = skins("110_cover", SkinId.NEON, SkinId.POD, SkinId.CHROME) { CoverLayout(state, NoActions, LayoutHooks()) }

    @Test fun podNowPlaying() = skins("111_pod", SkinId.POD, SkinId.NEON, SkinId.CHROME) { PodLayout(state, NoActions, LayoutHooks()) }

    @Test fun podMenuAndVolume() {
        SkinState.set(SkinId.POD)
        paparazzi.snapshot("112_pod_menu") { PodLayout(state, NoActions, LayoutHooks(), startMode = PodMode.MENU) }
        paparazzi.snapshot("113_pod_volume") { PodLayout(state, NoActions, LayoutHooks(), startVolume = 0.62f) }
        paparazzi.snapshot("114_pod_scrub") { PodLayout(state, NoActions, LayoutHooks(), startMode = PodMode.SCRUB) }
    }

    @Test fun prism() {
        paparazzi.snapshot("115_prism") { PrismLayout(state, NoActions, viz(), LayoutHooks(), frozenFrames = 150) }
        paparazzi.snapshot("116_prism_controls") { PrismLayout(state.copy(isPlaying = true), NoActions, viz(), LayoutHooks(), frozenFrames = 90) }
    }

    @Test fun classicInNewSkins() = skins("117_classic", SkinId.POD, SkinId.CHROME) { PlayerLayout(state, eq, dac, viz(), NoActions, {}) }

    @Test fun styleSheet() {
        StyleSheetState.open = true
        paparazzi.snapshot("118_style_sheet") { Box(Modifier.fillMaxSize()) { PlayerLayout(state, eq, dac, viz(), NoActions, {}); StyleSheet() } }
    }

    private fun t(id: Long, title: String, artist: String, codec: String, kbps: Int, year: Int) = TrackEntity(
        id = id, uri = "content://t/$id", title = title, artist = artist, album = "Album", albumArtist = null,
        genre = null, year = year, trackNo = 1, discNo = 1, durationMs = 215_000, bitrateKbps = kbps,
        sampleRate = 44100, bits = null, codec = codec, sizeBytes = 1, contentKey = "k$id", artPath = null,
        source = "mediastore", sourceRoot = null, dateAdded = 0, tagsMissing = false,
    )

    @Test fun libraryInNewSkins() = skins("119_library", SkinId.POD, SkinId.CHROME) {
        val tracks = listOf(t(1, "Cali Pachanguero", "Grupo Niche", "MP3", 192, 1984), t(2, "Midnight Circuit", "Voltage Bloom", "FLAC", 900, 2018), t(3, "Nightcall", "Kavinsky", "MP3", 320, 2010))
        LibraryContent(LibraryUi(count = 121, tracks = tracks, menu = tracks[0]), LibraryCallbacks())
    }
}
