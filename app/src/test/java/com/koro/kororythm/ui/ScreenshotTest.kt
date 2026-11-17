package com.koro.kororythm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.koro.kororythm.R
import com.koro.kororythm.devices.OutputDevice
import com.koro.kororythm.devices.OutputKind
import com.koro.kororythm.dsp.EqPresets
import com.koro.kororythm.dsp.EqSettings
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.playback.TrackInfo
import com.koro.kororythm.ui.player.PlayerLayout
import com.koro.kororythm.ui.skin.GridBackground
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.drawParrot
import com.koro.kororythm.ui.viz.VizState
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.koro.kororythm.ui.anim.PowerSurgeFrame
import com.koro.kororythm.ui.anim.WingsUpFrame
import org.junit.Rule
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sin

/**
 * Renders the real Compose UI to PNGs so the look can be checked without a phone.
 * Run `./gradlew recordPaparazziDebug`; images land in app/src/test/snapshots/images/.
 */
class ScreenshotTest {

    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
        maxPercentDifference = 0.1,
    )

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
        TrackInfo("6", "Cyan Static", "Voltage Bloom", null, 233_000, 1100, 44100, 16, "FLAC"),
    )

    private val state = PlayerUiState(
        connected = true, isPlaying = true, positionMs = 83_000, durationMs = 312_000,
        queue = queue, currentIndex = 1, shuffle = false, repeatMode = 2,
    )

    private val eq = EqSettings(bandsDb = EqPresets.all.getValue("Rock"), boost = true, presetName = "Rock")

    private val dac = OutputDevice(OutputKind.USB_DAC, "FiiO KA3", 384000, 32, bitPerfectCapable = true)
    private val headphones = OutputDevice(OutputKind.WIRED, "Wired headphones")

    private fun fakeViz() = VizState().apply {
        val b = FloatArray(64) { i ->
            val x = i / 64f
            (0.85f * exp(-x * 1.6f) + 0.18f * sin(i * 0.9f) + 0.15f).coerceIn(0.05f, 0.98f)
        }
        bars = b
        peaks = FloatArray(64) { (b[it] + 0.08f).coerceAtMost(1f) }
        scope = FloatArray(128) { (0.6 * sin(it * 0.25) * sin(it * 0.03)).toFloat() }
    }

    @Test fun playerPortrait() {
        paparazzi.snapshot("01_player_portrait") { PlayerLayout(state, eq, headphones, fakeViz(), NoActions, {}) }
    }

    @Test fun playerLandscape() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE))
        paparazzi.snapshot("02_player_landscape") { PlayerLayout(state, eq, dac, fakeViz(), NoActions, {}) }
    }

    @Test fun powerSurgeDac() {
        paparazzi.snapshot("03_power_surge_dac") {
            Box(Modifier.fillMaxSize()) {
                PlayerLayout(state, eq, dac, fakeViz(), NoActions, {})
                PowerSurgeFrame(dac, reverse = false, v = 0.72f)
            }
        }
    }

    @Test fun wingsUpHeadphones() {
        paparazzi.snapshot("04_wings_up_headphones") {
            Box(Modifier.fillMaxSize()) {
                PlayerLayout(state, eq, headphones, fakeViz(), NoActions, {})
                WingsUpFrame(headphones, reverse = false, v = 0.6f)
            }
        }
    }

    @Test fun iconAndMascot() {
        paparazzi.snapshot("05_icon_and_mascot") {
            Box(Modifier.fillMaxSize()) {
                GridBackground(1f)
                Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    // Launcher icon: background + foreground layers, round and squircle masks.
                    Box(Modifier.size(150.dp).clip(CircleShape)) {
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                    }
                    Box(Modifier.size(150.dp).clip(RoundedCornerShape(36.dp))) {
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                    }
                }
                Canvas(Modifier.padding(top = 220.dp).size(360.dp)) {
                    drawParrot(Offset.Zero, size.minDimension, wingSpread = 1f, body = Neon.Cyan, phonesGlow = 1f)
                }
            }
        }
    }
}
