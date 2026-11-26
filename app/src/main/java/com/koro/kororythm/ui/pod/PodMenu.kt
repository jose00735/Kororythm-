package com.koro.kororythm.ui.pod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import com.koro.kororythm.library.Decades
import com.koro.kororythm.library.Library
import com.koro.kororythm.library.PlaylistSummary
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.playback.TrackInfo
import com.koro.kororythm.ui.player.LayoutState
import com.koro.kororythm.ui.player.PlayerLayoutId
import com.koro.kororythm.ui.skin.SkinId
import com.koro.kororythm.ui.skin.SkinState

/** Where the iPod menu reads the library from (the Room database, or a fake in tests). */
interface PodCatalog {
    suspend fun tracks(): List<TrackEntity>
    suspend fun playlists(): List<PlaylistSummary>
    suspend fun playlistTracks(id: Long): List<TrackEntity>
}

/** The real library. */
object RoomCatalog : PodCatalog {
    override suspend fun tracks() = Library.dao.tracksNow()
    override suspend fun playlists(): List<PlaylistSummary> = Library.dao.playlists().first()
    override suspend fun playlistTracks(id: Long) = Library.dao.playlistTracks(id)
}

/** One row of an iPod menu. */
sealed interface PodItem {
    val label: String
    /** Small text on the right (a count, a time, "On"). */
    val detail: String? get() = null

    /** Opens another menu. */
    class Folder(override val label: String, override val detail: String? = null, val open: suspend () -> List<PodItem>) : PodItem

    /** A song in a list: pressing it plays [list] from [index]. */
    class Song(val track: TrackInfo, val list: List<TrackInfo>, val index: Int, val source: String) : PodItem {
        override val label get() = track.title
        override val detail get() = track.artist
    }

    /** A song in the queue: pressing it jumps there. */
    class Queued(val track: TrackInfo, val index: Int, val current: Boolean) : PodItem {
        override val label get() = track.title
        override val detail get() = if (current) "♪" else null
    }

    /** Runs something. [checked] draws a tick (null = no tick column). */
    class Action(override val label: String, val checked: Boolean? = null, override val detail: String? = null, val run: suspend () -> PodResult) : PodItem
}

/** What pressing a row asks the screen to do next. */
enum class PodResult { STAY, NOW_PLAYING, REFRESH }

/** One menu level: its title, rows and cursor. */
class PodLevel(val title: String, val items: List<PodItem>, cursor: Int = 0, val depth: Int = 0, val rebuild: (suspend () -> List<PodItem>)? = null) {
    var cursor by mutableStateOf(cursor.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
}

/** The menu tree, grouped the way the library screen groups it. */
class PodTree(private val catalog: PodCatalog, private val actions: PlayerActions, private val state: () -> PlayerUiState) {

    fun root(): List<PodItem> = listOf(
        PodItem.Folder("Music") { music() },
        PodItem.Folder("Up Next") { upNext() },
        PodItem.Action("Now Playing") { PodResult.NOW_PLAYING },
        PodItem.Action("Shuffle Songs") { shuffleSongs() },
        PodItem.Folder("Settings") { settings() },
    )

    suspend fun shuffleSongs(): PodResult {
        val all = catalog.tracks().map { it.toTrackInfo() }.shuffled()
        if (all.isEmpty()) return PodResult.STAY
        actions.playTracks(all, 0, source = "Shuffle Songs")
        return PodResult.NOW_PLAYING
    }

    suspend fun music(): List<PodItem> {
        val tracks = catalog.tracks()
        val playlists = catalog.playlists()
        return listOf(
            PodItem.Folder("Playlists", playlists.size.toString()) { playlists(playlists) },
            PodItem.Folder("Artists", artistsOf(tracks).size.toString()) { artists(tracks) },
            PodItem.Folder("Albums", albumsOf(tracks).size.toString()) { albums(tracks, null) },
            PodItem.Folder("Songs", tracks.size.toString()) { songs(sortSongs(tracks), "Songs") },
            PodItem.Folder("Genres", genresOf(tracks).size.toString()) { genres(tracks) },
            PodItem.Folder("Decades", decadesOf(tracks).size.toString()) { decades(tracks) },
        )
    }

