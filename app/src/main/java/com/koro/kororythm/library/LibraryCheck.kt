package com.koro.kororythm.library

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Manual refresh: which library songs no longer have a file (or can't be reached any more). */
object LibraryCheck {

    data class Missing(val track: TrackEntity, val reason: String)

    suspend fun missing(context: Context, tracks: List<TrackEntity>, onProgress: (Int) -> Unit = {}): List<Missing> =
        withContext(Dispatchers.IO) {
            val gate = Semaphore(6)
            var done = 0
            coroutineScope {
                tracks.map { t ->
                    async {
                        gate.withPermit {
                            val r = reason(context, Uri.parse(t.uri))
                            synchronized(this@LibraryCheck) { done++; if (done % 25 == 0) onProgress(done) }
                            r?.let { Missing(t, it) }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }

    /** Null if the file is there and readable. */
    private fun reason(context: Context, uri: Uri): String? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { null } ?: "not found"
    } catch (e: FileNotFoundException) {
        "file not found"
    } catch (e: SecurityException) {
        "no permission any more"
    } catch (e: IllegalArgumentException) {
        "file not found"
    } catch (e: Exception) {
        e.javaClass.simpleName
    }
}
