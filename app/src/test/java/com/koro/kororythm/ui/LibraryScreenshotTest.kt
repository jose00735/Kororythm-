package com.koro.kororythm.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.koro.kororythm.library.AlbumSummary
import com.koro.kororythm.library.ArtistSummary
import com.koro.kororythm.library.MusicBrainz
import com.koro.kororythm.library.ScanProgress
import com.koro.kororythm.library.ScanResult
import com.koro.kororythm.library.SourceEntity
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.library.UserAlbumSummary
import com.koro.kororythm.ui.library.LibDetail
import com.koro.kororythm.ui.library.LibTab
import com.koro.kororythm.ui.library.LibraryCallbacks
import com.koro.kororythm.ui.library.LibraryContent
import com.koro.kororythm.ui.library.LibraryUi
import com.koro.kororythm.ui.wizards.AlbumCallbacks
import com.koro.kororythm.ui.wizards.AlbumDraft
import com.koro.kororythm.ui.wizards.AlbumStep
import com.koro.kororythm.ui.wizards.AlbumWizardContent
import com.koro.kororythm.ui.wizards.AlbumWizardUi
import com.koro.kororythm.ui.wizards.ImportCallbacks
import com.koro.kororythm.ui.wizards.ImportDonePreview
import com.koro.kororythm.ui.wizards.ImportStep
import com.koro.kororythm.ui.wizards.ImportUi
import com.koro.kororythm.ui.wizards.ImportWizardContent
import com.koro.kororythm.ui.wizards.SuggestState
import org.junit.Rule
import org.junit.Test

