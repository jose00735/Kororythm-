package com.koro.kororythm.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.koro.kororythm.R
import com.koro.kororythm.library.Library
import com.koro.kororythm.library.Scanner
import com.koro.kororythm.tags.Cover
import com.koro.kororythm.tags.TagData
import com.koro.kororythm.tags.TagIo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * One download: yt-dlp fetches the best audio, FFmpeg converts it (MP3 V0) or keeps it as is,
 * Kororythm tags it, saves it to Music/Kororythm and adds it to the library.
 * Runs in the background with a notification, so it keeps going if you leave the app.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val processId get() = id.toString()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val d = inputData
        val url = d.getString(K_URL) ?: return@withContext Result.failure(err("No link"))
        val format = runCatching { DlFormat.valueOf(d.getString(K_FORMAT)!!) }.getOrDefault(DlFormat.MP3_V0)
        val title = d.getString(K_TITLE) ?: "Download"
        val artist = d.getString(K_ARTIST)
        runCatching { setForeground(foreground(title, 0f)) }
        log("Job $id: \"$title\"${artist?.let { " by $it" } ?: ""}")
        log("Link: $url")
        log("Format: ${format.label}  ·  ${d.getString(K_QUALITY) ?: ""}")
        val work = File(applicationContext.cacheDir, "ytdl/$processId").apply { deleteRecursively(); mkdirs() }
        try {
            stage("Starting engine", 0f)
            log(if (YtEngine.ready) "Engine already unpacked" else "Unpacking engine (Python, yt-dlp, FFmpeg)…")
            YtEngine.ensure(applicationContext)
            log("yt-dlp ${YtEngine.version(applicationContext) ?: "(bundled)"} ready")

            val req = YoutubeDLRequest(url)
                .addOption("--no-playlist")
                .addOption("--no-mtime")
                .addOption("-f", "bestaudio/best")
                .addOption("-x")
                .addOption("-o", File(work, "%(id)s.%(ext)s").absolutePath)
            if (format == DlFormat.MP3_V0) {
                req.addOption("--audio-format", "mp3").addOption("--audio-quality", "0")
            } else {
                req.addOption("--embed-metadata")
            }
            req.addOption("--newline")
            req.addOption("-v") // debug info goes to stderr, shown in the log
            log("Running: yt-dlp " + req.buildCommand().joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it })
            stage("Downloading", 0f)
            var lastLoggedPct = -10
            var lastNotified = -1
            // execute() blocks; when the job is cancelled, kill the yt-dlp process so it returns.
            val watcher = launch { try { awaitCancellation() } finally { if (isStopped) YoutubeDL.getInstance().destroyProcessById(processId) } }
            val resp = try { YoutubeDL.getInstance().execute(req, processId) { progress, _, line ->
                val p = (progress / 100f).coerceIn(0f, 1f)
                val stageName = if (line.contains("ExtractAudio") || line.contains("ffmpeg", true)) "Converting" else "Downloading"
                setProgressAsync(workDataOf(K_STAGE to stageName, K_PROGRESS to p, K_LINE to line.take(160), K_TITLE to title))
                val pct = (p * 100).toInt()
                val isProgress = YtLogic.progressOf(line) != null
                // every non-progress line, and progress every 5 %
                if (!isProgress || pct >= lastLoggedPct + 5 || pct >= 100) {
                    if (isProgress) lastLoggedPct = pct
                    log(line, "yt-dlp")
                }
                if (pct / 5 != lastNotified) {
                    lastNotified = pct / 5
                    runCatching { setForegroundAsync(foreground(title, p)) }
                }
            } } finally { watcher.cancel() }
            resp.err?.takeIf { it.isNotBlank() }?.let { log(it, "yt-dlp:err") }
            log("yt-dlp exited with code ${resp.exitCode} after ${resp.elapsedTime / 1000.0} s")
            if (resp.exitCode != 0) return@withContext Result.failure(err(lastError(resp.err)))

            val out = work.listFiles()?.filter { it.isFile && it.length() > 0 && it.extension.lowercase() !in setOf("part", "ytdl", "jpg", "webp", "png") }
                ?.maxByOrNull { it.length() } ?: return@withContext Result.failure(err("yt-dlp finished but no audio file was made"))

            stage("Tagging", 1f)
            val ext = out.extension.lowercase()
            log("Output: ${out.name} (${"%.2f".format(out.length() / 1048576.0)} MB)")
            val final = if (ext == "mp3") {
                val cover = d.getString(K_THUMB)?.let { fetchCover(it) }
                log(if (cover != null) "Cover: thumbnail cropped to a square (${cover.size / 1024} KB)" else "Cover: no thumbnail available")
                log("Writing ID3v2.4 tags: title, artist${if (d.getString(K_ALBUM) != null) ", album" else ""}, source note ${d.getString(K_NOTE)}")
                val tagged = File(work, "tagged.mp3")
                TagIo.write(
                    out, tagged,
                    TagData(
                        title = title, artist = artist, album = d.getString(K_ALBUM),
                        year = d.getInt(K_YEAR, 0).takeIf { it > 0 },
                        cover = cover?.let { Cover(it, "image/jpeg") },
                        sourceNote = d.getString(K_NOTE),
                    ),
                )
                tagged
            } else out.also { log("Kept the original stream; metadata embedded by yt-dlp") }

            stage("Saving to ${MusicStore.FOLDER}", 1f)
            val name = YtLogic.fileName(artist, title, ext)
            val uri = MusicStore.save(applicationContext, final, name, YtLogic.mimeFor(ext))
                ?: return@withContext Result.failure(err("Couldn't save into the Music folder"))
            log("Saved as ${MusicStore.FOLDER}/$name → $uri")

            stage("Adding to library", 1f)
            val track = Scanner(applicationContext, Library.dao).addOne(uri, name, final.length())
            // Opus/AAC files carry the raw video title; show the cleaned-up one in the library.
            if (track != null && ext != "mp3" && (track.title != title || track.artist != artist)) {
                Library.dao.update(track.copy(title = title, artist = artist ?: track.artist, tagsMissing = false))
            }
            log(track?.let { "Library track #${it.id}: ${it.codec} ${it.bitrateKbps ?: "?"} kbps, ${it.durationMs / 1000} s" } ?: "Saved, but the library couldn't read it yet (it'll show up on the next scan)")
            com.koro.kororythm.dj.AnalysisWorker.runNow(applicationContext)
            log("Done ✓")
            Result.success(
                workDataOf(K_TRACK_ID to (track?.id ?: -1L), K_SAVED_AS to "${MusicStore.FOLDER}/$name", K_TITLE to title),
            )
        } catch (e: YoutubeDL.CanceledException) {
            log("Cancelled")
            Result.failure(err("Cancelled"))
        } catch (e: Exception) {
            if (isStopped) { log("Cancelled"); Result.failure(err("Cancelled")) } else {
                log("${e.javaClass.simpleName}: ${generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString("\n")}", "error")
                Result.failure(err(describe(e)))
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun stage(name: String, p: Float) = setProgressAsync(workDataOf(K_STAGE to name, K_PROGRESS to p, K_TITLE to inputData.getString(K_TITLE)))

    private fun log(line: String, tag: String = "koro") = runCatching { DownloadLog.add(applicationContext, id, line, tag) }

    private fun err(msg: String) = workDataOf(K_ERROR to msg, K_TITLE to inputData.getString(K_TITLE))

    /** yt-dlp's useful message is its last "ERROR:" line. */
    private fun lastError(text: String?): String =
        text?.lines()?.lastOrNull { it.contains("ERROR", true) }?.substringAfter("ERROR:")?.trim()?.take(200)
            ?: text?.lines()?.lastOrNull { it.isNotBlank() }?.take(200) ?: "Download failed"

    /** Thumbnail as a square JPEG cover (YouTube thumbnails are 16:9 with the art in the middle). */
    private fun fetchCover(url: String): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        val bytes = c.inputStream.use { it.readBytes() }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        val side = minOf(bmp.width, bmp.height)
        val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val scaled = if (side > 1000) Bitmap.createScaledBitmap(sq, 1000, 1000, true) else sq
        ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }.getOrNull()

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(inputData.getString(K_TITLE) ?: "Download", 0f)

    private fun foreground(title: String, p: Float): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, (p * 100).toInt(), p <= 0f)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        val nid = 4000 + (id.hashCode() and 0xFFF)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(nid, n)
    }

    companion object {
        const val TAG = "koro-download"
        private const val CHANNEL = "downloads"
        const val K_URL = "url"; const val K_FORMAT = "format"; const val K_TITLE = "title"; const val K_ARTIST = "artist"
        const val K_ALBUM = "album"; const val K_YEAR = "year"; const val K_THUMB = "thumb"; const val K_NOTE = "note"
        const val K_QUALITY = "quality"
        const val K_STAGE = "stage"; const val K_PROGRESS = "progress"; const val K_LINE = "line"
        const val K_ERROR = "error"; const val K_TRACK_ID = "trackId"; const val K_SAVED_AS = "savedAs"

        fun enqueue(context: Context, info: DlInfo, format: DlFormat, title: String, artist: String?, album: String?): UUID {
            val data: Data = workDataOf(
                K_URL to info.url, K_FORMAT to format.name, K_TITLE to title, K_ARTIST to artist, K_ALBUM to album,
                K_YEAR to (info.year ?: 0), K_THUMB to info.thumbnail,
                K_NOTE to YtLogic.sourceNote(info.extractor, info.best),
                K_QUALITY to YtLogic.qualityLabel(info.best, format),
            )
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .addTag(TAG)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(req)
            DownloadLog.add(context, req.id, "Queued")
            return req.id
        }
    }
}
