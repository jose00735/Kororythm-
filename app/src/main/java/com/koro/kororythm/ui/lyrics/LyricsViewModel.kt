package com.koro.kororythm.ui.lyrics

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koro.kororythm.library.Library
import com.koro.kororythm.library.LyricsEntity
import com.koro.kororythm.library.TagRules
import com.koro.kororythm.lyrics.Lrc
import com.koro.kororythm.lyrics.LrcLib
import com.koro.kororythm.lyrics.LrcLine
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.TrackInfo
import com.koro.kororythm.tags.FileAccess
import com.koro.kororythm.tags.TagData
import com.koro.kororythm.tags.WriteAccess
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LyricsUi(
    val uri: String? = null,
    val title: String = "",
    val artist: String? = null,
    val lines: List<LrcLine> = emptyList(),
    val plain: String? = null,
    val instrumental: Boolean = false,
    val source: String? = null,
    val searching: Boolean = false,
    val searched: Boolean = false,
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    val consent: IntentSender? = null,
    /** FLAC and MP3 can hold the lyrics inside the file. */
    val canSave: Boolean = false,
) {
    val synced: Boolean get() = lines.isNotEmpty()
    val hasLyrics: Boolean get() = synced || !plain.isNullOrBlank()
}

/** Lyrics for one song: kept in the library, found on LRCLIB, optionally written into the file. */
class LyricsViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(LyricsUi())
    val ui: StateFlow<LyricsUi> = _ui.asStateFlow()
    private var watch: Job? = null
    private var track: TrackInfo? = null

    fun show(t: TrackInfo?) {
        if (t == null) { watch?.cancel(); _ui.value = LyricsUi(); return }
        if (t.uri == _ui.value.uri) return
        track = t
        watch?.cancel()
        val ext = t.uri.substringAfterLast('.', "").lowercase()
        _ui.value = LyricsUi(
            uri = t.uri, title = t.title, artist = t.artist,
            canSave = t.codec?.uppercase() in setOf("FLAC", "MP3") || ext in setOf("flac", "mp3"),
        )
        watch = viewModelScope.launch {
            var first = true
            Library.dao.lyrics(t.uri).collect { row ->
                if (row != null) apply(row)
                else if (first) find(auto = true)
                first = false
            }
        }
    }

    private fun apply(row: LyricsEntity) = _ui.update {
        val lines = row.synced?.let { s -> Lrc.parse(s) }.orEmpty()
        it.copy(lines = lines, plain = row.plain ?: row.synced?.let { s -> Lrc.toPlain(s) }, source = row.source, searched = true, instrumental = row.synced == null && row.plain == null)
    }

    fun find(auto: Boolean = false) {
        val t = track ?: return
        if (_ui.value.searching) return
        _ui.update { it.copy(searching = true, error = null, message = null) }
        viewModelScope.launch {
            val found = runCatching {
                LrcLib.find(t.title, t.artist, t.album, (t.durationMs / 1000).toInt().takeIf { it > 0 })
            }
            found.onSuccess { f ->
                if (f == null) {
                    _ui.update { it.copy(searching = false, searched = true, error = if (auto) null else "No lyrics found on LRCLIB for this song.") }
                } else {
                    Library.dao.putLyrics(LyricsEntity(t.uri, f.synced, f.plain, f.source, System.currentTimeMillis()))
                    _ui.update { it.copy(searching = false, searched = true, instrumental = f.instrumental, message = if (f.synced != null) "Synced lyrics found." else "Lyrics found (not synced).") }
                }
            }.onFailure { e ->
                _ui.update { it.copy(searching = false, searched = true, error = if (auto) null else "Couldn't reach LRCLIB (${e.message ?: "offline?"})") }
            }
        }
    }

    fun dismissConsent() = _ui.update { it.copy(consent = null) }

    /** Writes the lyrics (LRC if synced) into the FLAC / MP3 file. */
    fun saveToFile(player: PlayerActions) {
        val s = _ui.value
        val uriStr = s.uri ?: return
        val text = s.lines.takeIf { it.isNotEmpty() }?.let { toLrc(it) } ?: s.plain ?: return
        val app = getApplication<Application>()
        val uri = Uri.parse(uriStr)
        when (val a = FileAccess.check(app, uri)) {
            is WriteAccess.NeedsConsent -> { _ui.update { it.copy(consent = a.sender) }; return }
            is WriteAccess.Denied -> { _ui.update { it.copy(error = a.reason) }; return }
            WriteAccess.Ok -> Unit
        }
        _ui.update { it.copy(busy = "Saving lyrics into the file…", consent = null, error = null) }
        viewModelScope.launch {
            try {
                player.releaseFile(uriStr)
                delay(300)
                val tagged = FileAccess.rewrite(app, uri, TagData(lyrics = text))
                val head = ByteArray(64 * 1024)
                val n = tagged.inputStream().use { it.read(head).coerceAtLeast(0) }
                Library.dao.byUri(uriStr)?.let { row ->
                    Library.dao.update(row.copy(sizeBytes = tagged.length(), contentKey = TagRules.contentKey(head, n, tagged.length())))
                }
                tagged.delete()
                track?.let { player.fileRewritten(uriStr, it) }
                _ui.update { it.copy(busy = null, message = "Lyrics saved into the file; other players will show them too.") }
            } catch (e: Exception) {
                track?.let { runCatching { player.fileRewritten(uriStr, it) } }
                _ui.update { it.copy(busy = null, error = e.message ?: "Saving failed") }
            }
        }
    }

    private fun toLrc(lines: List<LrcLine>) = lines.joinToString("\n") {
        val m = it.timeMs / 60_000
        val sec = (it.timeMs / 1000) % 60
        val cs = (it.timeMs % 1000) / 10
        "[%02d:%02d.%02d] %s".format(m, sec, cs, it.text)
    }
}
