package com.koro.kororythm.genres

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.koro.kororythm.library.GenreUpdate
import com.koro.kororythm.library.Library
import com.koro.kororythm.library.LibraryDao
import com.koro.kororythm.library.MusicBrainz
import com.koro.kororythm.library.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fills in genre and sub-genre for tracks that haven't been looked up yet.
 * First, instantly, from each file's own genre; then online for the rest: MusicBrainz tags of
 * the recording, genres of its album and artist (each asked once), and iTunes as a fallback.
 * A song whose lookup fails is skipped (tried again on the next run) instead of stalling the run,
 * and results are saved in batches so the library doesn't reload after every song.
 */
class GenreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val albumCache = HashMap<String, List<Vote>>()
    private val artistCache = HashMap<String, List<Vote>>()
    private val artistIdCache = HashMap<String, String?>()

    override suspend fun doWork(): Result {
        val dao = Library.dao
        sortFileGenres(dao)

        val ids = dao.genreUncheckedIds()
        var done = 0
        var found = 0
        var failed = 0
        var failStreak = 0
        val pending = mutableListOf<GenreUpdate>()
        suspend fun flush() { if (pending.isNotEmpty()) { dao.setGenres(pending.toList()); pending.clear() } }

        for (chunk in ids.chunked(50)) {
            for (t in dao.byIds(chunk).sortedBy { (it.artist ?: "").lowercase() }) {
                if (isStopped) { flush(); return Result.success() }
                setProgress(workDataOf(K_DONE to done, K_FOUND to found, K_LEFT to ids.size - done, K_CURRENT to "${t.artist ?: "?"} – ${t.title}"))
                MusicBrainz.resetFailed()
                val (main, sub) = runCatching { lookup(t) }.getOrElse { null to null }
                if (main == null && MusicBrainz.failedSinceReset) {
                    // Offline / rate-limited: leave this one for the next run and move on.
                    failed++
                    if (++failStreak >= 6) { flush(); return Result.retry() }
                } else {
                    failStreak = 0
                    pending += GenreUpdate(t.id, main ?: t.genreMain, sub ?: t.subGenre, System.currentTimeMillis())
                    if (main != null) found++
                }
                done++
                if (pending.size >= 10) flush()
            }
        }
        flush()
        return if (failed > 0) Result.retry() else Result.success(workDataOf(K_DONE to done, K_FOUND to found))
    }

    private suspend fun lookup(t: TrackEntity): Pair<String?, String?> {
        val votes = mutableListOf<Vote>()
        GenreMap.fileGenres(t.genre).forEach { votes += Vote(it, 6.0) }
        val artist = t.artist?.takeIf { it.isNotBlank() }
        if (artist != null) {
            val q = MusicBrainz.buildQuery(t.title, artist)
            val match = MusicBrainz.get("recording/?limit=5&query=" + enc(q))?.let { GenreParse.recording(it, artist) }
            if (match != null) {
                votes += match.tags
                match.releaseGroupId?.let { id ->
                    votes += albumCache.getOrPut(id) {
                        MusicBrainz.get("release-group/$id?inc=genres+tags")?.let { GenreParse.entityGenres(it, 2.0) }.orEmpty()
                    }
                }
            }
            val artistId = match?.artistId ?: artistIdCache.getOrPut(artist.lowercase()) {
                MusicBrainz.get("artist/?limit=3&query=" + enc("artist:\"${artist.replace("\"", "")}\""))?.let { GenreParse.artistId(it, artist) }
            }
            artistId?.let { id ->
                votes += artistCache.getOrPut(id) {
                    MusicBrainz.get("artist/$id?inc=genres+tags")?.let { GenreParse.entityGenres(it, 1.0) }.orEmpty()
                }
            }
            if (votes.none { GenreMap.mainOf(it.name) != null }) {
                itunes("$artist ${t.title}")?.let { GenreParse.itunesGenre(it, artist) }?.let { votes += Vote(it, 2.0) }
            }
        }
        return GenreMap.classify(votes)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun itunes(term: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://itunes.apple.com/search?entity=song&limit=5&term=" + enc(term)).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
        }.getOrNull()
    }

    companion object {
        const val UNIQUE = "koro-genres"
        const val K_DONE = "done"
        const val K_FOUND = "found"
        const val K_LEFT = "left"
        const val K_CURRENT = "current"

        /**
         * Sorts every song whose file already says its genre (no network, a second at most).
         * A song whose file genre gives a sub-genre too needs no online lookup.
         */
        suspend fun sortFileGenres(dao: LibraryDao) {
            val now = System.currentTimeMillis()
            val rows = dao.unsortedFileGenres().mapNotNull { t ->
                val (main, sub) = GenreMap.fromFile(t.genre)
                main?.let { GenreUpdate(t.id, it, sub, if (sub != null) now else t.genreChecked) }
            }
            rows.chunked(200).forEach { dao.setGenres(it) }
        }

        /** Looks up genres for every track not checked yet. */
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<GenreWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
