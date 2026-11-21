package com.koro.kororythm.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koro.kororythm.library.AlbumSummary
import com.koro.kororythm.library.ArtistSummary
import com.koro.kororythm.library.Library
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.koro.kororythm.library.Decades
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
    /** Decades picked in the filter row (empty = all years). */
    val decades: Set<Int> = emptySet(),
    /** Decades in the library with their song counts, oldest first ("No year" last). */
    val decadeCounts: List<Pair<Int, Int>> = emptyList(),
    /** Asking "Delete this file?". */
    val confirmDelete: TrackEntity? = null,
    /** Android's "Allow deleting?" dialog for that file. */
    val deleteConsent: android.content.IntentSender? = null,
    /** Manual refresh running: songs checked so far / total. */
    val checking: Pair<Int, Int>? = null,
    /** Pop-up after a refresh: songs whose files are gone (empty list = all fine). */
    val missing: List<com.koro.kororythm.library.LibraryCheck.Missing>? = null,
    /** How many songs that refresh checked (to spot "almost everything missing"). */
    val checkedTotal: Int = 0,
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

    // ---- decade filter: applies to tracks, albums, artists, genres and any open list ----
    private val decades = MutableStateFlow<Set<Int>>(emptySet())
    private val all = dao.allTracks().distinctUntilChanged()
    private val inDecades = combine(all, decades) { l, d -> if (d.isEmpty()) l else l.filter { Decades.matches(d, it.year) } }
    private val decadeCounts = all.map { l ->
        l.groupingBy { Decades.of(it.year) }.eachCount().toList()
            .sortedWith(compareBy<Pair<Int, Int>> { it.first == Decades.UNKNOWN }.thenBy { it.first })
    }
    private val shownTracks = combine(tracks, decades) { l, d -> if (d.isEmpty()) l else l.filter { Decades.matches(d, it.year) } }

    private val albums = combine(dao.albums(), query, inDecades, decades) { list, q, inD, d ->
        val keep = if (d.isEmpty()) null else inD.map { (it.album ?: "Unknown album") to (it.albumArtist ?: it.artist) }.toHashSet()
        list.filter { matches(q, it.album, it.artist) && (keep == null || (it.album to it.artist) in keep) }
    }
    private val artists = combine(dao.artists(), query, inDecades, decades) { list, q, inD, d ->
        val keep = if (d.isEmpty()) null else inD.map { it.artist ?: "Unknown artist" }.toHashSet()
        list.filter { matches(q, it.artist) && (keep == null || it.artist in keep) }
    }
    private val mine = combine(dao.userAlbums(), query) { list, q -> list.filter { matches(q, it.title, it.artist) } }
    private val lists = combine(dao.playlists(), query) { list, q -> list.filter { matches(q, it.name) } }
    private val overlay = MutableStateFlow(LibraryUi())

    private val genreGroups = combine(inDecades, query) { rows, q ->
        rows.groupBy { it.genreMain }.map { (main, l) ->
            GenreGroup(main, l.size, l.groupingBy { it.subGenre }.eachCount().toList().sortedByDescending { it.second })
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
    private val shownDetail = combine(detail, decades) { dt, d ->
        if (dt == null || d.isEmpty()) dt else dt.copy(tracks = dt.tracks.filter { Decades.matches(d, it.year) })
    }

    val ui: StateFlow<LibraryUi> = combine(
        combine(tab, query, dao.count(), shownDetail, overlay) { t, q, c, d, o ->
            o.copy(tab = t, query = q, count = c, detail = d)
        },
        shownTracks, albums, artists,
        combine(mine, lists, extras, decades, decadeCounts) { a, b, c, d, dc -> Extras(a, b, c, d, dc) },
    ) { base, tr, al, ar, x ->
        base.copy(
            tracks = tr, albums = al, artists = ar, mine = x.mine, playlists = x.lists,
            needs = x.extras.first, genres = x.extras.second, genreScan = x.extras.third,
            decades = x.decades, decadeCounts = x.decadeCounts,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUi())

    private data class Extras(
        val mine: List<UserAlbumSummary>,
        val lists: List<PlaylistSummary>,
        val extras: Triple<List<TrackEntity>, List<GenreGroup>, GenreScan>,
        val decades: Set<Int>,
        val decadeCounts: List<Pair<Int, Int>>,
    )

    fun toggleDecade(d: Int) { decades.value = if (d in decades.value) decades.value - d else decades.value + d }
    fun clearDecades() { decades.value = emptySet() }

    // ---- delete a file ----
    fun askDelete(t: TrackEntity) = ov { it.copy(menu = null, confirmDelete = t) }
    fun cancelDelete() = ov { it.copy(confirmDelete = null, deleteConsent = null) }
    private var deleting: Pair<TrackEntity, Boolean>? = null
    private var playerRef: com.koro.kororythm.playback.PlayerActions? = null

    fun deleteFile(player: com.koro.kororythm.playback.PlayerActions) {
        val t = overlay.value.confirmDelete ?: return
        playerRef = player
        viewModelScope.launch {
            player.releaseFile(t.uri)
            when (val d = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.koro.kororythm.tags.Upgrader.deleteOld(getApplication(), android.net.Uri.parse(t.uri)) }) {
                com.koro.kororythm.tags.DeleteStep.Done -> forget(listOf(t), "Deleted \"${t.title}\"")
                is com.koro.kororythm.tags.DeleteStep.NeedsConsent -> { deleting = t to d.retry; ov { it.copy(deleteConsent = d.sender) } }
                is com.koro.kororythm.tags.DeleteStep.Failed -> ov { it.copy(confirmDelete = null, toast = "Couldn't delete: ${d.reason}") }
            }
        }
    }

    /** Result of Android's "allow deleting" dialog. */
    fun deleteResult(ok: Boolean) {
        val (t, retry) = deleting ?: return
        deleting = null
        ov { it.copy(deleteConsent = null) }
        if (!ok) { ov { it.copy(confirmDelete = null, toast = "Not deleted") }; return }
        viewModelScope.launch {
            val done = !retry || kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.koro.kororythm.tags.Upgrader.deleteOld(getApplication(), android.net.Uri.parse(t.uri))
            } == com.koro.kororythm.tags.DeleteStep.Done
            if (done) forget(listOf(t), "Deleted \"${t.title}\"") else ov { it.copy(confirmDelete = null, toast = "Couldn't delete the file") }
        }
    }

    /** Drops songs from the library (their playlist entries and hot cues go with them) and from the queue. */
    private suspend fun forget(tracks: List<TrackEntity>, toast: String) {
        val uris = tracks.map { it.uri }
        uris.chunked(500).forEach { dao.deleteUris(it); dao.deleteLyrics(it) }
        uris.forEach { u -> playerRef?.fileRemoved(u) }
        detail.value?.let { d -> detail.value = d.copy(tracks = d.tracks.filter { it.uri !in uris }) }
        ov { it.copy(confirmDelete = null, toast = toast) }
    }

    // ---- manual refresh ----
    fun refresh(player: com.koro.kororythm.playback.PlayerActions) {
        if (overlay.value.checking != null) return
        playerRef = player
        viewModelScope.launch {
            val list = dao.tracksNow()
            ov { it.copy(checking = 0 to list.size) }
            val gone = com.koro.kororythm.library.LibraryCheck.missing(getApplication(), list) { n -> ov { it.copy(checking = n to list.size) } }
            ov { it.copy(checking = null, missing = gone, checkedTotal = list.size) }
            // New files in your folders are picked up too.
            com.koro.kororythm.library.ImportWorker.runNow(getApplication())
        }
    }

    fun removeMissing() = viewModelScope.launch {
        val gone = overlay.value.missing.orEmpty()
        ov { it.copy(missing = null) }
        if (gone.isNotEmpty()) forget(gone.map { it.track }, "Removed ${gone.size} missing songs from the library")
    }

    fun dismissMissing() = ov { it.copy(missing = null) }

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
        val added = dao.appendToPlaylist(playlist.id, ids)
        val skipped = ids.distinct().size - added
        ov {
            it.copy(
                addTo = null,
                toast = when {
                    added == 0 -> "Already in ${playlist.name}"
                    skipped > 0 -> "Added $added to ${playlist.name} · $skipped already there"
                    else -> "Added $added to ${playlist.name}"
                },
            )
        }
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
