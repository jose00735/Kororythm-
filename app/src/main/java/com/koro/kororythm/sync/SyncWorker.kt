package com.koro.kororythm.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.koro.kororythm.R
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/**
 * Syncs with a paired PC in the background (kept alive with a notification). Started from the
 * Sync screen with a PC, or by the auto-sync schedule, which looks for any paired PC nearby.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = SyncStore(applicationContext)
        val fp = inputData.getString(K_PC)
        val pcs = store.pcs()
        val pc = if (fp != null) pcs.firstOrNull { it.fp == fp } else {
            // auto-sync: a paired PC answering on this network (its address may have changed)
            val near = runCatching { SyncClient.discover() }.getOrDefault(emptyList())
            near.firstNotNullOfOrNull { h -> pcs.firstOrNull { it.fp == h.fp }?.copy(host = h.host, port = h.port) }
        } ?: return Result.success()
        runCatching { setForeground(foreground(pc.name, 0)) }
        val failed = kotlinx.coroutines.coroutineScope {
            val job = launch {
                var last = -1
                SyncBus.ui.collect { u ->
                    val pct = if (u.totalBytes > 0) (u.bytes * 100 / u.totalBytes).toInt() else 0
                    if (pct / 5 != last / 5) { last = pct; runCatching { setForegroundAsync(foreground(pc.name, pct)) } }
                }
            }
            try {
                SyncEngine(applicationContext, store).run(pc)
                false
            } catch (e: Exception) {
                true
            } finally {
                job.cancel()
            }
        }
        return when {
            !failed -> Result.success()
            fp == null && runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("PC", 0)

    private fun foreground(pc: String, pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sync with PC", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Syncing with $pc")
            .setContentText(SyncBus.ui.value.current ?: SyncBus.ui.value.message)
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NID, n)
    }

    companion object {
        private const val K_PC = "pc"
        private const val CHANNEL = "sync"
        private const val NID = 4711
        const val NAME = "sync-now"
        private const val AUTO = "sync-auto"

        fun start(context: Context, pc: PairedPc) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf(K_PC to pc.fp)).build(),
            )
        }

        /** Every few hours, on Wi-Fi (unmetered) while charging. */
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(AUTO); return }
            val c = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build()
            wm.enqueueUniquePeriodicWork(AUTO, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS).setConstraints(c).build())
        }
    }
}
