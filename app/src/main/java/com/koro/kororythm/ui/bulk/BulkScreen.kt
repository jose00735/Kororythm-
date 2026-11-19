package com.koro.kororythm.ui.bulk

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koro.kororythm.library.PlaylistSummary
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.playback.PlayerViewModel
import com.koro.kororythm.tags.TagIo
import com.koro.kororythm.ui.common.ArtThumb
import com.koro.kororythm.ui.common.NeonCheck
import com.koro.kororythm.ui.common.NeonScreen
import com.koro.kororythm.ui.common.NeonTextField
import com.koro.kororythm.ui.player.formatTime
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder

open class BulkCallbacks {
    open fun close() {}
    open fun query(q: String) {}
    open fun toggle(id: Long) {}
    open fun selectShown() {}
    open fun clearSelection() {}
    open fun onlySelected(on: Boolean) {}
    open fun field(f: TagIo.Field, v: BulkField) {}
    open fun apply() {}
    open fun chooseList(on: Boolean) {}
    open fun addTo(p: PlaylistSummary) {}
    open fun newList(name: String?) {}
    open fun createList() {}
}

@Composable
fun BulkScreen(vm: BulkViewModel, player: PlayerViewModel, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.consentResult(r.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(ui.consent) { ui.consent?.let { consent.launch(IntentSenderRequest.Builder(it).build()) } }
    val cb = remember(vm, player) {
        object : BulkCallbacks() {
            override fun close() = onClose()
            override fun query(q: String) = vm.query(q)
            override fun toggle(id: Long) = vm.toggle(id)
            override fun selectShown() = vm.selectShown()
            override fun clearSelection() = vm.clearSelection()
            override fun onlySelected(on: Boolean) = vm.onlySelected(on)
            override fun field(f: TagIo.Field, v: BulkField) = vm.field(f, v)
            override fun apply() = vm.apply(player)
            override fun chooseList(on: Boolean) = vm.chooseList(on)
            override fun addTo(p: PlaylistSummary) { vm.addTo(p) }
            override fun newList(name: String?) = vm.newList(name)
            override fun createList() { vm.createList() }
        }
    }
    BulkContent(ui, cb)
}

private val FIELD_LABELS = linkedMapOf(
    TagIo.Field.ARTIST to "ARTIST",
    TagIo.Field.ALBUM to "ALBUM",
    TagIo.Field.ALBUM_ARTIST to "ALBUM ARTIST",
    TagIo.Field.GENRE to "GENRE",
    TagIo.Field.YEAR to "YEAR",
)

@Composable
fun BulkContent(ui: BulkUi, cb: BulkCallbacks) {
    Box(Modifier.fillMaxSize()) {
        NeonScreen(title = "BULK EDIT", onBack = cb::close) {
            NeonTextField(ui.query, cb::query, placeholder = "Filter ${ui.total} songs by title, artist, album, genre…")
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NeonText("${ui.selected.size} selected", Modifier.weight(1f), size = 11.sp, color = if (ui.selected.isEmpty()) Neon.Dim else Neon.Orange, glow = null, weight = FontWeight.Bold)
                NeonButton("ALL SHOWN", cb::selectShown)
                NeonButton("NONE", cb::clearSelection)
                NeonButton(if (ui.onlySelected) "✓ ONLY SEL." else "ONLY SEL.", { cb.onlySelected(!ui.onlySelected) }, active = ui.onlySelected)
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f)
                    .background(Neon.Panel, RoundedCornerShape(8.dp)).neonBorder(Neon.Cyan, 8.dp, 0.4f),
            ) {
                items(ui.shown, key = { it.id }) { t -> TrackLine(t, t.id in ui.selected) { cb.toggle(t.id) } }
            }
            ActionPanel(ui, cb)
        }
        if (ui.choosingList) PlaylistChooser(ui, cb)
    }
}

@Composable
private fun TrackLine(t: TrackEntity, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (on) Neon.Orange.copy(alpha = 0.08f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonCheck(on)
        Spacer(Modifier.width(8.dp))
        ArtThumb(t.artPath, size = 34.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            NeonText(t.title, size = 12.sp, color = if (on) Neon.Ink else Neon.Ink.copy(alpha = 0.85f), glow = null, weight = if (on) FontWeight.Bold else FontWeight.Normal)
            NeonText(
                listOfNotNull(t.artist ?: "No artist", t.album, t.subGenre ?: t.genreMain ?: t.genre, t.year?.toString()).joinToString(" · "),
                size = 9.sp, color = Neon.Dim, glow = null,
            )
        }
        NeonText(formatTime(t.durationMs), size = 10.sp, color = Neon.Dim, glow = null)
    }
}

