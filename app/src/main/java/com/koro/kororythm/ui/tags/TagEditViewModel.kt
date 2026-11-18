package com.koro.kororythm.ui.tags

import android.app.Application
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koro.kororythm.library.ArtStore
import com.koro.kororythm.library.Library
import com.koro.kororythm.library.TagRules
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.TrackInfo
import com.koro.kororythm.tags.Cover
import com.koro.kororythm.tags.FileAccess
import com.koro.kororythm.tags.TagData
import com.koro.kororythm.tags.TagMatch
import com.koro.kororythm.tags.TagNaming
import com.koro.kororythm.tags.TagSearch
import com.koro.kororythm.tags.WriteAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TagForm(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: String = "",
    val trackNo: String = "",
    val trackTotal: String = "",
    val discNo: String = "",
)

data class TagEditUi(
    val uri: String? = null,
    val loading: Boolean = true,
    val fileBase: String = "",
    val ext: String = "",
    val originalBase: String = "",
    val form: TagForm = TagForm(),
    /** Current cover (embedded or newly chosen). */
    val cover: ByteArray? = null,
    val coverChanged: Boolean = false,
    val coverRemoved: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<TagMatch> = emptyList(),
    val searched: Boolean = false,
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    val consent: IntentSender? = null,
    val saved: Boolean = false,
) {
    val renamed: Boolean get() = fileBase.isNotBlank() && fileBase != originalBase
}

