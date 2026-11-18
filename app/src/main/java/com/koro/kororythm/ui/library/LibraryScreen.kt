package com.koro.kororythm.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koro.kororythm.library.AlbumSummary
import com.koro.kororythm.library.ArtistSummary
import com.koro.kororythm.library.PlaylistSummary
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.library.UserAlbumSummary
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.ui.common.ArtThumb
import com.koro.kororythm.ui.common.NeonScreen
import com.koro.kororythm.ui.common.NeonTabs
import com.koro.kororythm.ui.common.NeonTextField
import com.koro.kororythm.ui.player.formatTime
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder

/** Everything the library can ask for (no-ops by default, for previews). */
open class LibraryCallbacks {
    open fun back() {}
    open fun tab(t: LibTab) {}
    open fun query(q: String) {}
    open fun play(tracks: List<TrackEntity>, start: Int) {}
    open fun enqueue(tracks: List<TrackEntity>) {}
    open fun openAlbum(a: AlbumSummary) {}
    open fun openArtist(a: ArtistSummary) {}
    open fun openUserAlbum(a: UserAlbumSummary) {}
    open fun closeDetail() {}
    open fun deleteUserAlbum(id: Long) {}
    open fun import() {}
    open fun newAlbum() {}
    open fun editTags(t: TrackEntity) {}
    open fun downloads() {}
    open fun menu(t: TrackEntity?) {}
    open fun upgrade(t: TrackEntity) {}
    open fun lyrics(t: TrackEntity) {}
    open fun chooseList(trackIds: List<Long>) {}
    open fun cancelChoose() {}
    open fun addTo(p: PlaylistSummary) {}
    open fun newPlaylist(trackIds: List<Long>) {}
    open fun openPlaylist(p: PlaylistSummary) {}
    open fun renamePlaylist(id: Long, current: String) {}
    open fun deletePlaylist(id: Long) {}
    open fun move(from: Int, to: Int) {}
    open fun removeAt(index: Int) {}
    open fun promptDone(name: String) {}
    open fun cancelPrompt() {}
    open fun coverFlow() {}
}

@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    player: PlayerActions,
    nowPlayingUri: String?,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onNewAlbum: () -> Unit,
    onEditTags: (String) -> Unit = {},
    onDownloads: () -> Unit = {},
    onUpgrade: (TrackEntity) -> Unit = {},
    onLyrics: (TrackEntity) -> Unit = {},
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    BackHandler { if (ui.detail != null) vm.closeDetail() else onBack() }
    val cb = object : LibraryCallbacks() {
        override fun back() = onBack()
        override fun tab(t: LibTab) = vm.setTab(t)
        override fun query(q: String) = vm.setQuery(q)
        override fun play(tracks: List<TrackEntity>, start: Int) {
            player.playTracks(tracks.map { it.toTrackInfo() }, start)
            onBack()
        }
        override fun enqueue(tracks: List<TrackEntity>) = player.enqueue(tracks.map { it.toTrackInfo() })
        override fun openAlbum(a: AlbumSummary) { vm.openAlbum(a) }
        override fun openArtist(a: ArtistSummary) { vm.openArtist(a) }
        override fun openUserAlbum(a: UserAlbumSummary) { vm.openUserAlbum(a) }
        override fun closeDetail() = vm.closeDetail()
        override fun deleteUserAlbum(id: Long) { vm.deleteUserAlbum(id) }
        override fun import() = onImport()
        override fun newAlbum() = onNewAlbum()
        override fun editTags(t: TrackEntity) = onEditTags(t.uri)
        override fun downloads() = onDownloads()
        override fun menu(t: TrackEntity?) = vm.openMenu(t)
        override fun upgrade(t: TrackEntity) { vm.openMenu(null); onUpgrade(t) }
        override fun lyrics(t: TrackEntity) { vm.openMenu(null); onLyrics(t) }
        override fun chooseList(trackIds: List<Long>) = vm.chooseList(trackIds)
        override fun cancelChoose() = vm.cancelChoose()
        override fun addTo(p: PlaylistSummary) { vm.addTo(p) }
        override fun newPlaylist(trackIds: List<Long>) = vm.promptNew(trackIds)
        override fun openPlaylist(p: PlaylistSummary) { vm.openPlaylist(p) }
        override fun renamePlaylist(id: Long, current: String) = vm.promptRename(id, current)
        override fun deletePlaylist(id: Long) { vm.deletePlaylist(id) }
        override fun move(from: Int, to: Int) { vm.moveInPlaylist(from, to) }
        override fun removeAt(index: Int) { vm.removeFromPlaylist(index) }
        override fun promptDone(name: String) { vm.promptDone(name) }
        override fun cancelPrompt() = vm.cancelPrompt()
        override fun coverFlow() = vm.toggleCoverFlow()
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(ui.toast) {
        ui.toast?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show(); vm.toastShown() }
    }
    LibraryContent(ui, cb, nowPlayingUri)
}

