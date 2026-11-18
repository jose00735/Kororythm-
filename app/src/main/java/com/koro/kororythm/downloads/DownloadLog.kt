package com.koro.kororythm.downloads

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Per-download log, shown by the LOG button: yt-dlp's own output (verbose) plus what
 * Kororythm does around it. Plain text files in the cache, one per job.
 */
object DownloadLog {
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private const val MAX_BYTES = 512 * 1024

    private fun dir(context: Context) = File(context.cacheDir, "ytdl-logs").apply { mkdirs() }
    fun file(context: Context, id: UUID) = File(dir(context), "$id.log")

    @Synchronized
    fun add(context: Context, id: UUID, line: String, tag: String = "koro") {
        val f = file(context, id)
        if (f.length() > MAX_BYTES) return
        val stamp = clock.format(Date())
        f.appendText(line.lines().filter { it.isNotBlank() }.joinToString("") { "$stamp [$tag] $it\n" })
    }

    fun read(context: Context, id: UUID): String = runCatching { file(context, id).readText() }.getOrDefault("")

    /** Deletes logs except those of [keep] (still running or queued jobs). */
    fun prune(context: Context, keep: Set<UUID>) {
        dir(context).listFiles()?.forEach { f ->
            val id = runCatching { UUID.fromString(f.nameWithoutExtension) }.getOrNull()
            if (id == null || id !in keep) f.delete()
        }
    }
}
