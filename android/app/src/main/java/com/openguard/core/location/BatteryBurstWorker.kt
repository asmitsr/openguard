package com.openguard.core.location

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.openguard.OpenGuardApp
import java.util.concurrent.TimeUnit

/**
 * State machine behind the low-battery last-location burst
 * (see [BatteryBurst]): while the feature is enabled it re-checks the
 * battery every 15 minutes and fires the burst once the level hits 5
 * percent. Always returns success — a failed check just waits for the
 * next run. The watch has NO constraints at all: it must run even
 * offline (the fix is queued in the outbox and uploads on reconnect)
 * and on low battery.
 */
class BatteryBurstWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        val context = applicationContext
        if (!BatteryBurst.isEnabled(context)) {
            cancelWatch(context)
            return@runCatching Result.success()
        }
        // Transient read failure: keep watching, the next run re-checks.
        val (level, charging) = LiveUploader.batteryInfo(context)
            ?: return@runCatching Result.success()
        if (charging || level > BatteryBurst.RESET_LEVEL) {
            // Charging, or back above 10 percent: the discharge cycle
            // is over. Clears the sent flag; the watch keeps running
            // for the next cycle.
            BatteryBurst.reset(context)
            return@runCatching Result.success()
        }
        if (level <= BatteryBurst.BURST_THRESHOLD && !BatteryBurst.burstSent(context)) {
            // performBurst marks the burst sent; the watch keeps
            // running and the sent flag gates re-fire. A missing device
            // registration leaves it unmarked.
            BatteryBurst.performBurst(context as OpenGuardApp)
        }
        // 6-15 percent, discharging: keep watching, the next 15-minute
        // run re-checks.
        Result.success()
    }.getOrDefault(Result.success())

    companion object {
        /** Periodic 15-minute watch (the WorkManager minimum), no constraints. */
        fun enqueueWatch(context: Context) {
            val request = PeriodicWorkRequestBuilder<BatteryBurstWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                BatteryBurst.WATCH_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** One immediate check, e.g. right after enabling at or below the threshold. */
        fun enqueueOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<BatteryBurstWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun cancelWatch(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(BatteryBurst.WATCH_WORK_NAME)
        }
    }
}
