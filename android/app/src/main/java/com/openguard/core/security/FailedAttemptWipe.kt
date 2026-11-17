package com.openguard.core.security

import android.app.KeyguardManager
import android.content.Context
import androidx.core.content.edit
import com.openguard.OpenGuardApp
import com.openguard.core.location.UploadWorker

/**
 * Failed-attempt wipe cap: after N failed REAL-keyguard unlock attempts,
 * the device wipes itself through the same 60-second abortable
 * EraseCountdown as remote erase.
 *
 * Counting happens in [onFailedAttempt], called from
 * OpenGuardDeviceAdmin.onPasswordFailed. The counter resets when the
 * owner unlocks, via [checkUnlockReset], which the shared 15-minute
 * worker calls on every run.
 *
 * State lives in SharedPreferences file "openguard_failed_attempt_wipe".
 * The eSIM flag is keyed by commandId and consumed exactly once by
 * DeviceAdminManager.wipeData, so an aborted wipe-cap countdown can
 * never cause a later remote erase to wipe eSIM.
 *
 * Honest limits:
 * - Counts REAL keyguard failures only. Decoy PIN failures never reach
 *   the system keyguard and are never counted; the decoy has its own
 *   lockout.
 * - The counter resets on the next 15-minute worker run after a
 *   successful unlock, so a failure count can linger up to 15 minutes
 *   after the owner unlocks.
 * - onPasswordFailed only fires while device admin is active; without
 *   admin there is no counting and no wipe.
 * - No screen lock means no failed attempts, so the cap can never fire.
 * - WIPE_EUICC destroys eSIM profiles; they cannot be restored from
 *   the phone. It is a separate explicit toggle, default off.
 * - The wipe uses the same 60-second EraseCountdown as remote erase:
 *   abort needs the device credential, so a thief cannot abort it but
 *   a returning owner can.
 * - The failed_unlock_attempt event type needs migration 007 applied
 *   to the server database before this ships, or the server rejects
 *   the whole security-event batch on the unknown type.
 */
object FailedAttemptWipe {

    private const val PREFS_NAME = "openguard_failed_attempt_wipe"
    private const val KEY_ENABLED = "failed_attempt_wipe_enabled"
    private const val KEY_ATTEMPT_CAP = "failed_attempt_wipe_cap"
    private const val KEY_WIPE_ESIM = "failed_attempt_wipe_esim"
    private const val KEY_FAILED_COUNT = "failed_attempt_wipe_count"
    private const val KEY_PENDING_WIPE_EUICC_COMMAND_ID = "pending_wipe_euicc_command_id"
    private const val KEY_PENDING_WIPE_EUICC = "pending_wipe_euicc"

    private const val DEFAULT_ATTEMPT_CAP = 10
    private val VALID_CAPS = setOf(5, 10, 15, 20)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        // Both enabling and disabling reset the count: never fire on
        // stale counts.
        prefs(context).edit {
            putBoolean(KEY_ENABLED, enabled)
            putInt(KEY_FAILED_COUNT, 0)
        }
        if (enabled) {
            DeadManSwitch.ensureWatchScheduled(context)
        } else {
            DeadManSwitch.cancelWatchIfIdle(context)
        }
    }

    fun attemptCap(context: Context): Int {
        val stored = prefs(context).getInt(KEY_ATTEMPT_CAP, DEFAULT_ATTEMPT_CAP)
        return if (stored in VALID_CAPS) stored else DEFAULT_ATTEMPT_CAP
    }

    fun setAttemptCap(context: Context, cap: Int) {
        if (cap !in VALID_CAPS) return
        prefs(context).edit { putInt(KEY_ATTEMPT_CAP, cap) }
    }

    fun wipeEsim(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIPE_ESIM, false)

    fun setWipeEsim(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_WIPE_ESIM, enabled) }
    }

    fun failedCount(context: Context): Int =
        prefs(context).getInt(KEY_FAILED_COUNT, 0)

    /**
     * Resets the counter when the phone is unlocked. Called by the
     * shared 15-minute worker on every run; this is how a successful
     * unlock resets the counter. No ACTION_USER_PRESENT receiver:
     * manifest receivers for implicit broadcasts do not fire on API
     * 26+. KeyguardManager.isDeviceLocked is false when unlocked OR
     * when no secure screen lock is set.
     */
    fun checkUnlockReset(context: Context) {
        if (failedCount(context) == 0) return
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceLocked) {
            prefs(context).edit { putInt(KEY_FAILED_COUNT, 0) }
        }
    }

    /**
     * Records every real-keyguard failure as a security event (even
     * when the cap is disabled — it is a security event for the
     * Timeline), then counts toward the cap when enabled. At the cap,
     * starts the 60-second abortable EraseCountdown and resets the
     * counter, so an aborted countdown does not re-fire until N fresh
     * failures.
     */
    suspend fun onFailedAttempt(context: Context) {
        val attempt = if (isEnabled(context)) {
            val newCount = failedCount(context) + 1
            prefs(context).edit { putInt(KEY_FAILED_COUNT, newCount) }
            newCount
        } else {
            1
        }
        val db = (context.applicationContext as OpenGuardApp).database
        SecurityEvents.record(
            db, "failed_unlock_attempt",
            details = mapOf("source" to "keyguard", "attempt" to attempt),
            confirmed = true,
        )
        UploadWorker.enqueueOneTime(context)
        if (!isEnabled(context)) return
        if (attempt >= attemptCap(context)) {
            val commandId = "wipecap-" + System.currentTimeMillis()
            setPendingWipeEuicc(context, commandId, wipeEsim(context))
            EraseCountdown.start(context, commandId)
            prefs(context).edit { putInt(KEY_FAILED_COUNT, 0) }
        }
    }

    fun setPendingWipeEuicc(context: Context, commandId: String, wipeEuicc: Boolean) {
        prefs(context).edit {
            putString(KEY_PENDING_WIPE_EUICC_COMMAND_ID, commandId)
            putBoolean(KEY_PENDING_WIPE_EUICC, wipeEuicc)
        }
    }

    /**
     * Returns the stored eSIM flag ONLY if the stored commandId equals
     * the given one. Clears both stored prefs either way: a mismatch
     * means the flag belongs to an old or aborted erase, so it is
     * stale and must never affect a later erase.
     */
    fun consumePendingWipeEuicc(context: Context, commandId: String): Boolean {
        val storedCommandId = prefs(context).getString(KEY_PENDING_WIPE_EUICC_COMMAND_ID, null)
        val wipeEuicc = prefs(context).getBoolean(KEY_PENDING_WIPE_EUICC, false)
        prefs(context).edit {
            remove(KEY_PENDING_WIPE_EUICC_COMMAND_ID)
            remove(KEY_PENDING_WIPE_EUICC)
        }
        return storedCommandId == commandId && wipeEuicc
    }
}
