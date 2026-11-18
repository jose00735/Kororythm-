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
    /** When yt-dlp last printed something (to spot a stuck download). */
    val lastActivity: Long = 0,
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
    /** Job whose log starts open (previews). */
    val logOpen: UUID? = null,
    /** yt-dlp's output while a link is being read (newest last). */
    val fetchLines: List<String> = emptyList(),
    val fetchStarted: Long = 0,
    val lastOutput: Long = 0,
    /** The link is a playlist: pick what to download. */
    val playlist: com.koro.kororythm.downloads.Probe.Playlist? = null,
    val selected: Set<Int> = emptySet(),
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
                lastActivity = w.progress.getLong(DownloadWorker.K_TIME, 0L),
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

    private var fetchId: String? = null
    private var fetchJob: kotlinx.coroutines.Job? = null

    fun fetch() {
        val url = YtLogic.extractUrl(_ui.value.link)
        if (url == null) { _ui.update { it.copy(error = "Paste a link (YouTube, YouTube Music, SoundCloud, Bandcamp…)") }; return }
        if (_ui.value.fetching) return
        val pid = "probe-" + UUID.randomUUID()
        fetchId = pid
        val now = System.currentTimeMillis()
        _ui.update {
            it.copy(
                fetching = true, error = null, info = null, playlist = null, selected = emptySet(),
                firstRun = !YtEngine.ready, fetchLines = emptyList(), fetchStarted = now, lastOutput = now,
            )
        }
        fetchJob = viewModelScope.launch {
            runCatching {
                YtEngine.probe(getApplication(), url, pid) { line ->
                    _ui.update { it.copy(fetchLines = (it.fetchLines + line).takeLast(200), lastOutput = System.currentTimeMillis()) }
                }
            }.onSuccess { probe ->
                if (fetchId != pid) return@onSuccess
                val version = YtEngine.version(getApplication())
                when (probe) {
                    is com.koro.kororythm.downloads.Probe.Single -> _ui.update {
                        val info = probe.info
                        it.copy(fetching = false, info = info, title = info.title, artist = info.artist.orEmpty(), album = "", engineVersion = version)
                    }
                    is com.koro.kororythm.downloads.Probe.Playlist -> _ui.update {
                        // A "watch?v=…&list=…" link preselects that video; a bare playlist link selects everything.
                        val vid = YtLogic.videoId(url)
                        val pre = probe.entries.filter { e -> e.id == vid }.map { e -> e.index }.toSet()
                        it.copy(fetching = false, playlist = probe, selected = pre.ifEmpty { probe.entries.map { e -> e.index }.toSet() }, album = "", engineVersion = version)
                    }
                }
            }.onFailure { e ->
                if (fetchId != pid) return@onFailure
                val cancelled = e is com.yausername.youtubedl_android.YoutubeDL.CanceledException || e is kotlinx.coroutines.CancellationException
                _ui.update { it.copy(fetching = false, error = if (cancelled) "Stopped." else "Couldn't read that link: ${com.koro.kororythm.downloads.describe(e)}") }
            }
            fetchId = null
        }
    }

    /** Stops reading a link (e.g. a huge playlist or a stuck site). */
    fun cancelFetch() {
        val pid = fetchId ?: return
        YtEngine.cancel(pid)
        fetchId = null
        fetchJob?.cancel()
        _ui.update { it.copy(fetching = false, error = "Stopped.") }
    }

    // ---- playlists ----
    fun toggle(index: Int) = _ui.update { it.copy(selected = if (index in it.selected) it.selected - index else it.selected + index) }
    fun selectAll(all: Boolean) = _ui.update { s -> s.copy(selected = if (all) s.playlist?.entries?.map { it.index }?.toSet().orEmpty() else emptySet()) }
    fun closePlaylist() = _ui.update { it.copy(playlist = null, selected = emptySet()) }

    fun downloadSelected() {
        val s = _ui.value
        val pl = s.playlist ?: return
        val picks = pl.entries.filter { it.index in s.selected }
        if (picks.isEmpty()) return
        val album = s.album.trim().ifEmpty { null }
        picks.forEach { e ->
            val info = DlInfo(e.url, e.videoTitle, e.artist, e.title, e.artist, e.durationSec, e.thumbnail, null, null, null)
            DownloadWorker.enqueue(getApplication(), info, s.format, e.title, e.artist, album)
        }
        _ui.update { it.copy(playlist = null, selected = emptySet(), link = "") }
    }

    fun cancelAll() { wm.cancelAllWorkByTag(DownloadWorker.TAG) }

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
    fun clearFinished() {
        com.koro.kororythm.downloads.DownloadLog.prune(getApplication(), _ui.value.jobs.filter { it.state == JobState.RUNNING || it.state == JobState.QUEUED }.map { it.id }.toSet())
        wm.pruneWork()
    }

    fun log(id: UUID): String = com.koro.kororythm.downloads.DownloadLog.read(getApplication(), id)

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
            val msg = runCatching { YtEngine.update(getApplication()) }.getOrElse { "Update failed: ${com.koro.kororythm.downloads.describe(it)}" }
            _ui.update { it.copy(updating = false, updateMessage = msg, engineVersion = YtEngine.version(getApplication())) }
        }
    }
}
