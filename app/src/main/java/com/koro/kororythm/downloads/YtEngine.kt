package com.koro.kororythm.downloads

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Readable error text: yt-dlp's own "ERROR:" line, else the exception and its cause. */
fun describe(e: Throwable): String {
    val text = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString("\n")
    text.lines().lastOrNull { it.contains("ERROR") }?.let { return it.substringAfter("ERROR:").trim().take(220) }
    val root = generateSequence(e) { it.cause }.last()
    return (root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName).take(220)
}

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
                val app = context.applicationContext
                try {
                    YoutubeDL.getInstance().init(app)
                    FFmpeg.getInstance().init(app)
                } catch (e: Throwable) {
                    // A half-unpacked engine (e.g. from an earlier failed start) is wiped and unpacked again.
                    File(app.noBackupFilesDir, YoutubeDL.baseName).deleteRecursively()
                    YoutubeDL.getInstance().init(app)
                    FFmpeg.getInstance().init(app)
                }
                ready = true
            }
        }
    }

    /**
     * Reads a link without downloading: one video, or the list of a playlist (flat, so a big
     * playlist is fast). yt-dlp's progress lines go to [onLine] as they come; [processId] lets
     * [cancel] stop it. Results are written by yt-dlp to a file, one JSON object per item.
     */
    suspend fun probe(context: Context, url: String, processId: String, onLine: (String) -> Unit): Probe = withContext(Dispatchers.IO) {
        ensure(context)
        val dir = File(context.cacheDir, "ytprobe/$processId").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "items.jsonl")
        try {
            val req = YoutubeDLRequest(url)
                .addOption("--skip-download")
                .addOption("--flat-playlist")
                .addOption(if (YtLogic.hasPlaylist(url)) "--yes-playlist" else "--no-playlist")
                .addOption("--playlist-end", PLAYLIST_LIMIT)
                .addOption("--newline")
                .addCommands(listOf("--print-to-file", "video:%()j", out.absolutePath))
            onLine("yt-dlp " + req.buildCommand().joinToString(" "))
            val resp = YoutubeDL.getInstance().execute(req, processId) { _, _, line -> onLine(line) }
            resp.err?.lines()?.filter { it.isNotBlank() }?.forEach { onLine(it) }
            YtLogic.parseProbe(if (out.exists()) out.readLines() else emptyList(), url)
                ?: throw IllegalStateException("yt-dlp found nothing to download at that link")
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Stops a running probe or download. */
    fun cancel(processId: String) = runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }.getOrDefault(false)

    const val PLAYLIST_LIMIT = 300

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