/** Library and wizard screens rendered with sample data. Record with ./gradlew recordPaparazziDebug. */
class LibraryScreenshotTest {

    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
        maxPercentDifference = 0.1,
    )

    private fun track(id: Long, title: String, artist: String?, album: String?, ms: Long, codec: String = "FLAC", bits: Int? = 16, missing: Boolean = false) =
        TrackEntity(
            id = id, uri = "content://t/$id", title = title, artist = artist, album = album, albumArtist = null,
            genre = "Synthwave", year = 2018, trackNo = id.toInt(), discNo = 1, durationMs = ms, bitrateKbps = 1000,
            sampleRate = 44100, bits = bits, codec = codec, sizeBytes = 1, contentKey = "k$id", artPath = null,
            source = "folder", sourceRoot = null, dateAdded = 0, tagsMissing = missing,
        )

    private val tracks = listOf(
        track(1, "Analog Horizon", "Static Coast", "Low Tide FM", 401_000, "MP3", null),
        track(2, "Cyan Static", "Voltage Bloom", "Bloomfield", 233_000),
        track(3, "Lightcycle Lullaby", "Grid Pilots", "Runways", 276_000),
        track(4, "Midnight Circuit", "Voltage Bloom", "Bloomfield", 312_000, bits = 24),
        track(5, "Neon Runway", "Grid Pilots", "Runways", 245_000),
        track(6, "Parrot in Orbit", "Koro Sound System", "Wings", 198_000, "MP3", null),
        track(7, "track07_final", null, null, 188_000, "MP3", null, missing = true),
        track(8, "Velvet Voltage", "Static Coast", "Low Tide FM", 264_000, "MP3", null),
    )

    private val albums = listOf(
        AlbumSummary("Bloomfield", "Voltage Bloom", 9, 2_400_000, null, 2020),
        AlbumSummary("Low Tide FM", "Static Coast", 11, 2_900_000, null, 2017),
        AlbumSummary("Runways", "Grid Pilots", 10, 2_650_000, null, 2018),
        AlbumSummary("Wings", "Koro Sound System", 7, 1_800_000, null, 2026),
    )

    private val base = LibraryUi(
        count = 1043, tracks = tracks, albums = albums,
        artists = listOf(ArtistSummary("Grid Pilots", 2, 21), ArtistSummary("Static Coast", 1, 11), ArtistSummary("Voltage Bloom", 2, 18)),
        mine = listOf(UserAlbumSummary(1, "Night Drive Mix", "Various artists", 2026, "Synthwave", null, null, 14, 3_300_000)),
    )

    @Test fun libraryTracks() = paparazzi.snapshot("10_library_tracks") {
        LibraryContent(base, LibraryCallbacks(), nowPlayingUri = "content://t/4")
    }

    @Test fun libraryAlbums() = paparazzi.snapshot("11_library_albums") {
        LibraryContent(base.copy(tab = LibTab.ALBUMS), LibraryCallbacks())
    }

    @Test fun libraryAlbumDetail() = paparazzi.snapshot("12_library_album_detail") {
        val d = LibDetail("Runways", "Grid Pilots", null, null, tracks.filter { it.album == "Runways" } + tracks.take(3))
        LibraryContent(base.copy(tab = LibTab.ALBUMS, detail = d), LibraryCallbacks(), nowPlayingUri = "content://t/5")
    }

    @Test fun libraryEmpty() = paparazzi.snapshot("13_library_empty") {
        LibraryContent(LibraryUi(), LibraryCallbacks())
    }

    private val sources = listOf(
        SourceEntity(SourceEntity.MEDIASTORE_KEY, "Phone music library", 0),
        SourceEntity("content://tree/primary%3AMusic%2FFLAC", "Music/FLAC", 1, lastScanAt = 5),
        SourceEntity("content://tree/1234%3AAlbums", "SD card: Albums", 2),
    )

    @Test fun importSources() = paparazzi.snapshot("20_import_sources") {
        ImportWizardContent(ImportUi(step = ImportStep.SOURCES, sources = sources), ImportCallbacks())
    }

    @Test fun importScanning() = paparazzi.snapshot("21_import_scanning") {
        ImportWizardContent(
            ImportUi(step = ImportStep.SCANNING, sources = sources, progress = ScanProgress(1043, 612, "07 - Voltage Bloom - Midnight Circuit.flac")),
            ImportCallbacks(),
        )
    }

    @Test fun importReview() = paparazzi.snapshot("22_import_review") {
        val a = track(7, "track07_final", null, null, 188_000, missing = true)
        val b = track(9, "Neon Runway", "Grid Pilots", null, 245_000, missing = true)
        ImportWizardContent(
            ImportUi(
                step = ImportStep.REVIEW, sources = sources, review = listOf(b, a),
                suggestions = mapOf(
                    9L to SuggestState(
                        items = listOf(
                            MusicBrainz.Suggestion("Neon Runway", "Grid Pilots", "Runways", 2018, 100),
                            MusicBrainz.Suggestion("Neon Runway (Extended)", "Grid Pilots", "Runways (Deluxe)", 2019, 88),
                        ),
                    ),
                    7L to SuggestState(error = "No matches found"),
                ),
            ),
            ImportCallbacks(),
        )
    }

    @Test fun importDone() = paparazzi.snapshot("23_import_done") {
        ImportDonePreview(ImportUi(step = ImportStep.DONE, result = ScanResult(1012, 4, 0, 23, 2, 2), libraryCount = 1043))
    }

    private val draft = AlbumDraft(
        selected = tracks.filter { it.artist == "Grid Pilots" || it.artist == "Voltage Bloom" },
        title = "Night Drive Mix", artist = "Various artists", year = "2026", genre = "Synthwave",
    )

    @Test fun albumPick() = paparazzi.snapshot("30_album_pick") {
        AlbumWizardContent(AlbumWizardUi(draft.copy(step = AlbumStep.PICK), tracks), AlbumCallbacks())
    }

    @Test fun albumDetails() = paparazzi.snapshot("31_album_details") {
        AlbumWizardContent(AlbumWizardUi(draft.copy(step = AlbumStep.DETAILS), tracks), AlbumCallbacks())
    }

    @Test fun albumOrder() = paparazzi.snapshot("32_album_order") {
        AlbumWizardContent(AlbumWizardUi(draft.copy(step = AlbumStep.ORDER), tracks), AlbumCallbacks())
    }

    @Test fun albumSaved() = paparazzi.snapshot("33_album_saved") {
        AlbumWizardContent(AlbumWizardUi(draft.copy(step = AlbumStep.SAVED, savedId = 1), tracks), AlbumCallbacks())
    }
}
