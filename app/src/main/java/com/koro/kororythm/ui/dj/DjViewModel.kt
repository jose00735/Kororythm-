package com.koro.kororythm.ui.dj

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koro.kororythm.AudioBus
import com.koro.kororythm.devices.OutputKind
import com.koro.kororythm.dj.AnalysisWorker
import com.koro.kororythm.dj.DjSettings
import com.koro.kororythm.dj.DjStore
import com.koro.kororythm.dj.LiveFx
import com.koro.kororythm.dj.Transition
import com.koro.kororythm.library.HotCue
import com.koro.kororythm.library.Library
import com.koro.kororythm.playback.PlayerActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DjUi(
    val dj: DjSettings = DjSettings(),
    val fx: LiveFx = LiveFx(),
    val bpm: Float? = null,
    val running: Transition? = null,
    val trackId: Long? = null,
    val cues: List<HotCue> = emptyList(),
    val analyzed: Int = 0,
    val total: Int = 0,
    val dacConnected: Boolean = false,
    val bitPerfectWanted: Boolean = false,
    val bitPerfectActive: Boolean = false,
    val bitPerfectNote: String? = null,
)

class DjViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = Library.dao
    private val currentUri = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val trackId = currentUri.map { u -> u?.let { dao.byUri(it)?.id } }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val cues = trackId.flatMapLatest { id -> id?.let { dao.cues(it) } ?: flowOf(emptyList()) }

    private val bitPerfect = combine(AudioBus.output, AudioBus.bitPerfectWanted, AudioBus.bitPerfectActive, AudioBus.bitPerfectNote) { o, w, a, n ->
        listOf(o.kind == OutputKind.USB_DAC, w, a, n)
    }

    val ui: StateFlow<DjUi> = combine(
        combine(AudioBus.dj, AudioBus.fx, AudioBus.bpm, AudioBus.transition) { d, f, b, t -> DjUi(dj = d, fx = f, bpm = b, running = t) },
        combine(trackId, cues) { id, c -> id to c },
        combine(dao.analyzedCount(), dao.count()) { a, t -> a to t },
        bitPerfect,
    ) { base, (id, c), (a, t), bp ->
        base.copy(
            trackId = id, cues = c, analyzed = a, total = t,
            dacConnected = bp[0] as Boolean, bitPerfectWanted = bp[1] as Boolean,
            bitPerfectActive = bp[2] as Boolean, bitPerfectNote = bp[3] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DjUi())

    fun setCurrent(uri: String?) { currentUri.value = uri }

    // transitions
    fun setTransition(t: Transition) = DjStore.update { it.copy(transition = t, durationMs = t.defaultMs.coerceAtLeast(300)) }
    fun setDuration(ms: Int) = DjStore.update { it.copy(durationMs = ms) }
    fun toggleAutoMix() = DjStore.update { it.copy(autoMix = !it.autoMix) }
    fun toggleLeveling() = DjStore.update { it.copy(leveling = !it.leveling) }

    // tempo
    fun setTempo(v: Float, player: PlayerActions) {
        val t = (Math.round(v * 1000) / 1000f).coerceIn(0.92f, 1.08f)
        DjStore.update { it.copy(tempo = t) }
        player.setTempo(t, AudioBus.dj.value.keyLock)
    }

    fun toggleKeyLock(player: PlayerActions) {
        DjStore.update { it.copy(keyLock = !it.keyLock) }
        player.setTempo(AudioBus.dj.value.tempo, AudioBus.dj.value.keyLock)
    }

    // FX pads
    fun fx(transform: (LiveFx) -> LiveFx) = AudioBus.fx.update(transform)

    fun setRepeat(beats: Float) = AudioBus.fx.update { if (it.repeatBeats == beats) it.copy(repeatBeats = 0f) else it.copy(repeatBeats = beats) }

    // hot cues: tap an empty pad to save the position, tap a saved one to jump
    fun cue(slot: Int, positionMs: Long, player: PlayerActions) {
        val id = ui.value.trackId ?: return
        val existing = ui.value.cues.firstOrNull { it.slot == slot }
        if (existing != null) player.seekTo(existing.positionMs)
        else viewModelScope.launch { dao.setCue(HotCue(id, slot, positionMs)) }
    }

    fun clearCue(slot: Int) {
        val id = ui.value.trackId ?: return
        viewModelScope.launch { dao.clearCue(id, slot) }
    }

    fun analyze() = AnalysisWorker.runNow(getApplication())

    fun toggleBitPerfect() = DjStore.setBitPerfect(!AudioBus.bitPerfectWanted.value)
}
