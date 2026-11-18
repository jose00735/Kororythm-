package com.koro.kororythm.genres

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.koro.kororythm.library.Library
import com.koro.kororythm.library.MusicBrainz
import com.koro.kororythm.library.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fills in genre and sub-genre for tracks that haven't been looked up yet:
 * the file's own genre, MusicBrainz tags of the recording, genres of its album and artist,
 * and iTunes' genre as a fallback. Albums and artists are asked once each per run.
 */
class GenreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val albumCache = HashMap<String, List<Vote>>()
    private val artistCache = HashMap<String, List<Vote>>()
    private val artistIdCache = HashMap<String, String?>()

    override suspend fun doWork(): Result {
        val dao = Library.dao
        var done = 0
        var found = 0
        while (!isStopped) {
            val batch = dao.genreUnchecked(40)
            if (batch.isEmpty()) break
            for (t in batch) {
                if (isStopped) break
                val (main, sub) = runCatching { lookup(t) }.getOrElse { null to null }
                // Offline or rate-limited: leave it for the next run instead of marking it "no genre".
                if (main == null && MusicBrainz.lastFailed) return Result.retry()
                dao.setGenre(t.id, main ?: t.genreMain, sub ?: t.subGenre, System.currentTimeMillis())
                done++
                if (main != null) found++
                setProgress(workDataOf(K_DONE to done, K_FOUND to found, K_CURRENT to "${t.artist ?: "?"} – ${t.title}"))
            }
        }
        return Result.success(workDataOf(K_DONE to done, K_FOUND to found))
    }

    private suspend fun lookup(t: TrackEntity): Pair<String?, String?> {
        val votes = mutableListOf<Vote>()
        t.genre?.split(';', '/', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { votes += Vote(it, 6.0) }
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
        const val K_CURRENT = "current"

        /** Looks up genres for every track not checked yet (keeps going if already running). */
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<GenreWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
