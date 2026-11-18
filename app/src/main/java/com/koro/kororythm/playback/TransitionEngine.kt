package com.koro.kororythm.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.koro.kororythm.AudioBus
import com.koro.kororythm.dj.OutroFxProcessor
import com.koro.kororythm.dj.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DJ transitions between songs. The "ghost" player keeps the outgoing song going through its
 * effect (OutroFxProcessor) while the main player moves to the next song.
 * Runs on the service's main thread, like both players.
 */
@UnstableApi
class TransitionEngine(
    private val main: ExoPlayer,
    private val ghost: ExoPlayer,
    private val scope: CoroutineScope,
    /** Main player volume when no ramp is running (volume levelling). */
    private val restingVolume: () -> Float,
) {
    private var job: Job? = null
    val busy: Boolean get() = job?.isActive == true

    /** Watches for the end of songs and starts the transition early enough (auto-mix). */
    fun startAutoMix() = scope.launch {
        while (isActive) {
            delay(100)
            val s = AudioBus.dj.value
            if (!s.autoMix || s.transition == Transition.CUT || busy || AudioBus.bitPerfectActive.value) continue
            if (!main.isPlaying || !main.hasNextMediaItem() || main.repeatMode == Player.REPEAT_MODE_ONE) continue
            val dur = main.duration.takeIf { it > 0 } ?: continue
            val remaining = dur - main.currentPosition
            val lead = (if (s.transition.overlap) s.durationMs.toLong() else 900L) + 400L
            if (remaining in 1..lead && dur > lead * 3) next()
        }
    }

    /** Handles Next. Returns false when a plain skip should happen instead. */
    fun next(): Boolean {
        val s = AudioBus.dj.value
        val mode = s.transition
        if (mode == Transition.CUT || busy || !main.isPlaying || !main.hasNextMediaItem() || AudioBus.bitPerfectActive.value) return false
        val item = main.currentMediaItem ?: return false
        val dur = s.durationMs.coerceIn(300, 10_000)
        val bpm = AudioBus.bpm.value
        AudioBus.transition.value = mode
        job = scope.launch {
            try {
                if (mode.historyMs > 0) dropWithHistory(mode, dur, bpm, item)
                else if (mode.overlap) overlap(mode, dur, bpm, item)
                else brake(mode, dur, bpm, item)
            } catch (e: Exception) {
                if (main.currentMediaItem == item) main.seekToNextMediaItem()
            } finally {
                endGhost()
                main.volume = restingVolume()
                AudioBus.transition.value = null
            }
        }
        return true
    }

    private suspend fun startGhost(mode: Transition, dur: Int, bpm: Float?, item: androidx.media3.common.MediaItem, fromMs: Long): Boolean {
        OutroFxProcessor.arm(mode, dur, bpm)
        ghost.setMediaItem(item, fromMs.coerceAtLeast(0))
        ghost.volume = main.volume
        ghost.playbackParameters = main.playbackParameters
        ghost.prepare()
        return withTimeoutOrNull(2000) {
            while (ghost.playbackState != Player.STATE_READY) delay(5)
            true
        } ?: false
    }

    private fun endGhost() {
        ghost.stop()
        ghost.clearMediaItems()
        OutroFxProcessor.disarm()
    }

    /** Fades the main player's own output down fast (its AudioTrack volume, no re-buffering). */
    private suspend fun duckMain(ms: Long) {
        val from = main.volume
        val steps = 6
        for (k in 1..steps) {
            main.volume = from * (1f - k / steps.toFloat())
            delay(ms / steps)
        }
    }

    /** Crossfade, echo out, filter sweep: both songs play together for [dur]. */
    private suspend fun overlap(mode: Transition, dur: Int, bpm: Float?, item: androidx.media3.common.MediaItem) {
        val lead = 350L
        val at = main.currentPosition + lead
        if (!startGhost(mode, dur, bpm, item, at)) { main.seekToNextMediaItem(); return }
        while (main.isPlaying && main.currentPosition < at) delay(4) // line the ghost up with what you hear
        ghost.play()
        delay(140) // the ghost's sound reaches the speaker
        duckMain(90)
        AudioBus.pendingIntro.set(mode to dur)
        main.seekToNextMediaItem()
        main.volume = restingVolume() // the new song's first samples are already faded in
        delay(dur + 300L)
    }

    /** Vinyl brake: the old song slows to a stop, then the next one drops. */
    private suspend fun brake(mode: Transition, dur: Int, bpm: Float?, item: androidx.media3.common.MediaItem) {
        val lead = 350L
        val at = main.currentPosition + lead
        if (!startGhost(mode, dur, bpm, item, at)) { main.seekToNextMediaItem(); return }
        while (main.isPlaying && main.currentPosition < at) delay(4)
        ghost.play()
        delay(140)
        duckMain(60)
        main.pause()
        main.volume = restingVolume()
        delay(dur.toLong())
        main.seekToNextMediaItem()
        main.play()
        delay(150)
    }

    /** Rewind and scratch: the ghost first collects the last seconds, then plays them backwards. */
    private suspend fun dropWithHistory(mode: Transition, dur: Int, bpm: Float?, item: androidx.media3.common.MediaItem) {
        val from = main.currentPosition - mode.historyMs
        if (!startGhost(mode, dur, bpm, item, from)) { main.seekToNextMediaItem(); return }
        ghost.play()
        val started = withTimeoutOrNull(3000) {
            while (OutroFxProcessor.current?.started != true) delay(5)
            true
        } ?: false
        if (!started) { main.seekToNextMediaItem(); return }
        delay(130)
        duckMain(40)
        main.pause()
        main.volume = restingVolume()
        delay(dur.toLong())
        main.seekToNextMediaItem()
        main.play()
        delay(150)
    }
}
