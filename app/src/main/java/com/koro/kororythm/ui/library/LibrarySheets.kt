package com.koro.kororythm.ui.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
                    listOfNotNull(t.artist, t.codec, t.bitrateKbps?.let { "$it kbps" }, t.bits?.let { "$it-bit" }).joinToString(" · "),
                    size = 10.sp, color = Neon.Dim, glow = null,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        MenuItem("▶", "Play next / add to queue") { cb.enqueue(listOf(t)); cb.menu(null) }
        MenuItem("☰", "Add to playlist…") { cb.chooseList(listOf(t.id)) }
        MenuItem("✎", "Edit tags & file name") { cb.menu(null); cb.editTags(t) }
        MenuItem("♪", "Lyrics", "Find synced lyrics, save them into the file") { cb.lyrics(t) }
        if (isLossy(t)) {
            MenuItem("✦", "Upgrade quality", "Replace with a FLAC or a higher-bitrate MP3", color = if (Neon.isHiFi) Neon.Orange else Color(0xFFFFC94D)) { cb.upgrade(t) }
        } else {
            MenuItem("✦", "Upgrade quality", "Replace with a higher-resolution FLAC") { cb.upgrade(t) }
        }
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
fun PlaylistList(lists: List<PlaylistSummary>, cb: LibraryCallbacks) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.End) {
            NeonButton("+ NEW PLAYLIST", { cb.newPlaylist(emptyList()) }, accent = Neon.Orange)
        }
        if (lists.isEmpty()) {
            Hint("No playlists yet.\nTap ⋮ on any song ▸ ADD TO PLAYLIST, or save the queue from the player.")
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