@Composable
private fun ActionPanel(ui: BulkUi, cb: BulkCallbacks) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .background(Neon.Panel, RoundedCornerShape(8.dp)).neonBorder(Neon.Orange, 8.dp, 0.5f)
            .padding(10.dp),
    ) {
        NeonText("TICK A FIELD TO SET IT ON EVERY SELECTED SONG · EMPTY CLEARS IT", size = 8.sp, color = Neon.Dim, glow = null, spacing = 1.sp)
        FIELD_LABELS.forEach { (f, label) ->
            val v = ui.fields[f] ?: BulkField()
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clickable { cb.field(f, v.copy(on = !v.on)) }.padding(end = 6.dp)) { NeonCheck(v.on) }
                NeonText(label, Modifier.width(86.dp).clickable { cb.field(f, v.copy(on = !v.on)) }, size = 9.sp, color = if (v.on) Neon.Orange else Neon.Dim, glow = null, spacing = 1.sp)
                NeonTextField(
                    v.value, { cb.field(f, BulkField(on = true, value = it)) }, Modifier.weight(1f),
                    placeholder = if (v.on) "(empty = remove)" else "keep as is", numeric = f == TagIo.Field.YEAR,
                )
            }
        }
        ui.progress?.let { (d, total) ->
            val c = Neon.Orange
            val track = Neon.Dim.copy(alpha = 0.25f)
            NeonText("Writing tags… $d / $total", Modifier.padding(top = 8.dp), size = 10.sp, color = Neon.Cyan, glow = null)
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp)) {
                drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                drawRoundRect(c, size = Size(size.width * d / total.coerceAtLeast(1), size.height), cornerRadius = CornerRadius(size.height / 2))
            }
        }
        ui.message?.let { NeonText(it, Modifier.padding(top = 6.dp), size = 10.sp, color = Neon.Cyan, glow = null, maxLines = 3) }
        ui.error?.let { NeonText(it, Modifier.padding(top = 4.dp), size = 10.sp, color = Neon.Orange, glow = null, maxLines = 6) }
        val n = ui.selected.size
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton(
                if (ui.progress != null) "WRITING…" else "APPLY TO $n SONG${if (n == 1) "" else "S"}",
                cb::apply, active = n > 0 && ui.changes.isNotEmpty() && ui.progress == null, accent = Neon.Orange,
            )
            NeonButton("☰ ADD $n TO PLAYLIST", { if (n > 0) cb.chooseList(true) }, active = false)
        }
    }
}

@Composable
private fun PlaylistChooser(ui: BulkUi, cb: BulkCallbacks) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, null) { cb.chooseList(false) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(Neon.Panel, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .neonBorder(Neon.Cyan, 14.dp, 0.8f)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .navigationBarsPadding()
                .padding(14.dp),
        ) {
            NeonText("ADD ${ui.selected.size} SONGS TO…", size = 11.sp, color = Neon.Dim, glow = null, spacing = 2.sp)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                NeonTextField(ui.newListName.orEmpty(), { cb.newList(it) }, Modifier.weight(1f), placeholder = "New playlist name")
                Spacer(Modifier.width(8.dp))
                NeonButton("+ CREATE", cb::createList, accent = Neon.Orange, active = !ui.newListName.isNullOrBlank())
            }
            if (ui.playlists.isEmpty()) NeonText("No playlists yet: type a name above.", size = 11.sp, color = Neon.Dim, glow = null)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(ui.playlists, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { cb.addTo(p) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        NeonText("☰", Modifier.width(28.dp), size = 14.sp, color = Neon.Cyan, glow = null)
                        Column(Modifier.weight(1f)) {
                            NeonText(p.name, size = 13.sp, weight = FontWeight.Bold)
                            NeonText("${p.trackCount} songs · ${formatTime(p.durationMs)}", size = 10.sp, color = Neon.Dim, glow = null)
                        }
                    }
                }
            }
        }
    }
}
