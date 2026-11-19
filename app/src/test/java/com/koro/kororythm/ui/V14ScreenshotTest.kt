package com.koro.kororythm.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.runtime.Composable
import com.koro.kororythm.library.PlaylistSummary
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.tags.TagIo
import com.koro.kororythm.ui.bulk.BulkCallbacks
import com.koro.kororythm.ui.bulk.BulkContent
import com.koro.kororythm.ui.bulk.BulkField
import com.koro.kororythm.ui.bulk.BulkUi
import com.koro.kororythm.ui.skin.SkinId
import com.koro.kororythm.ui.skin.SkinState
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** The hidden bulk-edit panel. */
class V14ScreenshotTest {

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

    private fun t(id: Long, title: String, artist: String?, album: String?, genre: String?) = TrackEntity(
        id = id, uri = "content://t/$id", title = title, artist = artist, album = album, albumArtist = null,
        genre = genre, year = 1990, trackNo = id.toInt(), discNo = 1, durationMs = 280_000, bitrateKbps = 320,
        sampleRate = 44100, bits = null, codec = "MP3", sizeBytes = 1, contentKey = "k$id", artPath = null,
        source = "mediastore", sourceRoot = null, dateAdded = 0, tagsMissing = false,
    )

    private val shown = listOf(
        t(1, "Cali Pachanguero", "Grupo Niche", "No Hay Quinto Malo", "Salsa"),
        t(2, "Una Aventura", "Grupo Niche", null, null),
        t(3, "Gotas de Lluvia", "grupo niche", "Sutil y Contundente", "Tropical"),
        t(4, "Sin Sentimiento", "G. Niche", null, "Salsa"),
        t(5, "Busca Por Dentro", "Grupo Niche", "Tapando el Hueco", null),
        t(6, "Hagamos Lo Que Diga el Corazón", "Grupo Niche", null, null),
    )
    private val ui = BulkUi(
        query = "niche", shown = shown, total = 1043, selected = setOf(1, 2, 3, 4, 6),
        fields = TagIo.Field.entries.associateWith { BulkField() } +
            (TagIo.Field.ARTIST to BulkField(true, "Grupo Niche")) + (TagIo.Field.GENRE to BulkField(true, "Salsa")),
        message = "Changed 5 songs.",
    )

    @Test fun bulkPanel() = both("100_bulk_edit") { BulkContent(ui, BulkCallbacks()) }

    @Test fun bulkAddToPlaylist() = paparazzi.snapshot("101_bulk_playlist") {
        BulkContent(
            ui.copy(choosingList = true, message = null, playlists = listOf(PlaylistSummary(1, "Salsa caleña", 31, 8_400_000, null), PlaylistSummary(2, "Late-night drive", 14, 3_300_000, null))),
            BulkCallbacks(),
        )
    }

    @Test fun bulkWriting() = paparazzi.snapshot("102_bulk_writing") { BulkContent(ui.copy(progress = 3 to 5, message = null), BulkCallbacks()) }
}
