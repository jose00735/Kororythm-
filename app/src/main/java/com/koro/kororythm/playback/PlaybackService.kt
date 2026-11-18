package com.koro.kororythm.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.koro.kororythm.AudioBus
import com.koro.kororythm.MainActivity
import com.koro.kororythm.dj.LiveFxProcessor
import com.koro.kororythm.dj.OutroFxProcessor
import com.koro.kororythm.dsp.KoroDspProcessor
import com.koro.kororythm.dsp.SpectrumTap
import com.koro.kororythm.library.Library
import com.koro.kororythm.widget.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Background playback: the main ExoPlayer inside a MediaSessionService (lock screen,
 * notification, Bluetooth buttons), a second "ghost" player for DJ transitions, volume
 * levelling, bit-perfect DAC output and the home-screen widget.
 *
 * Main audio path: decode -> EQ/Boost + transition fade-in -> FX pads -> visualizer tee -> AudioTrack
 * (or, in bit-perfect hi-res mode, straight to a float AudioTrack with nothing in between).
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var main: ExoPlayer
    private lateinit var ghost: ExoPlayer
    private lateinit var engine: TransitionEngine
    private lateinit var bitPerfect: BitPerfectController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trackGainDb: Float? = null

    override fun onCreate() {
        super.onCreate()
        bitPerfect = BitPerfectController(this)

        val mainRenderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val normal = DefaultAudioSink.Builder(context)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            KoroDspProcessor(main = true),
                            LiveFxProcessor(),
                            TeeAudioProcessor(SpectrumTap),
                        ),
                    )
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
                val hiRes = DefaultAudioSink.Builder(context).setEnableFloatOutput(true).build()
                return SwitchingAudioSink.create(normal, hiRes) { bitPerfect.wantFloat }
            }
        }
        val ghostRenderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(KoroDspProcessor(main = false), OutroFxProcessor()))
                    .build()
        }
        val musicAttrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

        main = ExoPlayer.Builder(this, mainRenderers)
            .setAudioAttributes(musicAttrs, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true) // unplugging headphones pauses
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // The ghost only plays the tail of the outgoing song; it must never take audio focus.
        ghost = ExoPlayer.Builder(this, ghostRenderers)
            .setAudioAttributes(musicAttrs, /* handleAudioFocus= */ false)
            .build()

        engine = TransitionEngine(main, ghost, scope) { restingVolume() }
        engine.startAutoMix()

        main.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                AudioBus.playing.value = isPlaying
                publishNowPlaying()
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) SpectrumTap.clear()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                onTrackChanged(mediaItem)
                publishNowPlaying()
            }
        })

        // Next from anywhere (app, notification, headset, widget) goes through the DJ engine.
        val sessionPlayer = object : ForwardingPlayer(main) {
            override fun seekToNext() { if (!engine.next()) super.seekToNext() }
            override fun seekToNextMediaItem() { if (!engine.next()) super.seekToNextMediaItem() }
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, sessionPlayer)
            .setCallback(SessionCallback)
            .setSessionActivity(openApp)
            .build()

        // Volume levelling follows the setting; bit-perfect follows the switch and the DAC.
        scope.launch {
            AudioBus.dj.collect { if (!engine.busy) main.volume = restingVolume() }
        }
        scope.launch {
            combine(AudioBus.bitPerfectWanted, AudioBus.output) { w, o -> w to o }.distinctUntilChanged().collect {
                refreshBitPerfect()
            }
        }
    }

    private fun restingVolume(): Float {
        if (AudioBus.bitPerfectActive.value) return 1f
        val g = trackGainDb
        // Levelling only turns loud songs down (it never boosts, so it can't clip).
        return if (AudioBus.dj.value.leveling && g != null) 10f.pow(g.coerceAtMost(0f) / 20f) else 1f
    }

    private fun onTrackChanged(item: MediaItem?) {
        val uri = item?.mediaId
        scope.launch {
            val row = uri?.let { runCatching { Library.dao.byUri(it) }.getOrNull() }
            AudioBus.bpm.value = row?.bpm
            trackGainDb = row?.gainDb
            if (!engine.busy) main.volume = restingVolume()
            refreshBitPerfect()
        }
    }

    /** Applies bit-perfect for the current song; rebuilds the audio path if the format changed. */
    private fun refreshBitPerfect() {
        val info = main.currentMediaItem?.let { TrackInfo.fromMediaItem(it) }
        val rate = main.audioFormat?.sampleRate?.takeIf { it > 0 } ?: info?.sampleRate
        val rebuild = bitPerfect.update(main.audioAttributes.audioAttributesV21.audioAttributes, rate, info?.bits)
        main.volume = restingVolume()
        if (rebuild && main.mediaItemCount > 0) {
            val pos = main.currentPosition
            val idx = main.currentMediaItemIndex
            val play = main.playWhenReady
            main.stop()
            main.seekTo(idx, pos)
            main.prepare()
            main.playWhenReady = play
        }
    }

    private fun publishNowPlaying() {
        val info = main.currentMediaItem?.let { TrackInfo.fromMediaItem(it) }
        NowPlaying.update(this, info?.title, info?.artist, main.isPlaying)
    }

    /** Items sent from the UI lose their URI on the way in; rebuild it from the mediaId. */
    private object SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri ?: Uri.parse(item.mediaId)
                item.buildUpon().setUri(uri).build()
            }.toMutableList(),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            release()
        }
        ghost.release()
        main.release()
        session = null
        AudioBus.playing.value = false
        AudioBus.bitPerfectActive.value = false
        NowPlaying.update(this, null, null, false)
        super.onDestroy()
    }
}
