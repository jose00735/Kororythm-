package com.koro.kororythm.ui.wizards

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.koro.kororythm.library.ImportWorker
import com.koro.kororythm.library.Library
import com.koro.kororythm.library.MusicBrainz
import com.koro.kororythm.library.ScanProgress
import com.koro.kororythm.library.ScanResult
import com.koro.kororythm.library.SourceEntity
import com.koro.kororythm.library.TrackEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ImportStep { SOURCES, SCANNING, REVIEW, DONE }

/** Tag suggestions for one track in the review step. */
data class SuggestState(
    val loading: Boolean = false,
    val items: List<MusicBrainz.Suggestion> = emptyList(),
    val error: String? = null,
)

data class ImportUi(
    val step: ImportStep = ImportStep.SOURCES,
    val sources: List<SourceEntity> = emptyList(),
    val progress: ScanProgress = ScanProgress(0, 0, ""),
    val result: ScanResult? = null,
    val review: List<TrackEntity> = emptyList(),
    val suggestions: Map<Long, SuggestState> = emptyMap(),
    val libraryCount: Int = 0,
    val error: String? = null,
) {
    val hasPhoneLibrary: Boolean get() = sources.any { it.isMediaStore }
    val folders: List<SourceEntity> get() = sources.filterNot { it.isMediaStore }
}

/** "Upload to the Grid": pick sources, scan in the background, fix missing tags, done. */
class ImportViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = Library.dao
    private val step = MutableStateFlow(ImportStep.SOURCES)
    private val progress = MutableStateFlow(ScanProgress(0, 0, ""))
    private val result = MutableStateFlow<ScanResult?>(null)
    private val suggestions = MutableStateFlow<Map<Long, SuggestState>>(emptyMap())
    private val skipped = MutableStateFlow<Set<Long>>(emptySet())
    private val error = MutableStateFlow<String?>(null)
    private var watch: Job? = null

    private val review = combine(dao.missingTags(), skipped) { list, skip -> list.filterNot { it.id in skip } }

    val ui: StateFlow<ImportUi> = combine(
        combine(step, dao.sources(), progress, result) { a, b, c, d -> arrayOf<Any?>(a, b, c, d) },
        combine(review, suggestions, dao.count(), error) { a, b, c, d -> arrayOf<Any?>(a, b, c, d) },
    ) { x, y ->
        @Suppress("UNCHECKED_CAST")
        ImportUi(
            step = x[0] as ImportStep,
            sources = x[1] as List<SourceEntity>,
            progress = x[2] as ScanProgress,
            result = x[3] as ScanResult?,
            review = y[0] as List<TrackEntity>,
            suggestions = y[1] as Map<Long, SuggestState>,
            libraryCount = y[2] as Int,
            error = y[3] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ImportUi())

    // ---- sources ----
    fun setPhoneLibrary(enabled: Boolean) = viewModelScope.launch {
        if (enabled) {
            dao.upsertSource(SourceEntity(SourceEntity.MEDIASTORE_KEY, "Phone music library", System.currentTimeMillis()))
        } else {
            dao.deleteSource(SourceEntity.MEDIASTORE_KEY)
            dao.deleteSourceTracks("mediastore", null)
        }
    }

    fun addFolder(tree: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        dao.upsertSource(SourceEntity(tree.toString(), folderLabel(tree), System.currentTimeMillis()))
    }

    fun removeFolder(src: SourceEntity) = viewModelScope.launch {
        dao.deleteSource(src.key)
        dao.deleteSourceTracks("folder", src.key)
        runCatching {
            getApplication<Application>().contentResolver
                .releasePersistableUriPermission(Uri.parse(src.key), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ---- scan ----
    fun startScan() {
        val app = getApplication<Application>()
        result.value = null
        error.value = null
        progress.value = ScanProgress(0, 0, "")
        step.value = ImportStep.SCANNING
        val id = ImportWorker.runNow(app)
        watch?.cancel()
        watch = viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfoByIdFlow(id).collect { info ->
                info ?: return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING -> progress.value = ScanProgress(
                        info.progress.getInt(ImportWorker.K_FOUND, 0),
                        info.progress.getInt(ImportWorker.K_PROCESSED, 0),
                        info.progress.getString(ImportWorker.K_CURRENT) ?: "",
                    )
                    WorkInfo.State.SUCCEEDED -> {
                        result.value = ImportWorker.fromData(info.outputData)
                        val missing = dao.missingTags().first().filterNot { it.id in skipped.value }
                        step.value = if (missing.isNotEmpty()) ImportStep.REVIEW else ImportStep.DONE
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        error.value = "The scan stopped before it finished. Try again."
                        step.value = ImportStep.SOURCES
                    }
                    else -> Unit
                }
            }
        }
    }

    // ---- review ----
    fun findTags(t: TrackEntity) = viewModelScope.launch {
        suggestions.value = suggestions.value + (t.id to SuggestState(loading = true))
        val state = runCatching { MusicBrainz.search(t.title, t.artist) }.fold(
            onSuccess = { SuggestState(items = it, error = if (it.isEmpty()) "No matches found" else null) },
            onFailure = { SuggestState(error = "Couldn't reach MusicBrainz") },
        )
        suggestions.value = suggestions.value + (t.id to state)
    }

    /** Saves the suggestion in the library (the file itself is not changed). */
    fun apply(t: TrackEntity, s: MusicBrainz.Suggestion) = viewModelScope.launch {
        dao.update(
            t.copy(
                title = s.title,
                artist = s.artist ?: t.artist,
                album = s.album ?: t.album,
                year = s.year ?: t.year,
                tagsMissing = false,
            ),
        )
        suggestions.value = suggestions.value - t.id
    }

    fun skip(t: TrackEntity) { skipped.value = skipped.value + t.id }

    fun finishReview() { step.value = ImportStep.DONE }

    fun restart() { step.value = ImportStep.SOURCES }

    companion object {
        /** "primary:Music/FLAC" -> "Music/FLAC", "1234-5678:Albums" -> "SD card: Albums". */
        fun folderLabel(tree: Uri): String {
            val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
            val (vol, path) = id.substringBefore(':') to id.substringAfter(':', "")
            val shown = path.ifBlank { "/" }
            return if (vol == "primary") shown else "SD card: $shown"
        }
    }
}
