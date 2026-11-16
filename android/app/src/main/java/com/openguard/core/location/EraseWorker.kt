package com.openguard.core.location

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.openguard.core.security.DeviceAdminManager
import com.openguard.core.security.EraseStore

/**
 * Backstop for the erase countdown: EraseCountdown enqueues this worker
 * with a 60-second delay when an erase starts. If the countdown activity
 * (or the whole process) died before the deadline, the worker wipes when
 * the deadline has passed. If the owner aborted, there is no pending
 * erase and this is a no-op. The deadline is re-checked here because a
 * WorkManager delay is a minimum, never exact.
 */
class EraseWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pending = EraseStore.pending(applicationContext) ?: return Result.success()
        if (System.currentTimeMillis() < pending.deadlineMs) return Result.success()
        DeviceAdminManager.wipeData(applicationContext)
        return Result.success()
    }
}
