package com.openguard.core.security

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic dead-man's switch check (see [DeadManSwitch]), shared with
 * the failed-attempt wipe's unlock reset (see [FailedAttemptWipe]).
 * Always returns success: a failed check just waits for the next
 * 15-minute run, while a retry with backoff would delay the check.
 * Scheduling lives in [DeadManSwitch], so this worker has no companion.
 */
class DeadManSwitchWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching { DeadManSwitch.check(applicationContext) }
        runCatching { FailedAttemptWipe.checkUnlockReset(applicationContext) }
        return Result.success()
    }
}
