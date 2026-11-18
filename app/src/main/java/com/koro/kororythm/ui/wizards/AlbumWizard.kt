package com.koro.kororythm.ui.wizards

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.ui.common.ArtThumb
import com.koro.kororythm.ui.common.NeonCheck
import com.koro.kororythm.ui.common.NeonTextField
import com.koro.kororythm.ui.common.WizardFrame
import com.koro.kororythm.ui.player.formatTime
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder

open class AlbumCallbacks {
    open fun query(q: String) {}
    open fun toggle(t: TrackEntity) {}
    open fun title(v: String) {}
    open fun artist(v: String) {}
    open fun year(v: String) {}
    open fun genre(v: String) {}
    open fun pickCover() {}
    open fun clearCover() {}
    open fun move(from: Int, to: Int) {}
    open fun remove(i: Int) {}
    open fun sortByTrackNumber() {}
    open fun next() {}
    open fun back() {}
    open fun save() {}
    open fun close() {}
    open fun play() {}
    open fun startOver() {}
}

@Composable
fun AlbumWizardScreen(vm: AlbumWizardViewModel, onClose: () -> Unit, onPlay: (List<TrackEntity>) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.setCover(it) } }
    val cb = remember(vm) {
        object : AlbumCallbacks() {
            override fun query(q: String) = vm.setQuery(q)
            override fun toggle(t: TrackEntity) = vm.toggle(t)
            override fun title(v: String) = vm.setTitle(v)
            override fun artist(v: String) = vm.setArtist(v)
            override fun year(v: String) = vm.setYear(v)
            override fun genre(v: String) = vm.setGenre(v)
            override fun pickCover() = coverPicker.launch(arrayOf("image/*"))
            override fun clearCover() = vm.setCover(null)
            override fun move(from: Int, to: Int) = vm.move(from, to)
            override fun remove(i: Int) = vm.removeAt(i)
            override fun sortByTrackNumber() = vm.sortByTrackNumber()
            override fun next() = vm.next()
            override fun back() = vm.back()
            override fun save() { vm.save() }
            override fun close() = onClose()
            override fun play() = onPlay(vm.ui.value.draft.selected)
            override fun startOver() = vm.startOver()
        }
    }
    AlbumWizardContent(ui, cb)
}

private val STEPS = listOf("Pick tracks", "Details", "Order", "Saved")

@Composable
fun AlbumWizardContent(ui: AlbumWizardUi, cb: AlbumCallbacks) {
    val d = ui.draft
    WizardFrame(
        title = "COMPILE PROGRAM",
        steps = STEPS,
        current = d.step.ordinal,
        onClose = cb::close,
        footer = {
            when (d.step) {
                AlbumStep.PICK -> {
                    NeonText("${d.selected.size} picked · ${formatTime(d.selected.sumOf { it.durationMs })}", Modifier.weight(1f), size = 11.sp, color = Neon.Cyan)
                    NeonButton("NEXT ▸", { if (d.selected.isNotEmpty()) cb.next() }, active = d.selected.isNotEmpty(), accent = Neon.Orange)
                }
                AlbumStep.DETAILS -> {
                    NeonButton("◂ BACK", cb::back)
                    Spacer(Modifier.weight(1f))
                    NeonButton("NEXT ▸", { if (d.title.isNotBlank()) cb.next() }, active = d.title.isNotBlank(), accent = Neon.Orange)
                }
                AlbumStep.ORDER -> {
                    NeonButton("◂ BACK", cb::back)
                    Spacer(Modifier.weight(1f))
                    NeonButton("COMPILE ▸", { if (d.canSave) cb.save() }, active = d.canSave, accent = Neon.Orange)
                }
                AlbumStep.SAVED -> {
                    NeonButton("NEW ALBUM", cb::startOver)
                    Spacer(Modifier.weight(1f))
                    NeonButton("▶ PLAY IT", cb::play, active = true, accent = Neon.Orange)
                }
            }
        },
    ) {
        when (d.step) {
            AlbumStep.PICK -> PickStep(ui, cb)
            AlbumStep.DETAILS -> DetailsStep(d, cb)
            AlbumStep.ORDER -> OrderStep(d, cb)
            AlbumStep.SAVED -> SavedStep(d)
        }
    }
}

