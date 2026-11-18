package com.koro.kororythm.ui.wizards

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koro.kororythm.library.Library
import com.koro.kororythm.library.TagRules
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.library.UserAlbumEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AlbumStep { PICK, DETAILS, ORDER, SAVED }

data class AlbumDraft(
    val step: AlbumStep = AlbumStep.PICK,
    val query: String = "",
    val selected: List<TrackEntity> = emptyList(),
    val title: String = "",
    val artist: String = "",
    val year: String = "",
    val genre: String = "",
    val coverUri: String? = null,
    val savedId: Long? = null,
) {
    val selectedIds: Set<Long> get() = selected.map { it.id }.toHashSet()
    val canSave: Boolean get() = selected.isNotEmpty() && title.isNotBlank()
}

data class AlbumWizardUi(val draft: AlbumDraft = AlbumDraft(), val library: List<TrackEntity> = emptyList())

/** "Compile Program": build your own album from library tracks. Saved in the library only. */
class AlbumWizardViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = Library.dao
    private val draft = MutableStateFlow(AlbumDraft())
    private val query = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    private val library = query.flatMapLatest { q -> TagRules.ftsQuery(q)?.let { dao.search(it) } ?: dao.tracks() }

    val ui: StateFlow<AlbumWizardUi> = combine(draft, library) { d, l -> AlbumWizardUi(d, l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlbumWizardUi())

    fun setQuery(q: String) { query.value = q; draft.update { it.copy(query = q) } }

    fun toggle(t: TrackEntity) = draft.update { d ->
        if (t.id in d.selectedIds) d.copy(selected = d.selected.filterNot { it.id == t.id }) else d.copy(selected = d.selected + t)
    }

    fun setTitle(v: String) = draft.update { it.copy(title = v) }
    fun setArtist(v: String) = draft.update { it.copy(artist = v) }
    fun setYear(v: String) = draft.update { it.copy(year = v.filter(Char::isDigit).take(4)) }
    fun setGenre(v: String) = draft.update { it.copy(genre = v) }

    fun setCover(uri: Uri?) {
        if (uri != null) runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        draft.update { it.copy(coverUri = uri?.toString()) }
    }

    fun move(from: Int, to: Int) = draft.update { d ->
        if (to !in d.selected.indices) return@update d
        d.copy(selected = d.selected.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun removeAt(i: Int) = draft.update { d -> d.copy(selected = d.selected.toMutableList().apply { removeAt(i) }) }

    /** Orders the picks by disc / track number, the way the original albums had them. */
    fun sortByTrackNumber() = draft.update { d ->
        d.copy(selected = d.selected.sortedWith(compareBy({ it.album ?: "" }, { it.discNo ?: 1 }, { it.trackNo ?: Int.MAX_VALUE })))
    }

    fun next() = draft.update { d ->
        when (d.step) {
            AlbumStep.PICK -> prefill(d).copy(step = AlbumStep.DETAILS)
            AlbumStep.DETAILS -> d.copy(step = AlbumStep.ORDER)
            else -> d
        }
    }

    fun back() = draft.update { d ->
        when (d.step) {
            AlbumStep.DETAILS -> d.copy(step = AlbumStep.PICK)
            AlbumStep.ORDER -> d.copy(step = AlbumStep.DETAILS)
            else -> d
        }
    }

    fun save() = viewModelScope.launch {
        val d = draft.value
        if (!d.canSave) return@launch
        val id = dao.createAlbum(
            UserAlbumEntity(
                title = d.title.trim(),
                artist = d.artist.trim().ifBlank { null },
                year = d.year.toIntOrNull(),
                genre = d.genre.trim().ifBlank { null },
                coverUri = d.coverUri,
                createdAt = System.currentTimeMillis(),
            ),
            d.selected.map { it.id },
        )
        draft.update { it.copy(step = AlbumStep.SAVED, savedId = id) }
    }

    fun startOver() { draft.value = AlbumDraft(); query.value = "" }

    companion object {
        /** Fills empty fields with the most common value among the picked tracks. */
        fun prefill(d: AlbumDraft): AlbumDraft {
            fun <T> common(f: (TrackEntity) -> T?): T? =
                d.selected.mapNotNull(f).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            return d.copy(
                title = d.title.ifBlank { common { it.album } ?: "" },
                artist = d.artist.ifBlank { common { it.albumArtist ?: it.artist } ?: "" },
                year = d.year.ifBlank { common { it.year }?.toString() ?: "" },
                genre = d.genre.ifBlank { common { it.genre } ?: "" },
            )
        }
    }
}
