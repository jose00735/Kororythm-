package com.koro.kororythm.library

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Background import: scans every saved source. Runs when the import wizard starts a scan,
 * and every 12 hours so new files in your folders show up on their own.
 */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = Library.dao
        val sources = dao.sourcesNow()
        if (sources.isEmpty()) return Result.success(toData(ScanResult()))
        val result = Scanner(applicationContext, dao).scan(sources) { p ->
            setProgress(workDataOf(K_FOUND to p.found, K_PROCESSED to p.processed, K_CURRENT to p.current))
        }
        // New tracks get their BPM and loudness measured in the background.
        if (result.added + result.updated > 0) com.koro.kororythm.dj.AnalysisWorker.runNow(applicationContext)
        return Result.success(toData(result))
    }

    companion object {
        const val UNIQUE_IMPORT = "koro-import"
        private const val UNIQUE_RESCAN = "koro-rescan"
        const val K_FOUND = "found"
        const val K_PROCESSED = "processed"
        const val K_CURRENT = "current"
        const val K_ADDED = "added"
        const val K_UPDATED = "updated"
        const val K_UNCHANGED = "unchanged"
        const val K_DUPES = "duplicates"
        const val K_REMOVED = "removed"
        const val K_FAILED = "failed"

        fun toData(r: ScanResult): Data = workDataOf(
            K_ADDED to r.added, K_UPDATED to r.updated, K_UNCHANGED to r.unchanged,
            K_DUPES to r.duplicates, K_REMOVED to r.removed, K_FAILED to r.failed,
        )

        fun fromData(d: Data) = ScanResult(
            d.getInt(K_ADDED, 0), d.getInt(K_UPDATED, 0), d.getInt(K_UNCHANGED, 0),
            d.getInt(K_DUPES, 0), d.getInt(K_REMOVED, 0), d.getInt(K_FAILED, 0),
        )

        /** Starts (or restarts) an import now; returns the job id to watch. */
        fun runNow(context: Context): UUID {
            val req = OneTimeWorkRequestBuilder<ImportWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_IMPORT, ExistingWorkPolicy.REPLACE, req)
            return req.id
        }

        /** Keeps the library fresh: rescan every 12 h while the battery isn't low. */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<ImportWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_RESCAN, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
