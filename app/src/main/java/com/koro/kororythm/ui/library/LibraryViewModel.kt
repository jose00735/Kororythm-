package com.koro.kororythm.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koro.kororythm.library.AlbumSummary
import com.koro.kororythm.library.ArtistSummary
import com.koro.kororythm.library.Library
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

enum class LibTab(val label: String) { TRACKS("TRACKS"), ALBUMS("ALBUMS"), ARTISTS("ARTISTS"), MINE("MY ALBUMS") }

/** An open album / artist / user album, shown instead of the list. */
data class LibDetail(
    val title: String,
    val subtitle: String,
    val artPath: String?,
    val coverUri: String?,
    val tracks: List<TrackEntity>,
    val userAlbumId: Long? = null,
)

data class LibraryUi(
    val tab: LibTab = LibTab.TRACKS,
    val query: String = "",
    val count: Int = 0,
    val tracks: List<TrackEntity> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val mine: List<UserAlbumSummary> = emptyList(),
    val detail: LibDetail? = null,
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

    val ui: StateFlow<LibraryUi> = combine(
        combine(tab, query, dao.count(), detail) { t, q, c, d -> LibraryUi(tab = t, query = q, count = c, detail = d) },
        tracks, albums, artists, mine,
    ) { base, tr, al, ar, mi -> base.copy(tracks = tr, albums = al, artists = ar, mine = mi) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUi())

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
