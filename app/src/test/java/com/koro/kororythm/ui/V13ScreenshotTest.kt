package com.koro.kororythm.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.runtime.Composable
import com.koro.kororythm.library.Decades
import com.koro.kororythm.library.LibraryCheck
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.ui.downloads.DlCallbacks
import com.koro.kororythm.ui.downloads.DlJob
import com.koro.kororythm.ui.downloads.DlUi
import com.koro.kororythm.ui.downloads.DownloadsContent
import com.koro.kororythm.ui.downloads.JobState
import com.koro.kororythm.ui.library.GenreGroup
import com.koro.kororythm.ui.library.LibTab
import com.koro.kororythm.ui.library.LibraryCallbacks
import com.koro.kororythm.ui.library.LibraryContent
import com.koro.kororythm.ui.library.LibraryUi
import com.koro.kororythm.ui.skin.SkinId
import com.koro.kororythm.ui.skin.SkinState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Decade filter, delete, refresh results, replace-a-similar-song after downloading. */
class V13ScreenshotTest {

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

    private fun t(id: Long, title: String, artist: String, year: Int?, codec: String = "MP3", kbps: Int = 192, main: String? = "Electronic", sub: String? = null) = TrackEntity(
        id = id, uri = "content://t/$id", title = title, artist = artist, album = "Album $id", albumArtist = null,
        genre = null, year = year, trackNo = 1, discNo = 1, durationMs = 215_000, bitrateKbps = kbps,
        sampleRate = 44100, bits = null, codec = codec, sizeBytes = 1, contentKey = "k$id", artPath = null,
        source = "mediastore", sourceRoot = null, dateAdded = 0, tagsMissing = false, genreMain = main, subGenre = sub,
    )

    private val tracks = listOf(
        t(1, "Blue (Da Ba Dee)", "Eiffel 65", 1998, sub = "Eurodance"),
        t(2, "Children", "Robert Miles", 1995, sub = "Dream Trance"),
        t(3, "Sandstorm", "Darude", 1999, sub = "Trance"),
        t(4, "Stereo Love", "Edward Maya", 2009, sub = "Dance"),
        t(5, "Satisfaction", "Benny Benassi", 2002, sub = "Electro House"),
    )
    private val decadeCounts = listOf(1970 to 31, 1980 to 88, 1990 to 214, 2000 to 301, 2010 to 276, 2020 to 72, Decades.UNKNOWN to 61)
    private val base = LibraryUi(count = 1043, tracks = tracks, decades = setOf(1990, 2000), decadeCounts = decadeCounts)

    @Test fun decadeFilterTracks() = both("94_decades_tracks") { LibraryContent(base, LibraryCallbacks()) }

    @Test fun decadeFilterGenres() = paparazzi.snapshot("95_decades_genres") {
        LibraryContent(
            base.copy(
                tab = LibTab.GENRES,
                genres = listOf(
                    GenreGroup("Electronic", 245, listOf("Trance" to 61, "Eurodance" to 48, "Electro House" to 33, "Dream Trance" to 12, null to 91)),
                    GenreGroup("Latin", 98, listOf("Vallenato" to 40, "Reggaeton" to 33, null to 25)),
                ),
            ),
            LibraryCallbacks(),
        )
    }

    @Test fun deleteDialog() = paparazzi.snapshot("96_delete_dialog") { LibraryContent(base.copy(confirmDelete = tracks[3]), LibraryCallbacks()) }

    @Test fun missingDialog() = both("97_missing_files") {
        LibraryContent(
            base.copy(missing = listOf(LibraryCheck.Missing(tracks[0], "file not found"), LibraryCheck.Missing(tracks[2], "no permission any more"))),
            LibraryCallbacks(),
        )
    }

    @Test fun suspiciousMissing() = paparazzi.snapshot("97b_missing_suspicious") {
        LibraryContent(base.copy(missing = tracks.map { LibraryCheck.Missing(it, "no permission any more") }, checkedTotal = 10), LibraryCallbacks())
    }

    @Test fun allPresent() = paparazzi.snapshot("98_all_present") { LibraryContent(base.copy(missing = emptyList()), LibraryCallbacks()) }

    @Test fun replaceSimilar() = both("99_replace_similar") {
        val job = DlJob(
            UUID(0, 9), "This Is My Life", "", JobState.DONE, "Saved", 1f, trackId = 77,
            savedAs = "Music/Kororythm/Edward Maya - This Is My Life.mp3",
            similar = listOf(t(12, "This Is My Life (Radio Edit)", "Edward Maya", 2009, kbps = 128)),
        )
        DownloadsContent(DlUi(jobs = listOf(job), engineVersion = "2026.11.10"), DlCallbacks(), frozenNow = 0)
    }
}
