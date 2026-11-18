package com.koro.kororythm.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koro.kororythm.library.AlbumSummary
import com.koro.kororythm.library.ArtistSummary
import com.koro.kororythm.library.Library
import com.koro.kororythm.library.PlaylistSummary
import com.koro.kororythm.library.TagRules
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.library.UserAlbumSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibTab(val label: String) {
    TRACKS("TRACKS"), ALBUMS("ALBUMS"), ARTISTS("ARTISTS"), GENRES("GENRES"), PLAYLISTS("PLAYLISTS"), MINE("MY ALBUMS"), NEEDS("⚑ NEEDS TAGS"),
}

/** A broad genre with its sub-genres (null = no finer genre known). */
data class GenreGroup(val main: String?, val count: Int, val subs: List<Pair<String?, Int>>)

/** State of the background genre lookup. */
data class GenreScan(val running: Boolean = false, val done: Int = 0, val found: Int = 0, val left: Int = 0, val current: String? = null)

/** A name to type: a new playlist (with the tracks to put in it) or a rename. */
data class NamePrompt(val title: String, val initial: String, val newWith: List<Long>? = null, val renameId: Long? = null)

/** An open album / artist / user album, shown instead of the list. */
data class LibDetail(
    val title: String,
    val subtitle: String,
    val artPath: String?,
    val coverUri: String?,
    val tracks: List<TrackEntity>,
    val userAlbumId: Long? = null,
    val playlistId: Long? = null,
)

