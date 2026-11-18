package com.koro.kororythm.ui.upgrade

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koro.kororythm.library.Library
import com.koro.kororythm.library.Scanner
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.tags.Cover
import com.koro.kororythm.tags.DeleteStep
import com.koro.kororythm.tags.Placed
import com.koro.kororythm.tags.Quality
import com.koro.kororythm.tags.TagData
import com.koro.kororythm.tags.Upgrader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UpgradeState {
    data object Idle : UpgradeState()
    data class Pick(val track: TrackEntity) : UpgradeState()
    data class Checking(val track: TrackEntity) : UpgradeState()
    data class Compare(val track: TrackEntity, val old: Quality.Spec, val new: Quality.Spec, val verdict: Quality.Verdict, val picked: Uri) : UpgradeState()
    data class Working(val track: TrackEntity, val stage: String) : UpgradeState()
    data class Consent(val sender: IntentSender) : UpgradeState()
    data class Done(val title: String, val old: String, val new: String, val power: Double, val note: String?) : UpgradeState()
    data class Failed(val message: String) : UpgradeState()
}

/** "Upgrade quality": swap a song's file for a better one, keeping everything else. */
class UpgradeViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<UpgradeState>(UpgradeState.Idle)
    val state: StateFlow<UpgradeState> = _state.asStateFlow()

    private class Pending(val old: TrackEntity, val placed: Placed, val oldSpec: Quality.Spec, val newSpec: Quality.Spec, val retry: Boolean)
    private var pending: Pending? = null

    fun start(track: TrackEntity) { _state.value = UpgradeState.Pick(track) }
    fun dismiss() { _state.value = UpgradeState.Idle; pending = null }

    fun picked(uri: Uri?) {
        val track = (_state.value as? UpgradeState.Pick)?.track ?: return
        if (uri == null) { dismiss(); return }
        _state.value = UpgradeState.Checking(track)
        viewModelScope.launch {
            runCatching {
                val app = getApplication<Application>()
                val old = withContext(Dispatchers.IO) { Upgrader.specOf(track, app) }
                val new = Upgrader.inspect(app, uri)
                UpgradeState.Compare(track, old, new, Quality.compare(old, new), uri)
            }.onSuccess { _state.value = it }
                .onFailure { _state.value = UpgradeState.Failed(it.message ?: "Couldn't read that file") }
        }
    }

    fun confirm(player: PlayerActions) {
        val c = _state.value as? UpgradeState.Compare ?: return
        val app = getApplication<Application>()
        val track = c.track
        viewModelScope.launch {
            try {
                _state.value = UpgradeState.Working(track, "Copying tags, cover and lyrics")
                val cover = withContext(Dispatchers.IO) {
                    runCatching {
                        val r = android.media.MediaMetadataRetriever()
                        try { r.setDataSource(app, Uri.parse(track.uri)); r.embeddedPicture } finally { r.release() }
                    }.getOrNull()
                }
                val lyrics = Library.dao.lyricsNow(track.uri)?.let { it.synced ?: it.plain }
                val tags = TagData(
                    title = track.title, artist = track.artist, album = track.album, albumArtist = track.albumArtist,
                    genre = track.genre, year = track.year, trackNo = track.trackNo, discNo = track.discNo,
                    cover = cover?.let { Cover.sniff(it) }, lyrics = lyrics,
                )
                _state.value = UpgradeState.Working(track, "Writing the new file next to the old one")
                val placed = Upgrader.place(app, track, c.picked, tags)
                pending = Pending(track, placed, c.old, c.new, false)

                _state.value = UpgradeState.Working(track, "Swapping it in")
                player.releaseFile(track.uri)
                delay(250)
                val oldUri = Uri.parse(track.uri)
                when (val d = withContext(Dispatchers.IO) { Upgrader.deleteOld(app, oldUri) }) {
                    DeleteStep.Done -> finish(player, oldKept = false)
                    is DeleteStep.NeedsConsent -> { pending = pending?.let { Pending(it.old, it.placed, it.oldSpec, it.newSpec, d.retry) }; this@UpgradeViewModel.player = player; _state.value = UpgradeState.Consent(d.sender) }
                    is DeleteStep.Failed -> finish(player, oldKept = true)
                }
            } catch (e: Exception) {
                runCatching { player.fileRewritten(track.uri, track.toTrackInfo()) }
                _state.value = UpgradeState.Failed(e.message ?: "Upgrade failed")
            }
        }
    }

    private var player: PlayerActions? = null

    /** Result of Android's "allow deleting" dialog. */
    fun consentResult(ok: Boolean) {
        val p = pending ?: return
        val pl = player ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            var kept = !ok
            if (ok && p.retry) kept = withContext(Dispatchers.IO) { Upgrader.deleteOld(app, Uri.parse(p.old.uri)) } != DeleteStep.Done
            finish(pl, oldKept = kept)
        }
    }

    private suspend fun finish(player: PlayerActions, oldKept: Boolean) {
        val p = pending ?: return
        val app = getApplication<Application>()
        val uri = if (oldKept) p.placed.uri else Upgrader.fixName(app, p.placed)
        val row = Scanner(app, Library.dao).replaceFile(p.old, uri, p.placed.wantedName, p.placed.size, p.newSpec.sampleRate, p.newSpec.bits)
        Library.dao.moveLyrics(p.old.uri, row.uri)
        player.fileRewritten(p.old.uri, row.toTrackInfo())
        com.koro.kororythm.dj.AnalysisWorker.runNow(app)
        val factor = if (p.oldSpec.effectiveKbps > 0) p.newSpec.effectiveKbps / p.oldSpec.effectiveKbps else 1.0
        pending = null
        this.player = null
        _state.value = UpgradeState.Done(
            p.old.title, p.oldSpec.label, p.newSpec.label, factor,
            if (oldKept) "The old file was kept (Android didn't allow deleting it). You can remove it yourself." else null,
        )
    }
}