@Composable
private fun PickStep(ui: AlbumWizardUi, cb: AlbumCallbacks) {
    val chosen = ui.draft.selectedIds
    NeonTextField(ui.draft.query, cb::query, Modifier.fillMaxWidth(), placeholder = "Search your library")
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxSize().background(Neon.Panel, RoundedCornerShape(10.dp)).neonBorder(Neon.Cyan, 10.dp, 0.6f).padding(6.dp)) {
        if (ui.library.isEmpty()) {
            NeonText("No tracks yet. Import some music first.", Modifier.align(Alignment.Center), size = 12.sp, color = Neon.Dim, glow = null)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(ui.library, key = { it.id }) { t ->
                val on = t.id in chosen
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (on) Neon.Orange.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { cb.toggle(t) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeonCheck(on)
                    Spacer(Modifier.width(10.dp))
                    ArtThumb(t.artPath, size = 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        NeonText(t.title, size = 12.sp, color = if (on) Neon.Orange else Neon.Ink, glow = null)
                        NeonText(listOfNotNull(t.artist, t.album).joinToString(" · "), size = 10.sp, color = Neon.Dim, glow = null)
                    }
                    NeonText(formatTime(t.durationMs), size = 10.sp, color = Neon.Dim, glow = null)
                }
            }
        }
    }
}

@Composable
private fun DetailsStep(d: AlbumDraft, cb: AlbumCallbacks) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtThumb(d.selected.firstNotNullOfOrNull { it.artPath }, size = 120.dp, contentUri = d.coverUri)
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NeonText("COVER", size = 9.sp, color = Neon.Dim, glow = null, spacing = 2.sp)
                NeonButton("CHOOSE IMAGE", cb::pickCover, accent = Neon.Orange)
                if (d.coverUri != null) NeonButton("USE TRACK ART", cb::clearCover)
                NeonText(if (d.coverUri != null) "Your image" else "From the first track", size = 10.sp, color = Neon.Dim, glow = null)
            }
        }
        NeonTextField(d.title, cb::title, Modifier.fillMaxWidth(), label = "ALBUM TITLE", placeholder = "Required")
        NeonTextField(d.artist, cb::artist, Modifier.fillMaxWidth(), label = "ARTIST", placeholder = "Various artists")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonTextField(d.year, cb::year, Modifier.weight(1f), label = "YEAR", placeholder = "2026", numeric = true)
            NeonTextField(d.genre, cb::genre, Modifier.weight(2f), label = "GENRE", placeholder = "Synthwave")
        }
        NeonText(
            "The album is saved in your Kororythm library. Your music files are not changed.",
            size = 10.sp, color = Neon.Dim, glow = null, maxLines = 2,
        )
    }
}

@Composable
private fun OrderStep(d: AlbumDraft, cb: AlbumCallbacks) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NeonText("${d.selected.size} tracks · ${formatTime(d.selected.sumOf { it.durationMs })}", Modifier.weight(1f), size = 12.sp, color = Neon.Cyan)
        NeonButton("SORT BY TRACK #", cb::sortByTrackNumber)
    }
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxSize().background(Neon.Panel, RoundedCornerShape(10.dp)).neonBorder(Neon.Cyan, 10.dp, 0.6f).padding(6.dp)) {
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(d.selected, key = { _, t -> t.id }) { i, t ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    NeonText("%02d".format(i + 1), Modifier.width(30.dp), size = 12.sp, color = Neon.Orange, glow = null, weight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) {
                        NeonText(t.title, size = 12.sp, glow = null)
                        NeonText(t.artist ?: "", size = 10.sp, color = Neon.Dim, glow = null)
                    }
                    SmallKey("▲", i > 0) { cb.move(i, i - 1) }
                    SmallKey("▼", i < d.selected.lastIndex) { cb.move(i, i + 1) }
                    SmallKey("✕", true) { cb.remove(i) }
                }
            }
        }
    }
}

@Composable
private fun SmallKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(32.dp)
            .neonBorder(if (enabled) Neon.Cyan else Neon.Dim, 6.dp, if (enabled) 0.6f else 0.25f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NeonText(label, size = 12.sp, color = if (enabled) Neon.Cyan else Neon.Dim, glow = null)
    }
}

@Composable
private fun SavedStep(d: AlbumDraft) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(10.dp))
        Box(contentAlignment = Alignment.Center) {
            UploadComplete(Modifier.size(220.dp), progressOverride = 1f)
        }
        NeonText("PROGRAM COMPILED", size = 18.sp, weight = FontWeight.Bold, spacing = 4.sp, glow = Neon.Orange)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtThumb(d.selected.firstNotNullOfOrNull { it.artPath }, size = 64.dp, contentUri = d.coverUri)
            Spacer(Modifier.width(12.dp))
            Column {
                NeonText(d.title, size = 14.sp, weight = FontWeight.Bold)
                NeonText(listOf(d.artist, d.year, d.genre).filter { it.isNotBlank() }.joinToString(" · "), size = 11.sp, color = Neon.Cyan, glow = null)
                NeonText("${d.selected.size} tracks · ${formatTime(d.selected.sumOf { it.durationMs })}", size = 10.sp, color = Neon.Dim, glow = null)
            }
        }
        Spacer(Modifier.height(10.dp))
        NeonText("Find it under LIBRARY ▸ MY ALBUMS.", size = 11.sp, color = Neon.Dim, glow = null)
    }
}
