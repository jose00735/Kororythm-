package com.koro.kororythm.ui.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koro.kororythm.library.PlaylistSummary
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.ui.common.ArtThumb
import com.koro.kororythm.ui.common.NeonTextField
import com.koro.kororythm.ui.player.formatTime
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder

/** Lossy files get the shiny "Upgrade quality" button. */
fun isLossy(t: TrackEntity): Boolean = t.codec?.uppercase() !in setOf("FLAC", "WAV", "ALAC", "AIFF")

/** Gold chip with a light glint sweeping across it. */
@Composable
fun ShinyUpgrade(label: String = "✦ HQ", onClick: () -> Unit) {
    val glint by rememberInfiniteTransition(label = "glint").animateFloat(
        -0.6f, 1.6f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "g",
    )
    val gold = Color(0xFFFFC94D)
    val deep = Color(0xFFE0861C)
    Box(
        Modifier
            .padding(start = 6.dp)
            .background(Brush.verticalGradient(listOf(gold, deep)), RoundedCornerShape(5.dp))
            .drawWithContent {
                drawContent()
                val x = size.width * glint
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.75f), Color.Transparent),
                        Offset(x - size.height, 0f), Offset(x + size.height, size.height),
                    ),
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        NeonText(label, size = 9.sp, color = Color(0xFF3A2200), glow = null, weight = FontWeight.Bold)
    }
}

@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(Neon.Panel, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .neonBorder(Neon.Cyan, 14.dp, 0.8f)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .navigationBarsPadding()
                .padding(14.dp),
            content = content,
        )
    }
}

@Composable
private fun MenuItem(icon: String, label: String, hint: String? = null, color: Color = Neon.Ink, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        NeonText(icon, Modifier.width(30.dp), size = 15.sp, color = Neon.Cyan, glow = null)
        Column(Modifier.weight(1f)) {
            NeonText(label, size = 13.sp, color = color, glow = null, weight = FontWeight.Bold)
            hint?.let { NeonText(it, size = 10.sp, color = Neon.Dim, glow = null) }
        }
    }
}

