package com.koro.kororythm.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.runtime.Composable
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.ui.library.GenreGroup
import com.koro.kororythm.ui.library.GenreScan
import com.koro.kororythm.ui.library.LibTab
import com.koro.kororythm.ui.library.LibraryCallbacks
import com.koro.kororythm.ui.library.LibraryContent
import com.koro.kororythm.ui.library.LibraryUi
import com.koro.kororythm.ui.skin.SkinId
import com.koro.kororythm.ui.skin.SkinState
import com.koro.kororythm.ui.tags.TagEditCallbacks
import com.koro.kororythm.ui.tags.TagEditUi
import com.koro.kororythm.ui.tags.TagEditorContent
import com.koro.kororythm.ui.tags.TagForm
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Genres tab, NEEDS TAGS list and the tag editor in fix mode, both skins. */
class V12ScreenshotTest {

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

    private fun t(id: Long, title: String, artist: String?, album: String?, year: Int? = null, genre: String? = null, art: String? = null, fromName: Boolean = false) = TrackEntity(
        id = id, uri = "content://t/$id", title = title, artist = artist, album = album, albumArtist = null,
        genre = genre, year = year, trackNo = null, discNo = null, durationMs = 200_000, bitrateKbps = 192,
        sampleRate = 44100, bits = null, codec = "MP3", sizeBytes = 1, contentKey = "k$id", artPath = art,
        source = "mediastore", sourceRoot = null, dateAdded = 0, tagsMissing = fromName,
    )

    private val genres = listOf(
        GenreGroup("Electronic", 412, listOf("Synthwave" to 120, "House" to 88, "Drum and Bass" to 41, "Darksynth" to 33, null to 130)),
        GenreGroup("Latin", 236, listOf("Vallenato" to 74, "Reggaeton" to 61, "Salsa" to 39, "Champeta" to 22, "Cumbia" to 18, null to 22)),
        GenreGroup("Rock", 158, listOf("Indie Rock" to 44, "Post-Punk" to 21, "Alternative Rock" to 19, null to 74)),
        GenreGroup("Hip Hop", 97, listOf("Boom Bap" to 23, "Trap" to 19, null to 55)),
        GenreGroup(null, 61, listOf(null to 61)),
    )

    @Test fun genresTab() = both("90_genres") {
        LibraryContent(
            LibraryUi(count = 1043, tab = LibTab.GENRES, genres = genres, genreScan = GenreScan(running = true, done = 214, found = 190, left = 586, current = "Silvestre Dangond – Materialista")),
            LibraryCallbacks(),
        )
    }

    private val needs = listOf(
        t(1, "track07_final", null, null, fromName = true),
        t(2, "AUD-20231104-WA0012", null, null, fromName = true),
        t(3, "La Gota Fría", "Carlos Vives", null),
        t(4, "Nightcall", "Kavinsky", "OutRun", genre = null),
        t(5, "Materialista", "Silvestre Dangond", "Materialista", year = 2021),
    )

    @Test fun needsTagsTab() = both("91_needs_tags") {
        LibraryContent(LibraryUi(count = 1043, tab = LibTab.NEEDS, needs = needs, genreScan = GenreScan(left = 586)), LibraryCallbacks())
    }

    @Test fun needsBannerOnTracks() = paparazzi.snapshot("92_needs_banner") {
        LibraryContent(LibraryUi(count = 1043, tab = LibTab.TRACKS, tracks = needs, needs = needs), LibraryCallbacks())
    }

    @Test fun tagEditorFixMode() = paparazzi.snapshot("93_tag_editor_fix_mode") {
        val ui = TagEditUi(
            uri = "content://x", loading = false, fileBase = "track07_final", originalBase = "track07_final", ext = ".mp3",
            form = TagForm(title = "track07_final", genre = "Synthwave"), query = "track07_final", saved = true, message = "Saved into the file.",
        )
        TagEditorContent(ui, TagEditCallbacks(), next = {}, progress = "3/17")
    }
}
