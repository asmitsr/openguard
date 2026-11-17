package com.openguard.core.security

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic offline auto-lock check (see [OfflineAutoLock]). Always returns
 * success: a failed check just waits for the next 15-minute run, while a
 * retry with backoff would delay the check. Scheduling lives in
 * [OfflineAutoLock], so this worker has no companion.
 */
class OfflineAutoLockWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching { OfflineAutoLock.check(applicationContext) }
        return Result.success()
    }
}
