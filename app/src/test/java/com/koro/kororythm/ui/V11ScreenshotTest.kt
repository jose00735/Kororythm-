package com.koro.kororythm.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.koro.kororythm.downloads.AudioStream
import com.koro.kororythm.downloads.DlFormat
import com.koro.kororythm.downloads.DlInfo
import com.koro.kororythm.library.AlbumSummary
import com.koro.kororythm.library.PlaylistSummary
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.lyrics.Lrc
import com.koro.kororythm.tags.Quality
import com.koro.kororythm.ui.downloads.DlCallbacks
import com.koro.kororythm.ui.downloads.DlJob
import com.koro.kororythm.ui.downloads.DlUi
import com.koro.kororythm.ui.downloads.DownloadsContent
import com.koro.kororythm.ui.downloads.JobState
import com.koro.kororythm.ui.library.LibDetail
import com.koro.kororythm.ui.library.LibTab
import com.koro.kororythm.ui.library.LibraryCallbacks
import com.koro.kororythm.ui.library.LibraryContent
import com.koro.kororythm.ui.library.LibraryUi
import com.koro.kororythm.ui.library.NamePrompt
import com.koro.kororythm.ui.lyrics.LyricsCallbacks
import com.koro.kororythm.ui.lyrics.LyricsContent
import com.koro.kororythm.ui.lyrics.LyricsUi
import com.koro.kororythm.ui.skin.GridBackground
import com.koro.kororythm.ui.skin.SkinId
import com.koro.kororythm.ui.skin.SkinState
import com.koro.kororythm.ui.upgrade.PowerUp
import com.koro.kororythm.ui.upgrade.UpgradeOverlay
import com.koro.kororythm.ui.upgrade.UpgradeState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** v1.1 screens: downloads, playlists, cover flow, track menu, upgrade quality, lyrics. */
class V11ScreenshotTest {

    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
        maxPercentDifference = 0.1,
    )

    @After fun neon() = SkinState.set(SkinId.NEON)

    private fun both(name: String, content: @Composable () -> Unit) {
        paparazzi.snapshot("${name}_neon", content)
        SkinState.set(SkinId.HIFI)
        paparazzi.snapshot("${name}_hifi", content)
        SkinState.set(SkinId.NEON)
    }

    private fun track(id: Long, title: String, artist: String, album: String, ms: Long, codec: String, kbps: Int, bits: Int? = null) = TrackEntity(
        id = id, uri = "content://t/$id", title = title, artist = artist, album = album, albumArtist = null,
        genre = "Synthwave", year = 2018, trackNo = id.toInt(), discNo = 1, durationMs = ms, bitrateKbps = kbps,
        sampleRate = 44100, bits = bits, codec = codec, sizeBytes = 1, contentKey = "k$id", artPath = null,
        source = "mediastore", sourceRoot = null, dateAdded = 0, tagsMissing = false,
    )

    private val tracks = listOf(
        track(1, "Analog Horizon", "Static Coast", "Low Tide FM", 401_000, "MP3", 192),
        track(2, "Cyan Static", "Voltage Bloom", "Bloomfield", 233_000, "FLAC", 900, 16),
        track(3, "Lightcycle Lullaby", "Grid Pilots", "Runways", 276_000, "MP3", 128),
        track(4, "Midnight Circuit", "Voltage Bloom", "Bloomfield", 312_000, "FLAC", 2304, 24),
        track(5, "Neon Runway", "Grid Pilots", "Runways", 245_000, "MP3", 320),
    )
    private val lists = listOf(
        PlaylistSummary(1, "Late-night drive", 14, 3_312_000, null),
        PlaylistSummary(2, "Gym · 128 BPM", 22, 5_160_000, null),
        PlaylistSummary(3, "Sunday vinyl rips", 9, 2_490_000, null),
    )
    private val base = LibraryUi(count = 1043, tracks = tracks, playlists = lists)

    // ---- downloads ----
    private val info = DlInfo(
        "https://youtu.be/x", "Grid Pilots - Neon Runway (Official Video)", "Grid Pilots", "Neon Runway", "Grid Pilots",
        245, null, "Youtube", 2018, AudioStream("251", "opus", 135, 48000, "webm"),
    )
    private val jobs = listOf(
        DlJob(UUID(0, 1), "Lightcycle Lullaby", "", JobState.RUNNING, "Downloading", 0.62f),
        DlJob(UUID(0, 2), "Analog Horizon", "", JobState.DONE, "Saved", 1f, trackId = 1, savedAs = "Music/Kororythm/Static Coast - Analog Horizon.mp3"),
        DlJob(UUID(0, 3), "Private video", "", JobState.FAILED, "Failed", 0f, error = "Private video. Sign in if you've been granted access"),
    )

    @Test fun downloads() = both("70_downloads") {
        DownloadsContent(
            DlUi(link = "https://youtu.be/x", info = info, title = "Neon Runway", artist = "Grid Pilots", format = DlFormat.MP3_V0, jobs = jobs, engineVersion = "2025.11.12", updateMessage = "Already up to date (2025.11.12)"),
            DlCallbacks(),
        )
    }

    @Test fun downloadsEmpty() = paparazzi.snapshot("71_downloads_fetching") {
        DownloadsContent(DlUi(link = "https://youtu.be/x", fetching = true, firstRun = true), DlCallbacks())
    }

    // ---- library ----
    @Test fun tracksWithShinyUpgrade() = both("72_tracks_upgrade_chips") { LibraryContent(base, LibraryCallbacks(), "content://t/4") }

    @Test fun trackMenu() = both("73_track_menu") { LibraryContent(base.copy(menu = tracks[0]), LibraryCallbacks()) }

    @Test fun playlistsTab() = paparazzi.snapshot("74_playlists") { LibraryContent(base.copy(tab = LibTab.PLAYLISTS), LibraryCallbacks()) }

    @Test fun choosePlaylist() = paparazzi.snapshot("75_add_to_playlist") { LibraryContent(base.copy(addTo = listOf(1L)), LibraryCallbacks()) }

    @Test fun playlistDetail() = both("76_playlist_detail") {
        LibraryContent(base.copy(detail = LibDetail("Late-night drive", "Playlist", null, null, tracks, playlistId = 1)), LibraryCallbacks(), "content://t/2")
    }

    @Test fun namePrompt() = paparazzi.snapshot("77_name_prompt") {
        LibraryContent(base.copy(tab = LibTab.PLAYLISTS, prompt = NamePrompt("SAVE QUEUE AS PLAYLIST", "Friday mix", newWith = listOf(1, 2, 3))), LibraryCallbacks())
    }

    @Test fun coverFlow() = both("78_cover_flow") {
        val albums = listOf(
            AlbumSummary("Bloomfield", "Voltage Bloom", 11, 2_400_000, null, 2018), AlbumSummary("Low Tide FM", "Static Coast", 9, 2_400_000, null, 2018),
            AlbumSummary("Runways", "Grid Pilots", 12, 2_400_000, null, 2018), AlbumSummary("Night Shift", "Arcade Ghosts", 8, 2_400_000, null, 2018),
            AlbumSummary("Sunset Protocol", "Chrome Lagoon", 10, 2_400_000, null, 2018), AlbumSummary("Tape Memory", "Modem Hearts", 7, 2_400_000, null, 2018),
        )
        LibraryContent(base.copy(tab = LibTab.ALBUMS, albums = albums, coverFlow = true), LibraryCallbacks())
    }

    @Test fun coverFlowMiddle() = both("78b_cover_flow_middle") {
        val cards = listOf("Bloomfield", "Low Tide FM", "Runways", "Night Shift", "Sunset Protocol", "Tape Memory")
            .map { com.koro.kororythm.ui.library.AlbumCard(it, "Various", "10 tracks", null, null) }
        com.koro.kororythm.ui.common.NeonScreen("LIBRARY", {}) { com.koro.kororythm.ui.library.CoverFlow(cards, {}, {}, initial = 2) }
    }

    // ---- upgrade ----
    private val mp3 = Quality.Spec("MP3", 192, 44100, null)
    private val flac = Quality.Spec("FLAC", 920, 44100, 16)

    @Test fun upgradeCompare() = both("79_upgrade_compare") {
        Box(Modifier.fillMaxSize()) {
            LibraryContent(base, LibraryCallbacks())
            UpgradeOverlay(UpgradeState.Compare(tracks[0], mp3, flac, Quality.compare(mp3, flac), android.net.Uri.EMPTY), {}, {})
        }
    }

    @Test fun upgradeNotBetter() = paparazzi.snapshot("80_upgrade_not_better") {
        val v = Quality.Spec("MP3", 200, 44100, null)
        Box(Modifier.fillMaxSize()) {
            LibraryContent(base, LibraryCallbacks())
            UpgradeOverlay(UpgradeState.Compare(tracks[0], mp3, v, Quality.compare(mp3, v), android.net.Uri.EMPTY), {}, {})
        }
    }

    @Test fun powerUp() = both("81_power_up") {
        Box(Modifier.fillMaxSize()) {
            GridBackground(1f)
            PowerUp(UpgradeState.Done("Analog Horizon", mp3.label, flac.label, flac.effectiveKbps / mp3.effectiveKbps, null), {}, frozen = 0.72f)
        }
    }

    // ---- lyrics ----
    private val lrc = """
        [00:12.00] Headlights on the overpass
        [00:16.40] Painting circuits on the glass
        [00:21.10] Every signal turning green
        [00:25.80] We're the ghosts inside the machine
        [00:30.20] Hold the line, hold the line
        [00:34.60] Neon runway, you and I
        [00:39.00] Static rain across the sky
        [00:43.40] Never landing, never dry
        [00:47.90]
        [00:52.00] Count the lights until the dawn
    """.trimIndent()

    @Test fun lyricsSynced() = both("82_lyrics_synced") {
        LyricsContent(
            LyricsUi(uri = "content://t/5", title = "Neon Runway", artist = "Grid Pilots", lines = Lrc.parse(lrc), plain = Lrc.toPlain(lrc), source = "LRCLIB", searched = true, canSave = true, message = "Synced lyrics found."),
            31_500, LyricsCallbacks(),
        )
    }

    @Test fun lyricsEmpty() = paparazzi.snapshot("83_lyrics_none") {
        LyricsContent(LyricsUi(uri = "content://t/1", title = "Analog Horizon", artist = "Static Coast", searched = true, error = "No lyrics found on LRCLIB for this song."), -1, LyricsCallbacks())
    }
}