/** The ⋮ menu of a track. */
@Composable
fun TrackMenu(t: TrackEntity, cb: LibraryCallbacks) {
    Sheet({ cb.menu(null) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtThumb(t.artPath, size = 48.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                NeonText(t.title, size = 14.sp, weight = FontWeight.Bold)
                NeonText(
                    listOfNotNull(t.artist, t.subGenre ?: t.genreMain ?: t.genre, t.codec, t.bitrateKbps?.let { "$it kbps" }, t.bits?.let { "$it-bit" }).joinToString(" · "),
                    size = 10.sp, color = Neon.Dim, glow = null,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        MenuItem("▶", "Play next", "Right after the song that's playing") { cb.menu(null); cb.enqueue(listOf(t), next = true) }
        MenuItem("↓", "Add to end of queue", "After everything that's already lined up") { cb.menu(null); cb.enqueue(listOf(t), next = false) }
        MenuItem("☰", "Add to playlist…") { cb.chooseList(listOf(t.id)) }
        MenuItem("✎", "Edit tags & file name") { cb.menu(null); cb.editTags(t) }
        MenuItem("↗", "Share…", "Send the audio file to another app or person") { cb.share(t) }
        MenuItem("♪", "Lyrics", "Find synced lyrics, save them into the file") { cb.lyrics(t) }
        MenuItem("✕", "Delete file…", "Removes the song from your phone and the library", color = Neon.Orange) { cb.askDelete(t) }
        if (isLossy(t)) {
            MenuItem("✦", "Upgrade quality", "Replace with a FLAC or a higher-bitrate MP3", color = if (Neon.isHiFi) Neon.Orange else Color(0xFFFFC94D)) { cb.upgrade(t) }
        } else {
            MenuItem("✦", "Upgrade quality", "Replace with a higher-resolution FLAC") { cb.upgrade(t) }
        }
    }
}

/** "+" on a song or an album: play it next, or line it up at the end? */
@Composable
fun QueueChoice(tracks: List<TrackEntity>, cb: LibraryCallbacks) {
    Sheet(cb::cancelQueue) {
        val what = if (tracks.size == 1) tracks[0].title else "${tracks.size} songs"
        NeonText("ADD TO QUEUE", size = 11.sp, color = Neon.Dim, glow = null, spacing = 2.sp)
        NeonText(what, Modifier.padding(top = 2.dp, bottom = 4.dp), size = 14.sp, weight = FontWeight.Bold, maxLines = 2)
        MenuItem("▶", "Play next", "Right after the song that's playing", color = Neon.Orange) { cb.enqueue(tracks, next = true) }
        MenuItem("↓", "Add to end", "After everything that's already lined up") { cb.enqueue(tracks, next = false) }
    }
}

/** Pick a playlist for "Add to playlist". */
@Composable
fun ChoosePlaylist(lists: List<PlaylistSummary>, trackIds: List<Long>, cb: LibraryCallbacks) {
    Sheet(cb::cancelChoose) {
        NeonText("ADD TO PLAYLIST", size = 11.sp, color = Neon.Dim, glow = null, spacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        MenuItem("+", "New playlist…", color = Neon.Orange) { cb.newPlaylist(trackIds) }
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(lists, key = { it.id }) { p ->
                MenuItem("☰", p.name, "${p.trackCount} tracks · ${formatTime(p.durationMs)}") { cb.addTo(p) }
            }
        }
    }
}

/** Type a playlist name. */
@Composable
fun NameDialog(p: NamePrompt, cb: LibraryCallbacks) {
    var name by remember(p) { mutableStateOf(p.initial) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(remember { MutableInteractionSource() }, null, onClick = cb::cancelPrompt),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(28.dp).fillMaxWidth()
                .background(Neon.Panel, RoundedCornerShape(12.dp)).neonBorder(Neon.Cyan, 12.dp)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(16.dp),
        ) {
            NeonText(p.title, size = 12.sp, weight = FontWeight.Bold, spacing = 2.sp)
            p.newWith?.let { if (it.isNotEmpty()) NeonText("${it.size} tracks", size = 10.sp, color = Neon.Dim, glow = null) }
            Spacer(Modifier.height(10.dp))
            NeonTextField(name, { name = it }, placeholder = "Late-night drive")
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("SAVE", { cb.promptDone(name) }, active = name.isNotBlank(), accent = Neon.Orange)
                NeonButton("CANCEL", cb::cancelPrompt)
            }
        }
    }
}

@Composable
fun PlaylistList(lists: List<PlaylistSummary>, cb: LibraryCallbacks, coverFlow: Boolean = false) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
            if (lists.size > 1) NeonButton(if (coverFlow) "▦ LIST" else "◫ COVER FLOW", cb::coverFlow, active = coverFlow)
            NeonButton("+ NEW PLAYLIST", { cb.newPlaylist(emptyList()) }, accent = Neon.Orange)
        }
        if (lists.isEmpty()) {
            Hint("No playlists yet.\nTap ⋮ on any song ▸ ADD TO PLAYLIST, or save the queue from the player.")
            return@Column
        }
        if (coverFlow && lists.size > 1) {
            CoverFlow(
                lists.map { AlbumCard(it.name, "Playlist", "${it.trackCount} tracks · ${formatTime(it.durationMs)}", it.artPath, null) },
                onOpen = { i -> cb.openPlaylist(lists[i]) }, onPlay = { i -> cb.openPlaylist(lists[i]) },
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(lists, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { cb.openPlaylist(p) }.padding(horizontal = 6.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtThumb(p.artPath, size = 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        NeonText(p.name, size = 13.sp, weight = FontWeight.Bold)
                        NeonText("${p.trackCount} tracks · ${formatTime(p.durationMs)}", size = 10.sp, color = Neon.Dim, glow = null)
                    }
                    NeonText("▸", size = 14.sp, color = Neon.Cyan, glow = null)
                }
            }
        }
    }
}