    private fun playlists(list: List<PlaylistSummary>): List<PodItem> = list.map { p ->
        PodItem.Folder(p.name, p.trackCount.toString()) { songs(catalog.playlistTracks(p.id), p.name) }
    }

    private fun artists(tracks: List<TrackEntity>): List<PodItem> = artistsOf(tracks).map { (name, rows) ->
        PodItem.Folder(name, rows.size.toString()) {
            val albums = albumsOf(rows)
            if (albums.size <= 1) songs(sortAlbum(rows), name)
            else listOf(PodItem.Folder("All Songs", rows.size.toString()) { songs(sortAlbum(rows), name) }) + albums(rows, name)
        }
    }

    private fun albums(tracks: List<TrackEntity>, artist: String?): List<PodItem> = albumsOf(tracks).map { (name, rows) ->
        PodItem.Folder(name, if (artist == null) rows.firstOrNull()?.let(::artistOf) else rows.size.toString()) { songs(sortAlbum(rows), name) }
    }

    private fun genres(tracks: List<TrackEntity>): List<PodItem> = genresOf(tracks).map { (name, rows) ->
        PodItem.Folder(name, rows.size.toString()) {
            val subs = rows.groupBy { it.subGenre?.takeIf(String::isNotBlank) }
            if (subs.size <= 1) songs(sortAlbum(rows), name)
            else listOf(PodItem.Folder("All $name", rows.size.toString()) { songs(sortAlbum(rows), name) }) +
                subs.entries.sortedBy { (it.key ?: "￿").lowercase() }.map { (sub, r) ->
                    val label = sub ?: "Other $name"
                    PodItem.Folder(label, r.size.toString()) { songs(sortAlbum(r), label) }
                }
        }
    }

    private fun decades(tracks: List<TrackEntity>): List<PodItem> = decadesOf(tracks).map { (d, rows) ->
        val label = Decades.label(d)
        PodItem.Folder(label, rows.size.toString()) { songs(sortAlbum(rows), label) }
    }

    private fun songs(rows: List<TrackEntity>, source: String): List<PodItem> {
        val list = rows.map { it.toTrackInfo() }
        if (list.isEmpty()) return emptyList()
        return listOf(PodItem.Action("Shuffle", detail = list.size.toString()) {
            actions.playTracks(list.shuffled(), 0, source = source); PodResult.NOW_PLAYING
        }) + list.mapIndexed { i, t -> PodItem.Song(t, list, i, source) }
    }

    fun upNext(): List<PodItem> {
        val st = state()
        return st.queue.mapIndexed { i, t -> PodItem.Queued(t, i, i == st.currentIndex) }
    }

    fun settings(): List<PodItem> {
        val st = state()
        return listOf(
            PodItem.Action("Shuffle", detail = if (st.shuffle) "On" else "Off") { actions.toggleShuffle(); PodResult.REFRESH },
            PodItem.Action("Repeat", detail = repeatLabel(st.repeatMode)) { actions.cycleRepeat(); PodResult.REFRESH },
            PodItem.Folder("Layout", LayoutState.current.label.lowercase().replaceFirstChar { it.uppercase() }) { layouts() },
            PodItem.Folder("Skin", SkinState.current.label) { skins() },
        )
    }

    private fun layouts(): List<PodItem> = PlayerLayoutId.entries.map { l ->
        PodItem.Action(l.label.lowercase().replaceFirstChar { it.uppercase() }, checked = LayoutState.current == l) { LayoutState.set(l); PodResult.REFRESH }
    }

    private fun skins(): List<PodItem> = SkinId.entries.map { s ->
        PodItem.Action(s.label, checked = SkinState.current == s) { SkinState.set(s); PodResult.REFRESH }
    }