@Composable
fun LibraryContent(ui: LibraryUi, cb: LibraryCallbacks, nowPlayingUri: String? = null) {
    Box(Modifier.fillMaxSize()) {
        LibraryPage(ui, cb, nowPlayingUri)
        ui.menu?.let { TrackMenu(it, cb) }
        ui.addTo?.let { ChoosePlaylist(ui.playlists, it, cb) }
        ui.prompt?.let { NameDialog(it, cb) }
    }
}

@Composable
private fun LibraryPage(ui: LibraryUi, cb: LibraryCallbacks, nowPlayingUri: String?) {
    NeonScreen(
        title = "LIBRARY",
        onBack = { if (ui.detail != null) cb.closeDetail() else cb.back() },
        backLabel = if (ui.detail != null) "◂ BACK" else "◂ PLAYER",
        actions = {
            NeonButton("⇩ GET", cb::downloads, accent = Neon.Orange)
            NeonButton("IMPORT", cb::import, accent = Neon.Orange)
            NeonButton("+ ALBUM", cb::newAlbum)
        },
    ) {
        if (ui.count == 0) {
            EmptyLibrary(cb)
            return@NeonScreen
        }
        val detail = ui.detail
        if (detail != null) {
            DetailView(detail, cb, nowPlayingUri)
            return@NeonScreen
        }
        NeonTextField(ui.query, cb::query, Modifier.fillMaxWidth(), placeholder = "Search ${ui.count} tracks, albums, artists")
        Spacer(Modifier.height(8.dp))
        NeonTabs(LibTab.entries.map { it.label }, ui.tab.ordinal, { cb.tab(LibTab.entries[it]) })
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Neon.Panel, RoundedCornerShape(10.dp)).neonBorder(Neon.Cyan, 10.dp, 0.7f).padding(6.dp),
        ) {
            when (ui.tab) {
                LibTab.TRACKS -> TrackList(ui.tracks, cb, nowPlayingUri)
                LibTab.ALBUMS -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.End) {
                        NeonButton(if (ui.coverFlow) "▦ GRID" else "◫ COVER FLOW", cb::coverFlow)
                    }
                    val cards = ui.albums.map { AlbumCard(it.album, it.artist ?: "Various artists", "${it.trackCount} tracks", it.artPath, null) }
                    if (ui.coverFlow && cards.isNotEmpty()) {
                        CoverFlow(cards, onOpen = { i -> cb.openAlbum(ui.albums[i]) }, onPlay = { i -> cb.openAlbum(ui.albums[i]) })
                    } else {
                        AlbumGrid(cards) { i -> cb.openAlbum(ui.albums[i]) }
                    }
                }
                LibTab.PLAYLISTS -> PlaylistList(ui.playlists, cb)
                LibTab.ARTISTS -> ArtistList(ui.artists, cb)
                LibTab.MINE -> if (ui.mine.isEmpty()) {
                    Hint("No albums of your own yet.\nTap + ALBUM to compile one from your tracks.")
                } else {
                    AlbumGrid(ui.mine.map { AlbumCard(it.title, it.artist ?: "Your album", "${it.trackCount} tracks · ${formatTime(it.durationMs)}", it.artPath, it.coverUri) }) { i -> cb.openUserAlbum(ui.mine[i]) }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(cb: LibraryCallbacks) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ArtThumb(null, size = 120.dp)
        Spacer(Modifier.height(16.dp))
        NeonText("YOUR LIBRARY IS EMPTY", size = 16.sp, weight = FontWeight.Bold, spacing = 3.sp)
        NeonText("Bring in your FLAC and MP3 files to get started.", Modifier.padding(top = 6.dp), size = 12.sp, color = Neon.Dim, glow = null, maxLines = 2)
        Spacer(Modifier.height(16.dp))
        NeonButton("UPLOAD TO THE GRID ▸", cb::import, active = true, accent = Neon.Orange)
        Spacer(Modifier.height(10.dp))
        NeonButton("⇩ DOWNLOAD FROM A LINK", cb::downloads)
    }
}

@Composable
internal fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        NeonText(text, size = 12.sp, color = Neon.Dim, glow = null, maxLines = 3)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackList(tracks: List<TrackEntity>, cb: LibraryCallbacks, nowPlayingUri: String?) {
    if (tracks.isEmpty()) { Hint("No tracks match your search."); return }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackRow(t, t.uri == nowPlayingUri, { cb.play(tracks, i) }, { cb.enqueue(listOf(t)) }, cb = cb) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    t: TrackEntity,
    playing: Boolean,
    onTap: () -> Unit,
    onLong: () -> Unit,
    number: Int? = null,
    cb: LibraryCallbacks? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (playing) Neon.Orange.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(6.dp))
            .combinedClickable(onClick = onTap, onLongClick = onLong)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            NeonText("$number.", Modifier.width(28.dp), size = 11.sp, color = Neon.Dim, glow = null)
        } else {
            ArtThumb(t.artPath, size = 40.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            NeonText(t.title, size = 13.sp, color = if (playing) Neon.Orange else Neon.Ink, glow = if (playing) Neon.Orange else null)
            NeonText(listOfNotNull(t.artist, t.album).joinToString(" · ").ifBlank { "Unknown artist" }, size = 10.sp, color = Neon.Dim, glow = null)
        }
        if (t.tagsMissing) NeonText("●", Modifier.padding(horizontal = 4.dp), size = 9.sp, color = Neon.Orange, glow = null)
        NeonText(listOfNotNull(t.codec, t.bits?.let { "$it" }).joinToString(" "), Modifier.padding(horizontal = 6.dp), size = 9.sp, color = Neon.Dim, glow = null)
        NeonText(formatTime(t.durationMs), size = 11.sp, color = if (playing) Neon.Orange else Neon.Ink, glow = null)
        if (cb != null && isLossy(t)) ShinyUpgrade { cb.upgrade(t) }
        trailing?.invoke()
        if (cb != null) {
            NeonText("⋮", Modifier.clickable { cb.menu(t) }.padding(start = 8.dp, end = 2.dp), size = 18.sp, color = Neon.Cyan, glow = null)
        }
    }
}