/** Edit a file's tags and name, with suggestions from public music databases. */
class TagEditViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(TagEditUi())
    val ui: StateFlow<TagEditUi> = _ui

    fun load(uriString: String) {
        _ui.value = TagEditUi(uri = uriString)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val uri = Uri.parse(uriString)
            val name = FileAccess.displayName(app, uri) ?: uri.lastPathSegment ?: "track"
            val (form, cover) = withContext(Dispatchers.IO) { readTags(uri) }
            val base = name.substringBeforeLast('.')
            _ui.update {
                it.copy(
                    loading = false,
                    fileBase = base, originalBase = base,
                    ext = name.substringAfterLast('.', "").let { e -> if (e.isEmpty()) "" else ".$e" },
                    form = form, cover = cover,
                    query = listOf(form.artist, form.title.ifBlank { base }).filter { s -> s.isNotBlank() }.joinToString(" "),
                )
            }
        }
    }

    private fun readTags(uri: Uri): Pair<TagForm, ByteArray?> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(getApplication(), uri)
            fun s(k: Int) = r.extractMetadata(k)?.trim().orEmpty()
            val trackRaw = s(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            TagForm(
                title = s(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre = s(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = TagRules.parseYear(s(MediaMetadataRetriever.METADATA_KEY_YEAR).ifBlank { s(MediaMetadataRetriever.METADATA_KEY_DATE) })?.toString().orEmpty(),
                trackNo = TagRules.parseNumber(trackRaw)?.toString().orEmpty(),
                trackTotal = trackRaw.substringAfter('/', "").trim(),
                discNo = TagRules.parseNumber(s(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))?.toString().orEmpty(),
            ) to r.embeddedPicture
        } catch (e: Exception) {
            TagForm() to null
        } finally {
            r.release()
        }
    }

    // ---- editing ----
    fun setForm(f: TagForm) = _ui.update { it.copy(form = f, saved = false) }
    fun setFileBase(v: String) = _ui.update { it.copy(fileBase = v, saved = false) }
    fun setQuery(v: String) = _ui.update { it.copy(query = v) }

    /** "03 - Grid Pilots - Neon Runway" -> track 3, artist, title. Empty fields only get filled… unless asked. */
    fun tagsFromName() = _ui.update { s ->
        val (n, artist, title) = TagNaming.parse(s.fileBase)
        s.copy(
            form = s.form.copy(
                title = title,
                artist = artist ?: s.form.artist,
                trackNo = n?.toString() ?: s.form.trackNo,
            ),
            message = "Filled title${if (artist != null) ", artist" else ""}${if (n != null) " and track number" else ""} from the file name.",
            saved = false,
        )
    }

    fun nameFromTags(p: TagNaming.Pattern) = _ui.update { s ->
        s.copy(fileBase = TagNaming.build(p, s.form.trackNo.toIntOrNull(), s.form.artist, s.form.title), saved = false)
    }

    fun search() {
        val q = _ui.value.query.trim()
        if (q.isEmpty()) return
        _ui.update { it.copy(searching = true, error = null) }
        viewModelScope.launch {
            val f = _ui.value.form
            // Use the fields when they look filled in, else the free-text query.
            val (title, artist) = if (f.title.isNotBlank() && q.contains(f.title, ignoreCase = true)) {
                f.title to f.artist.takeIf { it.isNotBlank() && q.contains(it, ignoreCase = true) }
            } else {
                q to null
            }
            val res = runCatching { TagSearch.search(title, artist) }.getOrDefault(emptyList())
            _ui.update {
                it.copy(
                    searching = false, searched = true, results = res,
                    error = if (res.isEmpty()) "No matches. Check your connection or try fewer words." else null,
                )
            }
        }
    }

    /** Fills the form from a database match and downloads its cover. */
    fun use(m: TagMatch) {
        _ui.update { s ->
            s.copy(
                form = s.form.copy(
                    title = m.title,
                    artist = m.artist ?: s.form.artist,
                    album = m.album ?: s.form.album,
                    albumArtist = m.albumArtist ?: s.form.albumArtist,
                    genre = m.genre ?: s.form.genre,
                    year = m.year?.toString() ?: s.form.year,
                    trackNo = m.trackNo?.toString() ?: s.form.trackNo,
                    trackTotal = m.trackTotal?.toString() ?: s.form.trackTotal,
                    discNo = m.discNo?.toString() ?: s.form.discNo,
                ),
                message = "Filled from ${m.source}.",
                saved = false,
            )
        }
        val url = m.coverUrl ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Downloading cover…") }
            val bytes = TagSearch.fetchCover(url)
            _ui.update {
                if (bytes != null) it.copy(cover = bytes, coverChanged = true, coverRemoved = false, busy = null, message = "Filled from ${m.source}, with its cover.")
                else it.copy(busy = null, message = "Filled from ${m.source} (no cover available).")
            }
        }
    }

    fun setCoverFrom(uri: Uri) = viewModelScope.launch {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
        if (bytes != null && bytes.size < 12 * 1024 * 1024) _ui.update { it.copy(cover = bytes, coverChanged = true, coverRemoved = false, saved = false) }
        else _ui.update { it.copy(error = "That image couldn't be used (max 12 MB).") }
    }

    fun removeCover() = _ui.update { it.copy(cover = null, coverChanged = false, coverRemoved = true, saved = false) }
    fun dismissConsent() = _ui.update { it.copy(consent = null) }

    // ---- saving ----
    fun save(player: PlayerActions) {
        val s = _ui.value
        val uriStr = s.uri ?: return
        val app = getApplication<Application>()
        val uri = Uri.parse(uriStr)
        when (val access = FileAccess.check(app, uri)) {
            is WriteAccess.NeedsConsent -> { _ui.update { it.copy(consent = access.sender) }; return }
            is WriteAccess.Denied -> { _ui.update { it.copy(error = access.reason) }; return }
            WriteAccess.Ok -> Unit
        }
        _ui.update { it.copy(busy = "Saving into the file…", error = null, consent = null) }
        viewModelScope.launch {
            val f = s.form
            val tags = TagData(
                title = f.title, artist = f.artist, album = f.album, albumArtist = f.albumArtist, genre = f.genre,
                year = f.year.toIntOrNull(), trackNo = f.trackNo.toIntOrNull(), trackTotal = f.trackTotal.toIntOrNull(),
                discNo = f.discNo.toIntOrNull(),
                cover = if (s.coverChanged) s.cover?.let { Cover.sniff(it) } else null,
                removeCover = s.coverRemoved,
            )
            try {
                player.releaseFile(uriStr)
                delay(300) // let the player close the file if it was playing it
                val tagged = FileAccess.rewrite(app, uri, tags)
                var newUri = uri
                var renameNote: String? = null
                if (s.renamed) {
                    try {
                        newUri = FileAccess.rename(app, uri, TagNaming.sanitize(s.fileBase) + s.ext)
                    } catch (e: com.koro.kororythm.tags.RenameUnsupported) {
                        renameNote = "Tags saved. ${e.message}"
                    }
                }
                if (newUri.toString() != uriStr) Library.dao.moveLyrics(uriStr, newUri.toString())
                val info = reindex(uriStr, newUri.toString(), tagged.length(), tagged.inputStream().use { ins ->
                    val head = ByteArray(64 * 1024)
                    val n = ins.read(head).coerceAtLeast(0)
                    TagRules.contentKey(head, n, tagged.length())
                }, s)
                tagged.delete()
                player.fileRewritten(uriStr, info)
                _ui.update {
                    it.copy(
                        uri = newUri.toString(), busy = null, saved = true, coverChanged = false, coverRemoved = false,
                        originalBase = if (renameNote != null) it.originalBase else it.fileBase,
                        fileBase = if (renameNote != null) it.originalBase else it.fileBase,
                        message = renameNote ?: if (s.renamed) "Saved into the file and renamed." else "Saved into the file.",
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, error = e.message ?: "Saving failed") }
                runCatching { player.fileRewritten(uriStr, TrackInfo.read(app, uri)) }
            }
        }
    }

    /** Updates the library row (tags, new URI, size, key, art) and returns the playable info. */
    private suspend fun reindex(oldUri: String, newUri: String, size: Long, key: String, s: TagEditUi): TrackInfo {
        val app = getApplication<Application>()
        val f = s.form
        val dao = Library.dao
        val row = dao.byUri(oldUri)
        val art = when {
            s.coverRemoved -> null
            s.coverChanged && s.cover != null -> withContext(Dispatchers.IO) { ArtStore.save(app, s.cover) }
            else -> row?.artPath
        }
        if (row != null) {
            dao.update(
                row.copy(
                    uri = newUri,
                    title = f.title.ifBlank { s.fileBase },
                    artist = f.artist.ifBlank { null },
                    album = f.album.ifBlank { null },
                    albumArtist = f.albumArtist.ifBlank { null },
                    genre = f.genre.ifBlank { null },
                    year = f.year.toIntOrNull(),
                    trackNo = f.trackNo.toIntOrNull(),
                    discNo = f.discNo.toIntOrNull(),
                    sizeBytes = size,
                    contentKey = key,
                    artPath = art,
                    tagsMissing = TagRules.tagsMissing(f.title, f.artist, f.album),
                ),
            )
            return dao.byUri(newUri)!!.toTrackInfo()
        }
        return withContext(Dispatchers.IO) { TrackInfo.read(app, Uri.parse(newUri)) }
    }
}