/** Decade filter chips: pick one or more (e.g. 1990s + 2000s); applies to every list. */
@Composable
fun DecadeRow(ui: LibraryUi, cb: LibraryCallbacks) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip("ALL YEARS", ui.decades.isEmpty(), cb::clearDecades)
        ui.decadeCounts.forEach { (d, n) ->
            Chip("${com.koro.kororythm.library.Decades.label(d)} · $n", d in ui.decades) { cb.toggleDecade(d) }
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = if (on) Neon.Orange else Neon.Dim
    Box(
        Modifier.background(c.copy(alpha = if (on) 0.18f else 0.08f), RoundedCornerShape(12.dp))
            .neonBorder(c, 12.dp, if (on) 0.9f else 0.35f)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        NeonText(text, size = 10.sp, color = if (on) Neon.Ink else Neon.Dim, glow = null, weight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun CenterDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth()
                .background(Neon.Panel, RoundedCornerShape(12.dp)).neonBorder(Neon.Cyan, 12.dp)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(16.dp),
            content = content,
        )
    }
}

/** "Delete this file?" */
@Composable
fun DeleteDialog(t: TrackEntity, cb: LibraryCallbacks) {
    CenterDialog(cb::cancelDelete) {
        NeonText("DELETE FILE?", size = 13.sp, weight = FontWeight.Bold, color = Neon.Orange, glow = null, spacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtThumb(t.artPath, size = 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                NeonText(t.title, size = 13.sp, weight = FontWeight.Bold)
                NeonText(listOfNotNull(t.artist, t.album).joinToString(" · ").ifBlank { "Unknown artist" }, size = 10.sp, color = Neon.Dim, glow = null)
            }
        }
        NeonText(
            "The file is removed from your phone, and the song from the library, its playlists and the queue. This can't be undone.",
            Modifier.padding(vertical = 10.dp), size = 11.sp, color = Neon.Ink, glow = null, maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("DELETE", cb::confirmDelete, active = true, accent = Neon.Orange)
            NeonButton("CANCEL", cb::cancelDelete)
        }
    }
}

/** Result of the manual refresh. */
@Composable
fun MissingDialog(missing: List<com.koro.kororythm.library.LibraryCheck.Missing>, cb: LibraryCallbacks, total: Int = 0) {
    CenterDialog(cb::dismissMissing) {
        if (missing.isEmpty()) {
            NeonText("ALL FILES PRESENT ✓", size = 13.sp, weight = FontWeight.Bold, spacing = 2.sp)
            NeonText("Every song in the library still has its file.", Modifier.padding(vertical = 10.dp), size = 11.sp, color = Neon.Dim, glow = null, maxLines = 2)
            NeonButton("OK", cb::dismissMissing)
            return@CenterDialog
        }
        NeonText("${missing.size} SONG${if (missing.size == 1) "" else "S"} MISSING", size = 13.sp, weight = FontWeight.Bold, color = Neon.Orange, glow = null, spacing = 2.sp)
        val suspicious = com.koro.kororythm.library.LibraryCheck.suspicious(missing.size, total)
        if (suspicious) {
            NeonText(
                "That's ${missing.size} of $total songs, which usually means Kororythm can't read your storage right now (a card or folder unmounted, or access taken away), not that the files are gone. Keeping them is safer; check Library ▸ Import.",
                Modifier.padding(vertical = 6.dp), size = 11.sp, color = Neon.Orange, glow = null, maxLines = 6,
            )
        } else {
            NeonText("Their files were moved, deleted or can't be reached any more:", Modifier.padding(vertical = 6.dp), size = 11.sp, color = Neon.Dim, glow = null, maxLines = 2)
        }
        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(missing, key = { it.track.id }) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    NeonText("✕", Modifier.width(22.dp), size = 12.sp, color = Neon.Orange, glow = null)
                    Column(Modifier.weight(1f)) {
                        NeonText(m.track.title, size = 12.sp, weight = FontWeight.Bold)
                        NeonText(listOfNotNull(m.track.artist, m.reason).joinToString(" · "), size = 9.sp, color = Neon.Dim, glow = null)
                    }
                }
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (suspicious) {
                NeonButton("KEEP THEM", cb::dismissMissing, active = true)
                NeonButton("REMOVE ANYWAY", cb::removeMissing, accent = Neon.Orange)
            } else {
                NeonButton("REMOVE FROM LIBRARY", cb::removeMissing, active = true, accent = Neon.Orange)
                NeonButton("KEEP", cb::dismissMissing)
            }
        }
    }
}

