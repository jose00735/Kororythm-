package com.koro.kororythm.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.koro.kororythm.dj.DjSettings
import com.koro.kororythm.dj.LiveFx
import com.koro.kororythm.dj.Transition
import com.koro.kororythm.library.HotCue
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.playback.TrackInfo
import com.koro.kororythm.tags.TagMatch
import com.koro.kororythm.ui.dj.DjCallbacks
import com.koro.kororythm.ui.dj.DjContent
import com.koro.kororythm.ui.dj.DjUi
import com.koro.kororythm.ui.skin.SkinId
import com.koro.kororythm.ui.skin.SkinState
import com.koro.kororythm.ui.tags.TagEditCallbacks
import com.koro.kororythm.ui.tags.TagEditUi
import com.koro.kororythm.ui.tags.TagEditorContent
import com.koro.kororythm.ui.tags.TagForm
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** DJ panel and tag editor, in both skins. */
class Phase3ScreenshotTest {

    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 3200),
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
        maxPercentDifference = 0.1,
    )

    @After fun neon() = SkinState.set(SkinId.NEON)

    private val ps = PlayerUiState(
        true, true, 141_000, 312_000,
        listOf(
            TrackInfo("1", "Midnight Circuit", "Voltage Bloom", null, 312_000, 2304, 96000, 24, "FLAC"),
            TrackInfo("2", "Neon Runway", "Grid Pilots", null, 245_000, 1012, 44100, 16, "FLAC"),
        ),
        0, false, 0,
    )

    private val dj = DjUi(
        dj = DjSettings(Transition.ECHO_OUT, 3000, autoMix = true, tempo = 1.024f, keyLock = true, leveling = true),
        fx = LiveFx(echo = true, filter = -0.45f, repeatBeats = 0.25f),
        bpm = 124f, trackId = 7, cues = listOf(HotCue(7, 0, 32_000), HotCue(7, 2, 95_500)),
        analyzed = 812, total = 1043, dacConnected = true, bitPerfectWanted = false,
        bitPerfectNote = null,
    )

    @Test fun djNeon() = paparazzi.snapshot("50_dj_neon") { DjContent(dj, ps, DjCallbacks()) }

    @Test fun djHiFi() {
        SkinState.set(SkinId.HIFI)
        paparazzi.snapshot("51_dj_hifi") { DjContent(dj, ps, DjCallbacks()) }
    }

    private val tags = TagEditUi(
        uri = "content://x", loading = false, fileBase = "track07_final", originalBase = "track07_final", ext = ".flac",
        form = TagForm(title = "Neon Runway", artist = "Grid Pilots", album = "Runways", albumArtist = "Grid Pilots", genre = "Synthwave", year = "2018", trackNo = "3", trackTotal = "12", discNo = "1"),
        query = "Grid Pilots Neon Runway", searched = true,
        results = listOf(
            TagMatch("MusicBrainz", "Neon Runway", "Grid Pilots", "Runways", "Grid Pilots", 2018, 3, 12, 1, null, null, 100),
            TagMatch("iTunes", "Neon Runway (Extended Mix)", "Grid Pilots", "Runways (Deluxe)", null, 2019, 9, 14, 1, "Electronic", null, 90),
        ),
        message = "Filled from MusicBrainz, with its cover.",
    )

    @Test fun tagEditorNeon() = paparazzi.snapshot("60_tags_neon") { TagEditorContent(tags, TagEditCallbacks()) }

    @Test fun tagEditorHiFi() {
        SkinState.set(SkinId.HIFI)
        paparazzi.snapshot("61_tags_hifi") { TagEditorContent(tags, TagEditCallbacks()) }
    }
}