    companion object {
        fun repeatLabel(mode: Int) = when (mode) { 1 -> "One"; 2 -> "All"; else -> "Off" }

        fun artistOf(t: TrackEntity) = (t.albumArtist ?: t.artist)?.takeIf(String::isNotBlank) ?: "Unknown artist"
        private fun key(s: String) = s.lowercase()

        fun artistsOf(rows: List<TrackEntity>) =
            rows.groupBy(::artistOf).entries.sortedBy { key(it.key) }.map { it.key to it.value }

        fun albumsOf(rows: List<TrackEntity>) =
            rows.groupBy { it.album?.takeIf(String::isNotBlank) ?: "Unknown album" }.entries
                .sortedBy { key(it.key) }.map { it.key to it.value }

        fun genresOf(rows: List<TrackEntity>) =
            rows.groupBy { it.genreMain?.takeIf(String::isNotBlank) ?: it.genre?.takeIf(String::isNotBlank) ?: "Unknown genre" }
                .entries.sortedBy { key(it.key) }.map { it.key to it.value }

        fun decadesOf(rows: List<TrackEntity>) =
            rows.groupBy { Decades.of(it.year) }.entries.sortedBy { if (it.key == Decades.UNKNOWN) Int.MAX_VALUE else it.key }.map { it.key to it.value }

        fun sortSongs(rows: List<TrackEntity>) = rows.sortedBy { key(it.title) }
        fun sortAlbum(rows: List<TrackEntity>) =
            rows.sortedWith(compareBy({ key(artistOf(it)) }, { key(it.album ?: "") }, { it.discNo ?: 0 }, { it.trackNo ?: 0 }, { key(it.title) }))
    }
}

/**
 * The open menus, as a stack. The click wheel drives it: [tick] moves the highlight, [select]
 * opens or plays, [back] goes up a level (false at the top, so MENU can leave the menus).
 */
class PodMenuState(private val tree: PodTree) {
    val levels = mutableStateListOf(PodLevel("Kororythm", tree.root()) { tree.root() })
    /** +1 when the last move went deeper, -1 when it went back (slide direction). */
    var direction by mutableStateOf(1)
        private set
    var busy by mutableStateOf(false)
        private set

    val top: PodLevel get() = levels.last()

    fun tick(dir: Int) {
        val l = top
        if (l.items.isNotEmpty()) l.cursor = (l.cursor + dir).coerceIn(0, l.items.size - 1)
    }

    fun back(): Boolean {
        if (levels.size <= 1) return false
        direction = -1
        levels.removeAt(levels.lastIndex)
        return true
    }

    fun home() { while (levels.size > 1) levels.removeAt(levels.lastIndex) }

    /** Opens the Up Next list straight away (the queue button in the other layouts). */
    fun openUpNext(cursor: Int) {
        home(); direction = 1
        levels.add(PodLevel("Up Next", tree.upNext(), cursor, depth = 1) { tree.upNext() })
    }

    suspend fun select(actions: PlayerActions, index: Int = top.cursor): PodResult {
        val l = top
        val item = l.items.getOrNull(index) ?: return PodResult.STAY
        l.cursor = index
        return when (item) {
            is PodItem.Folder -> {
                busy = true
                val rows = try { item.open() } finally { busy = false }
                direction = 1
                levels.add(PodLevel(item.label, rows, depth = levels.size, rebuild = item.open))
                PodResult.STAY
            }
            is PodItem.Song -> { actions.playTracks(item.list, item.index, item.source); PodResult.NOW_PLAYING }
            is PodItem.Queued -> { actions.playAt(item.index); PodResult.NOW_PLAYING }
            is PodItem.Action -> {
                val r = item.run()
                if (r == PodResult.REFRESH) refresh()
                r
            }
        }
    }

    /** Re-reads the open level (after a setting changes, so its tick and detail update). */
    suspend fun refresh() {
        val l = top
        val rebuild = l.rebuild ?: return
        levels[levels.lastIndex] = PodLevel(l.title, rebuild(), l.cursor, l.depth, rebuild)
    }
}
