package com.koro.kororythm.playback

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.koro.kororythm.dsp.EqPresets
import com.koro.kororythm.dsp.EqStore
import com.koro.kororythm.library.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<TrackInfo> = emptyList(),
    val currentIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
) {
    val current: TrackInfo? get() = queue.getOrNull(currentIndex)
    val totalMs: Long get() = queue.sumOf { it.durationMs }
}

/** Talks to PlaybackService through a Media3 MediaController. */
class PlayerViewModel(app: Application) : AndroidViewModel(app), PlayerActions {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private var controller: MediaController? = null
    private val controllerReady = kotlinx.coroutines.CompletableDeferred<MediaController>()
    private var ticker: Job? = null
    private val prefs = app.getSharedPreferences("queue", Context.MODE_PRIVATE)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    init {
        viewModelScope.launch {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val c = MediaController.Builder(app, token).buildAsync().await()
            controller = c
            controllerReady.complete(c)
            c.addListener(listener)
            if (c.mediaItemCount == 0) restoreQueue(c)
            refresh()
            startTicker()
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val items = (0 until c.mediaItemCount).map { TrackInfo.fromMediaItem(c.getMediaItemAt(it)) }
        _state.value = PlayerUiState(
            connected = true,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: items.getOrNull(c.currentMediaItemIndex)?.durationMs ?: 0,
            queue = items,
            currentIndex = if (items.isEmpty()) -1 else c.currentMediaItemIndex,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
        )
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                controller?.let { c ->
                    if (c.isPlaying) _state.value = _state.value.copy(positionMs = c.currentPosition)
                }
                delay(200)
            }
        }
    }

    // ---- transport ----
    override fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
            c.play()
        }
    }
    override fun play() { controller?.run { if (playbackState == Player.STATE_IDLE) prepare(); play() } }
    override fun pause() { controller?.pause() }
    override fun stop() { controller?.run { pause(); seekTo(0) } }
    override fun next() { controller?.seekToNextMediaItem() }
    override fun previous() {
        // Winamp behaviour: always go to the previous track, not the start of this one.
        controller?.run { if (hasPreviousMediaItem()) seekToPreviousMediaItem() else seekTo(0) }
    }
    override fun seekTo(ms: Long) { controller?.seekTo(ms) }
    override fun playAt(index: Int) {
        controller?.run { seekTo(index, 0); if (playbackState == Player.STATE_IDLE) prepare(); play() }
    }
    override fun toggleShuffle() { controller?.run { shuffleModeEnabled = !shuffleModeEnabled } }
    override fun cycleRepeat() {
        controller?.run {
            repeatMode = when (repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    // ---- playlist ----
    fun addUris(uris: List<Uri>, playNow: Boolean = false, persist: Boolean = true) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (persist) uris.forEach { uri ->
                runCatching {
                    app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }.recoverCatching {
                    app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val infos = withContext(Dispatchers.IO) { uris.map { TrackInfo.read(app, it) } }
            val c = controllerReady.await() // files opened before the player connects still land
            val start = c.mediaItemCount
            c.addMediaItems(infos.map { it.toMediaItem() })
            if (playNow || start == 0) {
                c.seekTo(start, 0)
                c.prepare()
                c.play()
            }
            saveQueue()
        }
    }

    override fun playTracks(tracks: List<TrackInfo>, start: Int) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val c = controllerReady.await()
            c.setMediaItems(tracks.map { it.toMediaItem() }, start.coerceIn(0, tracks.lastIndex), 0)
            c.prepare()
            c.play()
            saveQueue()
        }
    }

    override fun enqueue(tracks: List<TrackInfo>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val c = controllerReady.await()
            val wasEmpty = c.mediaItemCount == 0
            c.addMediaItems(tracks.map { it.toMediaItem() })
            if (wasEmpty) c.prepare()
            saveQueue()
        }
    }

    override fun setTempo(speed: Float, keyLock: Boolean) {
        controller?.playbackParameters = PlaybackParameters(speed, if (keyLock) 1f else speed)
    }

    private var rewriting: Triple<String, Long, Boolean>? = null

    override fun releaseFile(uri: String) {
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId == uri) {
            rewriting = Triple(uri, c.currentPosition, c.isPlaying)
            c.stop()
        }
    }

    override fun fileRewritten(oldUri: String, info: TrackInfo) {
        val c = controller ?: return
        for (i in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(i).mediaId == oldUri) c.replaceMediaItem(i, info.toMediaItem())
        }
        rewriting?.let { (uri, pos, wasPlaying) ->
            if (uri == oldUri) {
                c.prepare()
                c.seekTo(pos)
                if (wasPlaying) c.play()
            }
        }
        rewriting = null
        saveQueue()
    }

    override fun removeAt(index: Int) {
        controller?.removeMediaItem(index); saveQueue()
    }

    override fun clear() {
        controller?.run { stop(); clearMediaItems() }; saveQueue()
    }

    private fun saveQueue() {
        val c = controller ?: return
        val uris = (0 until c.mediaItemCount).joinToString("\n") { c.getMediaItemAt(it).mediaId }
        prefs.edit().putString("uris", uris).apply()
    }

    private suspend fun restoreQueue(c: MediaController) {
        val app = getApplication<Application>()
        val saved = prefs.getString("uris", null)?.lines()?.filter { it.isNotBlank() } ?: return
        val infos = withContext(Dispatchers.IO) {
            saved.mapNotNull { s ->
                // Library tracks come straight from the database; others are re-read if still readable.
                Library.dao.byUri(s)?.toTrackInfo() ?: Uri.parse(s).takeIf { canRead(app, it) }?.let { TrackInfo.read(app, it) }
            }
        }
        if (infos.isEmpty()) return
        c.setMediaItems(infos.map { it.toMediaItem() })
        c.prepare()
    }

    private fun canRead(app: Application, uri: Uri): Boolean =
        runCatching { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

    // ---- EQ ----
    override fun setBand(i: Int, db: Float) = EqStore.update { s ->
        s.copy(bandsDb = s.bandsDb.toMutableList().also { it[i] = db }, presetName = "Custom")
    }
    override fun setPreamp(db: Float) = EqStore.update { it.copy(preampDb = db) }
    override fun toggleEq() = EqStore.update { it.copy(enabled = !it.enabled) }
    override fun toggleBoost() = EqStore.update { it.copy(boost = !it.boost) }
    override fun applyPreset(name: String) = EqStore.update { s ->
        EqPresets.all[name]?.let { s.copy(bandsDb = it, presetName = name) } ?: s
    }
    override fun resetEq() = EqStore.update { it.copy(preampDb = 0f, bandsDb = List(10) { 0f }, presetName = "Flat") }

    override fun onCleared() {
        ticker?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        super.onCleared()
    }
}
