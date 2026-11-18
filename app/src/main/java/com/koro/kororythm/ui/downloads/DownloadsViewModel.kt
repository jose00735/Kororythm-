package com.koro.kororythm.ui.downloads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.koro.kororythm.downloads.DlFormat
import com.koro.kororythm.downloads.DlInfo
import com.koro.kororythm.downloads.DownloadWorker
import com.koro.kororythm.downloads.YtEngine
import com.koro.kororythm.downloads.YtLogic
import com.koro.kororythm.library.Library
import com.koro.kororythm.playback.PlayerActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class JobState { QUEUED, RUNNING, DONE, FAILED }

data class DlJob(
    val id: UUID,
    val title: String,
    val quality: String,
    val state: JobState,
    val stage: String,
    val progress: Float,
    val error: String? = null,
    val trackId: Long = -1,
    val savedAs: String? = null,
)

data class DlUi(
    val link: String = "",
    val fetching: Boolean = false,
    val firstRun: Boolean = true,
    val info: DlInfo? = null,
    val error: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val format: DlFormat = DlFormat.MP3_V0,
    val jobs: List<DlJob> = emptyList(),
    val engineVersion: String? = null,
    val updating: Boolean = false,
    val updateMessage: String? = null,
)

class DownloadsViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(DlUi())
    val ui: StateFlow<DlUi> = _ui.asStateFlow()
    private val wm = WorkManager.getInstance(app)

    init {
        viewModelScope.launch {
            wm.getWorkInfosByTagFlow(DownloadWorker.TAG).collect { infos ->
                _ui.update { it.copy(jobs = infos.map(::toJob).sortedBy { j -> if (j.state == JobState.RUNNING || j.state == JobState.QUEUED) 0 else 1 }) }
            }
        }
    }

    private fun toJob(w: WorkInfo): DlJob {
        val title = w.outputData.getString(DownloadWorker.K_TITLE) ?: w.progress.getString(DownloadWorker.K_TITLE) ?: "Download"
        return when (w.state) {
            WorkInfo.State.SUCCEEDED -> DlJob(
                w.id, title, "", JobState.DONE, "Saved", 1f,
                trackId = w.outputData.getLong(DownloadWorker.K_TRACK_ID, -1),
                savedAs = w.outputData.getString(DownloadWorker.K_SAVED_AS),
            )
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> DlJob(
                w.id, title, "", JobState.FAILED, "Failed", 0f,
                error = if (w.state == WorkInfo.State.CANCELLED) "Cancelled" else w.outputData.getString(DownloadWorker.K_ERROR) ?: "Download failed",
            )
            WorkInfo.State.RUNNING -> DlJob(
                w.id, title, "", JobState.RUNNING,
                w.progress.getString(DownloadWorker.K_STAGE) ?: "Starting",
                w.progress.getFloat(DownloadWorker.K_PROGRESS, 0f),
            )
            else -> DlJob(w.id, title, "", JobState.QUEUED, "Waiting", 0f)
        }
    }

    fun setLink(s: String) = _ui.update { it.copy(link = s, error = null) }
    fun setTitle(s: String) = _ui.update { it.copy(title = s) }
    fun setArtist(s: String) = _ui.update { it.copy(artist = s) }
    fun setAlbum(s: String) = _ui.update { it.copy(album = s) }
    fun setFormat(f: DlFormat) = _ui.update { it.copy(format = f) }

    /** From the share sheet: put the link in and read it right away. */
    fun shared(text: String?) {
        val url = YtLogic.extractUrl(text) ?: return
        _ui.update { it.copy(link = url, info = null, error = null) }
        fetch()
    }

    fun fetch() {
        val url = YtLogic.extractUrl(_ui.value.link)
        if (url == null) { _ui.update { it.copy(error = "Paste a link (YouTube, YouTube Music, SoundCloud, Bandcamp…)") }; return }
        if (_ui.value.fetching) return
        _ui.update { it.copy(fetching = true, error = null, info = null, firstRun = !YtEngine.ready) }
        viewModelScope.launch {
            runCatching { YtEngine.info(getApplication(), url) }
                .onSuccess { info ->
                    _ui.update {
                        it.copy(
                            fetching = false, info = info, title = info.title, artist = info.artist.orEmpty(), album = "",
                            engineVersion = YtEngine.version(getApplication()),
                        )
                    }
                }
                .onFailure { e ->
                    val msg = e.message?.lines()?.lastOrNull { l -> l.contains("ERROR") }?.substringAfter("ERROR:")?.trim() ?: e.message
                    _ui.update { it.copy(fetching = false, error = (msg ?: "Couldn't read that link") + "  · If this keeps happening, tap UPDATE ENGINE.") }
                }
        }
    }

    fun download() {
        val s = _ui.value
        val info = s.info ?: return
        DownloadWorker.enqueue(
            getApplication(), info, s.format,
            title = s.title.trim().ifEmpty { info.title },
            artist = s.artist.trim().ifEmpty { null },
            album = s.album.trim().ifEmpty { null },
        )
        _ui.update { it.copy(info = null, link = "", title = "", artist = "", album = "") }
    }

    fun cancel(id: UUID) { wm.cancelWorkById(id) }
    fun clearFinished() { wm.pruneWork() }

    fun play(job: DlJob, player: PlayerActions) {
        if (job.trackId < 0) return
        viewModelScope.launch {
            Library.dao.byIds(listOf(job.trackId)).firstOrNull()?.let { player.playTracks(listOf(it.toTrackInfo())) }
        }
    }

    fun loadVersion() {
        if (_ui.value.engineVersion != null || !YtEngine.ready) return
        _ui.update { it.copy(engineVersion = YtEngine.version(getApplication())) }
    }

    fun updateEngine() {
        if (_ui.value.updating) return
        _ui.update { it.copy(updating = true, updateMessage = "Checking for a new yt-dlp…") }
        viewModelScope.launch {
            val msg = runCatching { YtEngine.update(getApplication()) }.getOrElse { "Update failed: ${it.message?.take(120)}" }
            _ui.update { it.copy(updating = false, updateMessage = msg, engineVersion = YtEngine.version(getApplication())) }
        }
    }
}
