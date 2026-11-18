package com.koro.kororythm.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.ui.common.ArtThumb
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder

/** One colour per broad genre, so the list reads at a glance. */
fun genreColor(main: String?): Color = when (main) {
    "Rock" -> Color(0xFFFF6B5A)
    "Pop" -> Color(0xFFFF7EC8)
    "Electronic" -> Color(0xFF39D9FF)
    "Hip Hop" -> Color(0xFFFFC94D)
    "R&B & Soul" -> Color(0xFFC08BFF)
    "Latin" -> Color(0xFFFF9F43)
    "Metal" -> Color(0xFFB0BEC5)
    "Jazz" -> Color(0xFF7FD8BE)
    "Classical" -> Color(0xFFE8D9A8)
    "Reggae" -> Color(0xFF6BD66B)
    "Funk & Disco" -> Color(0xFFFFE066)
    else -> Color(0xFF8FA6B5)
}

/** Lookup status + "find genres" buttons, shown at the top of GENRES. */
@Composable
private fun GenreScanBar(ui: LibraryUi, cb: LibraryCallbacks) {
    val s = ui.genreScan
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        NeonText(
            when {
                s.running -> "Looking up genres · ${s.done} checked, ${s.found} found · ${s.left} to go"
                s.left > 0 -> "${s.left} songs not looked up yet"
                else -> "All songs looked up (MusicBrainz, iTunes)"
            },
            Modifier.weight(1f), size = 10.sp, color = if (s.running) Neon.Cyan else Neon.Dim, glow = null, maxLines = 2,
        )
        if (!s.running && s.left > 0) NeonButton("FIND GENRES", cb::findGenres, accent = Neon.Orange)
        if (!s.running && s.left == 0) NeonButton("↻ RECHECK", cb::recheckGenres)
    }
    if (s.running && s.current != null) {
        NeonText("▸ ${s.current}", Modifier.padding(bottom = 6.dp), size = 9.sp, color = Neon.Dim, glow = null)
    }
}

/** GENRES tab: broad genres with their sub-genres as chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenreList(ui: LibraryUi, cb: LibraryCallbacks) {
    Column(Modifier.fillMaxSize()) {
        GenreScanBar(ui, cb)
        if (ui.genres.isEmpty()) { Hint("No genres yet.\nTap FIND GENRES to look them up online."); return@Column }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.genres, key = { it.main ?: "\u0000" }) { g ->
                val c = genreColor(g.main)
                Column(
                    Modifier.fillMaxWidth()
                        .background(c.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
                        .neonBorder(c, 8.dp, 0.35f)
                        .padding(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth().clickable { cb.openGenre(g.main) }, verticalAlignment = Alignment.CenterVertically) {
                        NeonText("◆", Modifier.padding(end = 8.dp), size = 12.sp, color = if (Neon.isHiFi) c.copy(red = c.red * 0.7f, green = c.green * 0.7f, blue = c.blue * 0.7f) else c, glow = null)
                        NeonText(g.main ?: "Unknown", Modifier.weight(1f), size = 14.sp, weight = FontWeight.Bold, color = if (g.main == null) Neon.Dim else Neon.Ink, glow = null)
                        NeonText("${g.count} ▸", size = 11.sp, color = if (Neon.isHiFi) Neon.Dim else c, glow = null)
                    }
                    val named = g.subs.filter { it.first != null }
                    if (named.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            named.forEach { (sub, n) ->
                                Row(
                                    Modifier.background(c.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                                        .clickable { cb.openSubGenre(g.main, sub) }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    NeonText(sub!!, size = 11.sp, color = Neon.Ink, glow = null)
                                    NeonText("  $n", size = 10.sp, color = if (Neon.isHiFi) Neon.Dim else c, glow = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ⚑ NEEDS TAGS tab: badly tagged songs, worst first, with what each is missing. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NeedsList(ui: LibraryUi, cb: LibraryCallbacks, nowPlayingUri: String?) {
    Column(Modifier.fillMaxSize()) {
        if (ui.needs.isEmpty()) { Hint("Every song has its artist, album and most details.\nNice library."); return@Column }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            NeonText("${ui.needs.size} songs need tags · worst first", Modifier.weight(1f), size = 10.sp, color = Neon.Dim, glow = null)
            NeonButton("FIX ONE BY ONE ▸", { cb.fixAll(ui.needs) }, accent = Neon.Orange, active = true)
        }
        if (ui.genreScan.left > 0 && !ui.genreScan.running) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                NeonText("Missing genres can be found automatically.", Modifier.weight(1f), size = 10.sp, color = Neon.Dim, glow = null)
                NeonButton("FIND GENRES", cb::findGenres)
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(ui.needs, key = { it.id }) { t -> NeedRow(t, t.uri == nowPlayingUri, cb) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun NeedRow(t: TrackEntity, playing: Boolean, cb: LibraryCallbacks) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = { cb.editTags(t) }, onLongClick = { cb.menu(t) })
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtThumb(t.artPath, size = 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            NeonText(t.title, size = 13.sp, color = if (playing) Neon.Orange else Neon.Ink, glow = null)
            NeonText(listOfNotNull(t.artist, t.album).joinToString(" · ").ifBlank { "Unknown artist" }, size = 10.sp, color = Neon.Dim, glow = null)
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                t.missing.forEach { m ->
                    NeonText(
                        "no $m",
                        Modifier.background(Neon.Orange.copy(alpha = 0.14f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                        size = 9.sp, color = Neon.Orange, glow = null,
                    )
                }
            }
        }
        NeonText("✎", Modifier.padding(start = 8.dp), size = 16.sp, color = Neon.Cyan, glow = null)
    }
}
