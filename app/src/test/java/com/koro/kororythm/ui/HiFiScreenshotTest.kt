package com.koro.kororythm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.koro.kororythm.devices.OutputDevice
import com.koro.kororythm.devices.OutputKind
import com.koro.kororythm.dsp.EqPresets
import com.koro.kororythm.dsp.EqSettings
import com.koro.kororythm.library.AlbumSummary
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.playback.TrackInfo
import com.koro.kororythm.ui.anim.PowerSurgeFrame
import com.koro.kororythm.ui.library.LibTab
import com.koro.kororythm.ui.library.LibraryCallbacks
import com.koro.kororythm.ui.library.LibraryContent
import com.koro.kororythm.ui.library.LibraryUi
import com.koro.kororythm.ui.player.PlayerLayout
import com.koro.kororythm.ui.skin.SkinId
import com.koro.kororythm.ui.skin.SkinState
import com.koro.kororythm.ui.viz.VizState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sin

/** The skeuomorphic Hi-Fi skin. */
class HiFiScreenshotTest {

    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
        maxPercentDifference = 0.1,
    )

    @Before fun hifi() = SkinState.set(SkinId.HIFI)
    @After fun neon() = SkinState.set(SkinId.NEON)

    private object NoActions : PlayerActions {
        override fun playPause() {}; override fun play() {}; override fun pause() {}; override fun stop() {}
        override fun next() {}; override fun previous() {}; override fun seekTo(ms: Long) {}; override fun playAt(index: Int) {}
        override fun toggleShuffle() {}; override fun cycleRepeat() {}; override fun removeAt(index: Int) {}; override fun clear() {}
        override fun setBand(i: Int, db: Float) {}; override fun setPreamp(db: Float) {}; override fun toggleEq() {}
        override fun toggleBoost() {}; override fun applyPreset(name: String) {}; override fun resetEq() {}
    }

    private val queue = listOf(
        TrackInfo("1", "Neon Runway", "Grid Pilots", null, 245_000, 1012, 44100, 16, "FLAC"),
        TrackInfo("2", "Midnight Circuit", "Voltage Bloom", null, 312_000, 2304, 96000, 24, "FLAC"),
        TrackInfo("3", "Parrot in Orbit", "Koro Sound System", null, 198_000, 320, 44100, null, "MP3"),
        TrackInfo("4", "Lightcycle Lullaby", "Grid Pilots", null, 276_000, 980, 44100, 16, "FLAC"),
        TrackInfo("5", "Analog Horizon", "Static Coast", null, 401_000, 256, 48000, null, "MP3"),
    )
    private val state = PlayerUiState(true, true, 83_000, 312_000, queue, 1, false, 2)
    private val eq = EqSettings(bandsDb = EqPresets.all.getValue("Rock"), boost = true, presetName = "Rock")
    private val dac = OutputDevice(OutputKind.USB_DAC, "FiiO KA3", 384000, 32, bitPerfectCapable = true)

    private fun viz() = VizState().apply {
        val b = FloatArray(64) { i -> (0.85f * exp(-i / 64f * 1.6f) + 0.18f * sin(i * 0.9f) + 0.15f).coerceIn(0.05f, 0.98f) }
        bars = b
        peaks = FloatArray(64) { (b[it] + 0.08f).coerceAtMost(1f) }
    }

    @Test fun playerHiFi() = paparazzi.snapshot("40_hifi_player") {
        PlayerLayout(state, eq, dac, viz(), NoActions, {})
    }

    @Test fun powerSurgeHiFi() = paparazzi.snapshot("41_hifi_power_surge") {
        Box(Modifier.fillMaxSize()) {
            PlayerLayout(state, eq, dac, viz(), NoActions, {})
            PowerSurgeFrame(dac, reverse = false, v = 0.72f)
        }
    }

    @Test fun libraryHiFi() = paparazzi.snapshot("42_hifi_library") {
        fun t(id: Long, title: String, artist: String, album: String, ms: Long) = TrackEntity(
            id = id, uri = "u$id", title = title, artist = artist, album = album, albumArtist = null, genre = null,
            year = 2018, trackNo = null, discNo = null, durationMs = ms, bitrateKbps = null, sampleRate = 44100, bits = 16,
            codec = "FLAC", sizeBytes = 1, contentKey = "k$id", artPath = null, source = "folder", sourceRoot = null,
            dateAdded = 0, tagsMissing = false,
        )
        LibraryContent(
            LibraryUi(
                tab = LibTab.TRACKS, count = 1043,
                tracks = listOf(
                    t(1, "Analog Horizon", "Static Coast", "Low Tide FM", 401_000),
                    t(2, "Cyan Static", "Voltage Bloom", "Bloomfield", 233_000),
                    t(3, "Midnight Circuit", "Voltage Bloom", "Bloomfield", 312_000),
                    t(4, "Neon Runway", "Grid Pilots", "Runways", 245_000),
                ),
                albums = listOf(AlbumSummary("Runways", "Grid Pilots", 10, 2_650_000, null, 2018)),
            ),
            LibraryCallbacks(), nowPlayingUri = "u3",
        )
    }
}