data class LibraryUi(
    val tab: LibTab = LibTab.TRACKS,
    val query: String = "",
    val count: Int = 0,
    val tracks: List<TrackEntity> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val mine: List<UserAlbumSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val detail: LibDetail? = null,
    /** Albums tab shows the cover flow instead of the grid. */
    val coverFlow: Boolean = false,
    /** Track whose ⋮ menu is open. */
    val menu: TrackEntity? = null,
    /** Tracks waiting for "Add to playlist" to pick a playlist. */
    val addTo: List<Long>? = null,
    val prompt: NamePrompt? = null,
    val toast: String? = null,
    val needs: List<TrackEntity> = emptyList(),
    val genres: List<GenreGroup> = emptyList(),
    val genreScan: GenreScan = GenreScan(),
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = Library.dao
    private val tab = MutableStateFlow(LibTab.TRACKS)
    private val query = MutableStateFlow("")
    private val detail = MutableStateFlow<LibDetail?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tracks = query.flatMapLatest { q -> TagRules.ftsQuery(q)?.let { dao.search(it) } ?: dao.tracks() }

    private fun matches(q: String, vararg fields: String?) =
        q.isBlank() || fields.any { it?.contains(q.trim(), ignoreCase = true) == true }

    private val albums = combine(dao.albums(), query) { list, q -> list.filter { matches(q, it.album, it.artist) } }
    private val artists = combine(dao.artists(), query) { list, q -> list.filter { matches(q, it.artist) } }
    private val mine = combine(dao.userAlbums(), query) { list, q -> list.filter { matches(q, it.title, it.artist) } }
    private val lists = combine(dao.playlists(), query) { list, q -> list.filter { matches(q, it.name) } }
    private val overlay = MutableStateFlow(LibraryUi())

    private val genreGroups = combine(dao.genreCounts(), query) { rows, q ->
        rows.groupBy { it.genreMain }.map { (main, l) ->
            GenreGroup(main, l.sumOf { it.count }, l.map { it.subGenre to it.count }.sortedByDescending { it.second })
        }.filter { g -> matches(q, g.main) || g.subs.any { matches(q, it.first) } }
            .sortedWith(compareBy<GenreGroup> { it.main == null }.thenByDescending { it.count })
    }
    private val genreWork = androidx.work.WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(com.koro.kororythm.genres.GenreWorker.UNIQUE)
    private val scan = combine(genreWork, dao.genreUncheckedCount()) { infos, left ->
        val w = infos.lastOrNull { !it.state.isFinished }
        GenreScan(
            running = w != null,
            done = w?.progress?.getInt(com.koro.kororythm.genres.GenreWorker.K_DONE, 0) ?: 0,
            found = w?.progress?.getInt(com.koro.kororythm.genres.GenreWorker.K_FOUND, 0) ?: 0,
            left = left,
            current = w?.progress?.getString(com.koro.kororythm.genres.GenreWorker.K_CURRENT),
        )
    }
    private val extras = combine(dao.needsTags(), genreGroups, scan) { n, g, sc -> Triple(n, g, sc) }

    val ui: StateFlow<LibraryUi> = combine(
        combine(tab, query, dao.count(), detail, overlay) { t, q, c, d, o ->
            o.copy(tab = t, query = q, count = c, detail = d)
        },
        tracks, albums, artists, combine(mine, lists, extras) { a, b, c -> Triple(a, b, c) },
    ) { base, tr, al, ar, ml ->
        base.copy(
            tracks = tr, albums = al, artists = ar, mine = ml.first, playlists = ml.second,
            needs = ml.third.first, genres = ml.third.second, genreScan = ml.third.third,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUi())

    private fun ov(f: (LibraryUi) -> LibraryUi) { overlay.value = f(overlay.value) }

    fun toggleCoverFlow() = ov { it.copy(coverFlow = !it.coverFlow) }

    // ---- genres ----
    fun openGenre(main: String?) = viewModelScope.launch {
        val list = dao.genreTracks(main)
        detail.value = LibDetail(main ?: "Unknown genre", "${list.size} tracks · genre", null, null, list)
    }

    fun openSubGenre(main: String?, sub: String?) = viewModelScope.launch {
        val list = dao.subGenreTracks(main, sub)
        detail.value = LibDetail(sub ?: "Other ${main ?: ""}".trim(), "${main ?: "Unknown"} · ${list.size} tracks", null, null, list)
    }

    fun findGenres() = com.koro.kororythm.genres.GenreWorker.runNow(getApplication())

    /** Looks every song up again (e.g. after the genre rules improved). */
    fun recheckGenres() = viewModelScope.launch { dao.recheckAllGenres(); findGenres() }
    fun openMenu(t: TrackEntity?) = ov { it.copy(menu = t) }
    fun toastShown() = ov { it.copy(toast = null) }

    // ---- playlists ----
    fun chooseList(trackIds: List<Long>) = ov { it.copy(menu = null, addTo = trackIds) }
    fun cancelChoose() = ov { it.copy(addTo = null) }

    fun addTo(playlist: PlaylistSummary) = viewModelScope.launch {
        val ids = overlay.value.addTo ?: return@launch
        dao.appendToPlaylist(playlist.id, ids)
        ov { it.copy(addTo = null, toast = "Added ${ids.size} to ${playlist.name}") }
        refreshPlaylistDetail(playlist.id)
    }

    fun promptNew(trackIds: List<Long>) = ov { it.copy(addTo = null, menu = null, prompt = NamePrompt("NEW PLAYLIST", "", newWith = trackIds)) }
    fun promptRename(id: Long, current: String) = ov { it.copy(prompt = NamePrompt("RENAME PLAYLIST", current, renameId = id)) }
    fun cancelPrompt() = ov { it.copy(prompt = null) }

    fun promptDone(name: String) = viewModelScope.launch {
        val p = overlay.value.prompt ?: return@launch
        val n = name.trim().ifEmpty { return@launch }
        ov { it.copy(prompt = null) }
        when {
            p.renameId != null -> {
                dao.renamePlaylist(p.renameId, n)
                detail.value?.takeIf { it.playlistId == p.renameId }?.let { detail.value = it.copy(title = n) }
            }
            p.newWith != null -> {
                dao.createPlaylist(n, p.newWith)
                ov { it.copy(toast = "Playlist \"$n\" created" + if (p.newWith.isNotEmpty()) " with ${p.newWith.size} tracks" else "") }
            }
        }
    }

    /** "Save queue as playlist": tracks that are in the library, in queue order. */
    fun saveQueue(uris: List<String>) = viewModelScope.launch {
        val ids = uris.mapNotNull { dao.byUri(it)?.id }
        ov { it.copy(prompt = NamePrompt("SAVE QUEUE AS PLAYLIST", "", newWith = ids)) }
    }

    fun openPlaylist(p: PlaylistSummary) = viewModelScope.launch {
        detail.value = LibDetail(p.name, "Playlist", p.artPath, null, dao.playlistTracks(p.id), playlistId = p.id)
    }

    fun deletePlaylist(id: Long) = viewModelScope.launch { dao.deletePlaylist(id); detail.value = null }

    fun moveInPlaylist(from: Int, to: Int) = viewModelScope.launch {
        val d = detail.value ?: return@launch
        val id = d.playlistId ?: return@launch
        if (to !in d.tracks.indices) return@launch
        val list = d.tracks.toMutableList().apply { add(to, removeAt(from)) }
        detail.value = d.copy(tracks = list)
        dao.setPlaylistOrder(id, list.map { it.id })
    }

    fun removeFromPlaylist(index: Int) = viewModelScope.launch {
        val d = detail.value ?: return@launch
        val id = d.playlistId ?: return@launch
        val list = d.tracks.toMutableList().apply { removeAt(index) }
        detail.value = d.copy(tracks = list)
        dao.setPlaylistOrder(id, list.map { it.id })
    }

    private suspend fun refreshPlaylistDetail(id: Long) {
        detail.value?.takeIf { it.playlistId == id }?.let { detail.value = it.copy(tracks = dao.playlistTracks(id)) }
    }

    fun setTab(t: LibTab) { tab.value = t; detail.value = null }
    fun setQuery(q: String) { query.value = q }
    fun closeDetail() { detail.value = null }

    fun openAlbum(a: AlbumSummary) = viewModelScope.launch {
        detail.value = LibDetail(a.album, a.artist ?: "Various artists", a.artPath, null, dao.albumTracks(a.album, a.artist))
    }

    fun openArtist(a: ArtistSummary) = viewModelScope.launch {
        detail.value = LibDetail(a.artist, "${a.albumCount} albums · ${a.trackCount} tracks", null, null, dao.artistTracks(a.artist))
    }

    fun openUserAlbum(a: UserAlbumSummary) = viewModelScope.launch {
        val sub = listOfNotNull(a.artist, a.year?.toString(), a.genre).joinToString(" · ").ifBlank { "Your album" }
        detail.value = LibDetail(a.title, sub, a.artPath, a.coverUri, dao.userAlbumTracks(a.id), a.id)
    }

    fun deleteUserAlbum(id: Long) = viewModelScope.launch {
        dao.deleteUserAlbum(id)
        detail.value = null
    }
}
