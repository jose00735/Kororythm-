package com.koro.kororythm.ui.bulk

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koro.kororythm.genres.GenreMap
import com.koro.kororythm.library.Library
import com.koro.kororythm.library.PlaylistSummary
import com.koro.kororythm.library.TagRules
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.tags.FileAccess
import com.koro.kororythm.tags.TagIo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One field of the bulk form: ticked = it will be changed on every selected song. */
data class BulkField(val on: Boolean = false, val value: String = "")

data class BulkUi(
    val query: String = "",
    val shown: List<TrackEntity> = emptyList(),
    val total: Int = 0,
    val selected: Set<Long> = emptySet(),
    val onlySelected: Boolean = false,
    val fields: Map<TagIo.Field, BulkField> = TagIo.Field.entries.associateWith { BulkField() },
    val playlists: List<PlaylistSummary> = emptyList(),
    val choosingList: Boolean = false,
    val newListName: String? = null,
    /** Writing: done / total. */
    val progress: Pair<Int, Int>? = null,
    val consent: IntentSender? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val changes: Map<TagIo.Field, String> get() = fields.filterValues { it.on }.mapValues { it.value.value }
}

/**
 * Bulk edit (hidden panel, Volume Up-Down-Up): pick songs, then change artist / album /
 * album artist / genre / year on all of them at once, or add them all to a playlist.
 */
class BulkViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = Library.dao
    private val state = MutableStateFlow(BulkUi())

    val ui: StateFlow<BulkUi> = combine(state, dao.allTracks(), dao.playlists()) { s, all, lists ->
        val q = s.query.trim()
        val shown = all.filter { t ->
            (!s.onlySelected || t.id in s.selected) &&
                (q.isEmpty() || listOfNotNull(t.title, t.artist, t.album, t.genre, t.genreMain, t.subGenre).any { it.contains(q, ignoreCase = true) })
        }
        s.copy(shown = shown, total = all.size, playlists = lists)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BulkUi())

    private fun set(f: (BulkUi) -> BulkUi) { state.value = f(state.value) }

    fun query(q: String) = set { it.copy(query = q) }
    fun toggle(id: Long) = set { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    fun selectShown() = set { it.copy(selected = it.selected + ui.value.shown.map { t -> t.id }) }
    fun clearSelection() = set { it.copy(selected = emptySet(), onlySelected = false) }
    fun onlySelected(on: Boolean) = set { it.copy(onlySelected = on) }
    fun field(f: TagIo.Field, value: BulkField) = set { it.copy(fields = it.fields + (f to value), message = null, error = null) }

    // ---- playlists ----
    fun chooseList(on: Boolean) = set { it.copy(choosingList = on) }
    fun newList(name: String?) = set { it.copy(newListName = name) }

    fun addTo(p: PlaylistSummary) = viewModelScope.launch {
        val ids = selectedInOrder()
        dao.appendToPlaylist(p.id, ids)
        set { it.copy(choosingList = false, message = "Added ${ids.size} songs to \"${p.name}\"") }
    }

    fun createList() = viewModelScope.launch {
        val name = state.value.newListName?.trim().orEmpty().ifEmpty { return@launch }
        val ids = selectedInOrder()
        dao.createPlaylist(name, ids)
        set { it.copy(choosingList = false, newListName = null, message = "Playlist \"$name\" created with ${ids.size} songs") }
    }

    private fun selectedInOrder(): List<Long> {
        val sel = state.value.selected
        return ui.value.shown.filter { it.id in sel }.map { it.id } + sel.filter { id -> ui.value.shown.none { it.id == id } }
    }

    // ---- writing the tags ----
    private var pendingWrite: List<TrackEntity>? = null
    private var player: PlayerActions? = null

    fun apply(player: PlayerActions) {
        val s = state.value
        if (s.changes.isEmpty() || s.selected.isEmpty() || s.progress != null) return
        this.player = player
        viewModelScope.launch {
            val tracks = s.selected.toList().chunked(500).flatMap { dao.byIds(it) }
            val access = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FileAccess.checkMany(getApplication(), tracks.map { Uri.parse(it.uri) })
            }
            if (access.consent != null) {
                // One Android dialog for every file that needs it.
                pendingWrite = tracks
                set { it.copy(consent = access.consent) }
            } else {
                write(tracks)
            }
        }
    }

    /** Result of Android's "allow editing these files" dialog. */
    fun consentResult(ok: Boolean) {
        val tracks = pendingWrite ?: return
        pendingWrite = null
        set { it.copy(consent = null) }
        if (!ok) { set { it.copy(error = "Android didn't allow editing; nothing was changed.") }; return }
        viewModelScope.launch { write(tracks) }
    }

    private suspend fun write(tracks: List<TrackEntity>) {
        val app = getApplication<Application>()
        val changes = state.value.changes
        var done = 0
        var dbOnly = 0
        val failed = mutableListOf<String>()
        set { it.copy(progress = 0 to tracks.size, message = null, error = null) }
        val current = (player as? com.koro.kororythm.playback.PlayerViewModel)?.state?.value?.current?.uri
        for (t in tracks) {
            val uri = Uri.parse(t.uri)
            val ext = t.uri.substringAfterLast('.', "").lowercase()
            val writable = t.codec?.uppercase() in setOf("FLAC", "MP3") || ext in setOf("flac", "mp3")
            try {
                var size = t.sizeBytes
                var key = t.contentKey
                if (writable) {
                    if (t.uri == current) { player?.releaseFile(t.uri); delay(250) }
                    val tagged = FileAccess.rewriteWith(app, uri) { file -> TagIo.merge(TagIo.read(file), changes) }
                    val head = ByteArray(64 * 1024)
                    val n = tagged.inputStream().use { it.read(head).coerceAtLeast(0) }
                    size = tagged.length()
                    key = TagRules.contentKey(head, n, size)
                    tagged.delete()
                } else {
                    dbOnly++
                }
                // The library follows the file.
                var row = t.copy(sizeBytes = size, contentKey = key)
                changes.forEach { (f, v) ->
                    val value = v.trim().ifEmpty { null }
                    row = when (f) {
                        TagIo.Field.ARTIST -> row.copy(artist = value)
                        TagIo.Field.ALBUM -> row.copy(album = value)
                        TagIo.Field.ALBUM_ARTIST -> row.copy(albumArtist = value)
                        TagIo.Field.YEAR -> row.copy(year = value?.take(4)?.toIntOrNull())
                        TagIo.Field.GENRE -> {
                            val (main, sub) = GenreMap.fromFile(value)
                            row.copy(genre = value, genreMain = main, subGenre = sub, genreChecked = if (sub != null) System.currentTimeMillis() else 0)
                        }
                    }
                }
                row = row.copy(tagsMissing = TagRules.tagsMissing(row.title, row.artist, row.album))
                dao.update(row)
                if (t.uri == current) player?.fileRewritten(t.uri, row.toTrackInfo())
            } catch (e: Exception) {
                failed += "${t.title}: ${e.message ?: e.javaClass.simpleName}"
                if (t.uri == current) runCatching { player?.fileRewritten(t.uri, t.toTrackInfo()) }
            }
            done++
            set { it.copy(progress = done to tracks.size) }
        }
        if (TagIo.Field.GENRE in changes) com.koro.kororythm.genres.GenreWorker.runNow(app)
        val ok = tracks.size - failed.size
        set {
            it.copy(
                progress = null,
                message = "Changed $ok song${if (ok == 1) "" else "s"}" +
                    (if (dbOnly > 0) " ($dbOnly in the library only: not FLAC/MP3)" else "") + ".",
                error = if (failed.isEmpty()) null else "${failed.size} failed:\n" + failed.take(5).joinToString("\n"),
            )
        }
    }
}
