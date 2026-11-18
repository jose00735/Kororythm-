package com.koro.kororythm.downloads

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the site says about a link, before downloading. */
data class DlInfo(
    val url: String,
    val videoTitle: String,
    val artist: String?,
    val title: String,
    val uploader: String?,
    val durationSec: Int,
    val thumbnail: String?,
    val extractor: String?,
    val year: Int?,
    val best: AudioStream?,
)

/**
 * The embedded yt-dlp (Python + yt-dlp + FFmpeg packaged by youtubedl-android).
 * First use unpacks it into app storage, which takes a few seconds.
 */
object YtEngine {
    private val lock = Mutex()
    @Volatile var ready = false
        private set

    suspend fun ensure(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!ready) {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                ready = true
            }
        }
    }

    suspend fun info(context: Context, url: String): DlInfo = withContext(Dispatchers.IO) {
        ensure(context)
        val req = YoutubeDLRequest(url).addOption("--no-playlist")
        val v = YoutubeDL.getInstance().getInfo(req)
        val streams = v.formats.orEmpty().filter { (it.vcodec ?: "none") == "none" }.map {
            AudioStream(it.formatId, it.acodec, if (it.abr > 0) it.abr else it.tbr, it.asr, it.ext)
        }
        val title = v.title ?: v.fulltitle ?: url
        val (artist, track) = YtLogic.guessArtistTitle(title, v.uploader)
        DlInfo(
            url = v.webpageUrl ?: url,
            videoTitle = title,
            artist = artist,
            title = track,
            uploader = v.uploader,
            durationSec = v.duration,
            thumbnail = v.thumbnail,
            extractor = v.extractorKey ?: v.extractor,
            year = YtLogic.year(v.uploadDate),
            best = YtLogic.bestAudio(streams),
        )
    }

    /** Version of the yt-dlp copy in use, e.g. "2025.11.12". */
    fun version(context: Context): String? = runCatching {
        YoutubeDL.getInstance().versionName(context) ?: YoutubeDL.getInstance().version(context)
    }.getOrNull()

    /** Gets the newest stable yt-dlp (sites change often; this fixes most "can't download" errors). */
    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        ensure(context)
        when (YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel._STABLE)) {
            YoutubeDL.UpdateStatus.DONE -> "Updated to ${version(context) ?: "the latest version"}"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date (${version(context) ?: "?"})"
            else -> "Update finished"
        }
    }
}