data class AlbumCard(val title: String, val subtitle: String, val meta: String, val artPath: String?, val coverUri: String?)

@Composable
private fun AlbumGrid(cards: List<AlbumCard>, onOpen: (Int) -> Unit) {
    if (cards.isEmpty()) { Hint("No albums match your search."); return }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards.size) { i ->
            val c = cards[i]
            Column(Modifier.clickable { onOpen(i) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    ArtThumb(c.artPath, Modifier.fillMaxSize(), size = 180.dp, contentUri = c.coverUri)
                }
                NeonText(c.title, Modifier.padding(top = 6.dp), size = 12.sp, weight = FontWeight.Bold)
                NeonText(c.subtitle, size = 10.sp, color = Neon.Cyan, glow = null)
                NeonText(c.meta, size = 9.sp, color = Neon.Dim, glow = null)
            }
        }
    }
}

@Composable
private fun ArtistList(artists: List<ArtistSummary>, cb: LibraryCallbacks) {
    if (artists.isEmpty()) { Hint("No artists match your search."); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(artists, key = { it.artist }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { cb.openArtist(a) }.padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeonText("◆", size = 12.sp, color = Neon.Orange, glow = null)
                Spacer(Modifier.width(10.dp))
                NeonText(a.artist, Modifier.weight(1f), size = 13.sp)
                NeonText("${a.albumCount} albums · ${a.trackCount} tracks", size = 10.sp, color = Neon.Dim, glow = null)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DetailView(d: LibDetail, cb: LibraryCallbacks, nowPlayingUri: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ArtThumb(d.artPath ?: d.tracks.firstNotNullOfOrNull { it.artPath }, size = 110.dp, contentUri = d.coverUri)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            NeonText(d.title, size = 17.sp, weight = FontWeight.Bold, maxLines = 2)
            NeonText(d.subtitle, Modifier.padding(top = 2.dp), size = 11.sp, color = Neon.Cyan, glow = null)
            NeonText("${d.tracks.size} tracks · ${formatTime(d.tracks.sumOf { it.durationMs })}", size = 10.sp, color = Neon.Dim, glow = null)
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NeonButton("▶ PLAY ALL", { cb.play(d.tracks, 0) }, active = true, accent = Neon.Orange)
                NeonButton("+ ADD", { cb.enqueue(d.tracks) })
                d.userAlbumId?.let { id -> NeonButton("DELETE", { cb.deleteUserAlbum(id) }) }
                d.playlistId?.let { id ->
                    NeonButton("RENAME", { cb.renamePlaylist(id, d.title) })
                    NeonButton("DELETE", { cb.deletePlaylist(id) })
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxSize().background(Neon.Panel, RoundedCornerShape(10.dp)).neonBorder(Neon.Cyan, 10.dp, 0.7f).padding(6.dp)) {
        if (d.tracks.isEmpty() && d.playlistId != null) Hint("This playlist is empty.\nIn TRACKS, tap ⋮ on a song ▸ ADD TO PLAYLIST.")
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(d.tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
                TrackRow(
                    t, t.uri == nowPlayingUri, { cb.play(d.tracks, i) }, { cb.enqueue(listOf(t)) }, number = i + 1, cb = cb,
                    trailing = if (d.playlistId == null) null else {
                        {
                            Row {
                                NeonText("▲", Modifier.clickable { cb.move(i, i - 1) }.padding(horizontal = 5.dp), size = 12.sp, color = if (i > 0) Neon.Cyan else Neon.Dim, glow = null)
                                NeonText("▼", Modifier.clickable { cb.move(i, i + 1) }.padding(horizontal = 5.dp), size = 12.sp, color = if (i < d.tracks.lastIndex) Neon.Cyan else Neon.Dim, glow = null)
                                NeonText("✕", Modifier.clickable { cb.removeAt(i) }.padding(horizontal = 5.dp), size = 12.sp, color = Neon.Orange, glow = null)
                            }
                        }
                    },
                )
            }
        }
    }
}
