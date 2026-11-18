package com.koro.kororythm.ui.tags

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.tags.TagMatch
import com.koro.kororythm.tags.TagNaming
import com.koro.kororythm.ui.common.ArtThumb
import com.koro.kororythm.ui.common.NeonScreen
import com.koro.kororythm.ui.common.NeonTextField
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder

open class TagEditCallbacks {
    open fun close() {}
    open fun form(f: TagForm) {}
    open fun fileBase(v: String) {}
    open fun tagsFromName() {}
    open fun nameFromTags(p: TagNaming.Pattern) {}
    open fun query(v: String) {}
    open fun search() {}
    open fun use(m: TagMatch) {}
    open fun pickCover() {}
    open fun removeCover() {}
    open fun save() {}
}

@Composable
/**
 * [next] / [progress] are set while fixing a list of badly tagged songs one by one
 * ("FIX ONE BY ONE" in Library ▸ NEEDS TAGS).
 */
fun TagEditorScreen(vm: TagEditViewModel, player: PlayerActions, onClose: () -> Unit, next: (() -> Unit)? = null, progress: String? = null) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::setCoverFrom) }
    // Android's "Allow Kororythm to modify this file?" dialog; on yes, save again.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.dismissConsent()
        if (r.resultCode == Activity.RESULT_OK) vm.save(player)
    }
    LaunchedEffect(ui.consent) { ui.consent?.let { consent.launch(IntentSenderRequest.Builder(it).build()) } }
    // After a rename-by-copy: "Allow Kororythm to delete this file?" for the old copy.
    val deleteConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.deleteResult(r.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(ui.deleteConsent) { ui.deleteConsent?.let { deleteConsent.launch(IntentSenderRequest.Builder(it).build()) } }
    val cb = remember(vm) {
        object : TagEditCallbacks() {
            override fun close() = onClose()
            override fun form(f: TagForm) = vm.setForm(f)
            override fun fileBase(v: String) = vm.setFileBase(v)
            override fun tagsFromName() = vm.tagsFromName()
            override fun nameFromTags(p: TagNaming.Pattern) = vm.nameFromTags(p)
            override fun query(v: String) = vm.setQuery(v)
            override fun search() = vm.search()
            override fun use(m: TagMatch) = vm.use(m)
            override fun pickCover() = coverPicker.launch(arrayOf("image/jpeg", "image/png"))
            override fun removeCover() = vm.removeCover()
            override fun save() = vm.save(player)
        }
    }
    TagEditorContent(ui, cb, next, progress)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorContent(ui: TagEditUi, cb: TagEditCallbacks, next: (() -> Unit)? = null, progress: String? = null) {
    NeonScreen(
        title = progress?.let { "FIX $it" } ?: "EDIT TAGS",
        onBack = cb::close,
        backLabel = "✕ CLOSE",
        actions = {
            NeonButton(if (ui.saved) "SAVED ✓" else if (next != null) "SAVE" else "SAVE TO FILE", cb::save, active = !ui.saved, accent = Neon.Orange)
            next?.let { NeonButton(if (ui.saved) "NEXT ▸" else "SKIP ▸", it, active = ui.saved) }
        },
    ) {
        val f = ui.form
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // status line
            val status = ui.busy ?: ui.error ?: ui.message
            if (status != null) {
                NeonText(status, size = 11.sp, color = if (ui.error != null) Neon.Orange else Neon.Cyan, glow = null, maxLines = 3)
            }

            // --- file name ---
            Section("FILE NAME") {
                Row(verticalAlignment = Alignment.Bottom) {
                    NeonTextField(ui.fileBase, cb::fileBase, Modifier.weight(1f))
                    NeonText(ui.ext, Modifier.padding(start = 6.dp, bottom = 10.dp), size = 12.sp, color = Neon.Dim, glow = null)
                }
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeonButton("TAGS ◂ NAME", cb::tagsFromName, accent = Neon.Orange)
                    TagNaming.Pattern.entries.forEach { p -> NeonButton("NAME ◂ ${p.label.uppercase()}", { cb.nameFromTags(p) }) }
                }
                NeonText(
                    "Rename the file like \"01 - Artist - Title\" and tap TAGS ◂ NAME, or fill the tags and build the name from them.",
                    Modifier.padding(top = 6.dp), size = 10.sp, color = Neon.Dim, glow = null, maxLines = 3,
                )
            }

            // --- search public databases ---
            Section("FIND TAGS ONLINE · MUSICBRAINZ + ITUNES") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeonTextField(ui.query, cb::query, Modifier.weight(1f), placeholder = "Artist and title")
                    Spacer(Modifier.width(8.dp))
                    NeonButton(if (ui.searching) "…" else "SEARCH", cb::search, active = true, accent = Neon.Orange)
                }
                ui.results.forEach { m -> MatchRow(m, cb) }
                if (ui.searched && ui.results.isEmpty() && !ui.searching) {
                    NeonText("No matches.", Modifier.padding(top = 6.dp), size = 11.sp, color = Neon.Dim, glow = null)
                }
            }

            // --- tags + cover ---
            Section("TAGS") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtThumb(null, size = 96.dp, bytes = ui.cover)
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        NeonButton("CHOOSE COVER", cb::pickCover)
                        if (ui.cover != null) NeonButton("REMOVE COVER", cb::removeCover)
                        NeonText(
                            when {
                                ui.coverChanged -> "New cover, saved with the tags"
                                ui.coverRemoved -> "Cover will be removed"
                                ui.cover != null -> "Cover inside the file"
                                else -> "No cover"
                            },
                            size = 10.sp, color = Neon.Dim, glow = null,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                NeonTextField(f.title, { cb.form(f.copy(title = it)) }, Modifier.fillMaxWidth(), label = "TITLE")
                NeonTextField(f.artist, { cb.form(f.copy(artist = it)) }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "ARTIST")
                NeonTextField(f.album, { cb.form(f.copy(album = it)) }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "ALBUM")
                NeonTextField(f.albumArtist, { cb.form(f.copy(albumArtist = it)) }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "ALBUM ARTIST")
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonTextField(f.genre, { cb.form(f.copy(genre = it)) }, Modifier.weight(2f), label = "GENRE")
                    NeonTextField(f.year, { cb.form(f.copy(year = it.filter(Char::isDigit).take(4))) }, Modifier.weight(1f), label = "YEAR", numeric = true)
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonTextField(f.trackNo, { cb.form(f.copy(trackNo = it.filter(Char::isDigit).take(3))) }, Modifier.weight(1f), label = "TRACK", numeric = true)
                    NeonTextField(f.trackTotal, { cb.form(f.copy(trackTotal = it.filter(Char::isDigit).take(3))) }, Modifier.weight(1f), label = "OF", numeric = true)
                    NeonTextField(f.discNo, { cb.form(f.copy(discNo = it.filter(Char::isDigit).take(2))) }, Modifier.weight(1f), label = "DISC", numeric = true)
                }
            }
            NeonText(
                "SAVE TO FILE writes the tags (and cover) into the FLAC or MP3 itself; the audio is not touched. " +
                    "Android may ask once to allow editing.",
                size = 10.sp, color = Neon.Dim, glow = null, maxLines = 3,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Neon.Panel, RoundedCornerShape(10.dp))
            .neonBorder(Neon.Cyan, 10.dp, 0.6f)
            .padding(12.dp),
    ) {
        NeonText(title, Modifier.padding(bottom = 8.dp), size = 9.sp, color = if (Neon.isHiFi) Neon.Ink else Neon.Cyan, spacing = 2.sp, weight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun MatchRow(m: TagMatch, cb: TagEditCallbacks) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).background(Neon.Lcd, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtThumb(null, size = 46.dp, url = m.coverUrl)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            NeonText(m.title, size = 12.sp, color = Neon.LcdText, glow = null)
            NeonText(listOfNotNull(m.artist, m.album).joinToString(" · "), size = 10.sp, color = Neon.LcdText.copy(alpha = 0.75f), glow = null)
            NeonText(
                listOfNotNull(
                    m.year?.toString(),
                    m.trackNo?.let { n -> "track $n" + (m.trackTotal?.let { "/$it" } ?: "") },
                    m.genre,
                    m.source,
                ).joinToString(" · "),
                size = 9.sp, color = Neon.LcdText.copy(alpha = 0.55f), glow = null,
            )
        }
        Box(Modifier.padding(start = 6.dp)) { NeonButton("USE", { cb.use(m) }, accent = Neon.Orange) }
    }
}
