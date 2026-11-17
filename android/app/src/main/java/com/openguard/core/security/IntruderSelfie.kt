package com.openguard.core.security

import android.content.Context
import androidx.core.content.edit
import com.openguard.core.evidence.EvidenceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Intruder Selfie: takes one front-camera still photo of whoever fails
 * to unlock the phone, and sends it through the normal evidence
 * pipeline (file to the owner's own Google Drive, metadata only to our
 * server — exactly like an evidence clip).
 *
 * Two triggers, both owner-armed by the same toggle (default off):
 * - Real keyguard failures, via [onKeyguardFailure] from
 *   OpenGuardDeviceAdmin.onPasswordFailed: 2 failures inside a
 *   5-minute window. No unlock-reset is needed — the window
 *   approximates "consecutive": a typo, a successful unlock, then
 *   another typo hours later never triggers.
 * - App-lock lockout, via [onAppLockLockout] from DecoyLock or
 *   LostModeActivity when the app-lock failure count reaches 5: the
 *   lockout itself is the threshold, so no counting happens here.
 *
 * After any capture, a 1-hour cooldown runs: no photo spam. The
 * evidence clips cover continuous recording while Lost Mode is on.
 *
 * Android's green camera indicator always shows during the capture.
 * That is platform-mandated and cannot be hidden — there is no
 * stealth or hidden capture anywhere in this app.
 *
 * State lives in SharedPreferences file "openguard_intruder_selfie",
 * plain prefs like FailedAttemptWipe (nothing here is a secret).
 */
object IntruderSelfie {

    private const val PREFS_NAME = "openguard_intruder_selfie"
    private const val KEY_ENABLED = "intruder_selfie_enabled"
    private const val KEY_LAST_FAILURE_AT = "intruder_selfie_last_failure_at"
    private const val KEY_FAILURE_COUNT = "intruder_selfie_failure_count"
    private const val KEY_LAST_CAPTURE_AT = "intruder_selfie_last_capture_at"

    /** Two keyguard failures inside this window count as consecutive. */
    private const val WINDOW_MS = 5 * 60 * 1000L
    /** Keyguard failures needed inside the window before a selfie fires. */
    private const val KEYGUARD_THRESHOLD = 2
    /** After a capture, no further selfie for one hour. */
    private const val COOLDOWN_MS = 60 * 60 * 1000L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    /**
     * Called from OpenGuardDeviceAdmin.onPasswordFailed on every real
     * keyguard failure. Counts in a 5-minute window: a failure within
     * 5 minutes of the last one increments the count, otherwise the
     * count restarts at 1. At 2, captures one photo and starts the
     * 1-hour cooldown. While the cooldown runs, does nothing.
     */
    suspend fun onKeyguardFailure(context: Context) {
        if (!isEnabled(context)) return
        if (inCooldown(context)) return
        val now = System.currentTimeMillis()
        val lastFailureAt = prefs(context).getLong(KEY_LAST_FAILURE_AT, 0L)
        val count = if (now - lastFailureAt <= WINDOW_MS) {
            prefs(context).getInt(KEY_FAILURE_COUNT, 0) + 1
        } else {
            1
        }
        prefs(context).edit {
            putLong(KEY_LAST_FAILURE_AT, now)
            putInt(KEY_FAILURE_COUNT, count)
        }
        if (count >= KEYGUARD_THRESHOLD) {
            prefs(context).edit {
                putLong(KEY_LAST_CAPTURE_AT, now)
                putInt(KEY_FAILURE_COUNT, 0)
            }
            capturePhoto(context)
        }
    }

    /**
     * Called when the app-lock failure count reaches 5 and lockout
     * engages. No counting here — the lockout itself is the threshold.
     * May be called from the main thread: the prefs read and the
     * cooldown check-and-set happen synchronously (fast, no I/O
     * beyond prefs), and only the capture launches on an IO scope.
     * Setting the cooldown before launching means a second lockout in
     * the same moment cannot capture twice.
     */
    fun onAppLockLockout(context: Context) {
        if (!isEnabled(context)) return
        if (inCooldown(context)) return
        prefs(context).edit { putLong(KEY_LAST_CAPTURE_AT, System.currentTimeMillis()) }
        val appContext = context.applicationContext
        ioScope.launch { capturePhoto(appContext) }
    }

    private fun inCooldown(context: Context): Boolean {
        val lastCaptureAt = prefs(context).getLong(KEY_LAST_CAPTURE_AT, 0L)
        return lastCaptureAt > 0 &&
            System.currentTimeMillis() - lastCaptureAt < COOLDOWN_MS
    }

    /**
     * Starts the one-photo capture. The foreground-service start can
     * be denied (background-start limits, revoked permission): a
     * denied start just means no photo, never a crash.
     */
    private fun capturePhoto(context: Context) {
        try {
            EvidenceService.capturePhoto(context)
        } catch (e: IllegalStateException) {
            // Foreground-service start denied: no photo, never a crash.
        } catch (e: SecurityException) {
            // Permission revoked between the check and the start.
        }
    }
}
