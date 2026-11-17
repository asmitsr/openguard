package com.openguard.core.security

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.openguard.MainActivity
import com.openguard.R
import com.openguard.core.common.SecurityConfig
import java.util.concurrent.TimeUnit

/**
 * Dead-man's switch: if the owner does not unlock the phone for an
 * owner-configured number of days, the phone locks itself (Lost Mode),
 * and after a longer period it wipes itself. It covers the case where
 * the phone is stolen and never recovered, or the owner can no longer
 * reach it: the data destroys itself instead of sitting on a lost
 * device forever.
 *
 * The check runs in [DeadManSwitchWorker], a periodic worker with NO
 * constraints — the switch must still run on low battery and without
 * network, because a stolen phone has neither guaranteed.
 *
 * Unlock detection is the KeyguardManager.isDeviceLocked check inside
 * [check]: if the device is unlocked at a worker run, the owner proved
 * presence and the clock resets. There is no ACTION_USER_PRESENT
 * receiver — manifest receivers for implicit broadcasts do not fire on
 * API 26+, so the 15-minute worker check is the design. Worst case, an
 * unlock is noticed up to 15 minutes late; that only ever delays a
 * firing, never advances one.
 *
 * State machine (see [check]): disabled -> return; first run with no
 * stamp -> stamp now and return (never fire on unknown data); unlocked
 * -> stamp now, clear warning and wipe-fired state, return; locked
 * past the wipe threshold -> start the abortable erase countdown once;
 * inside the last 24 hours before the wipe threshold -> warn once per
 * stretch; locked past the lock threshold -> turn Lost Mode on. The
 * warning is checked BEFORE the lock: the wipe window is always past
 * the lock threshold (wipeDays is strictly longer than lockDays), so a
 * lock branch first would swallow the warning window and the warning
 * would never fire.
 *
 * Honest limits:
 * - KeyguardManager.isDeviceLocked is false when no secure screen lock
 *   (PIN, pattern, or password) is set, so without a screen lock the
 *   switch can never fire. Swipe-to-unlock does not count.
 * - WorkManager may delay the 15-minute checks while the phone is idle
 *   (Doze), so the fire times are approximate, not exact.
 * - A phone left at home for weeks WILL lock then wipe — that is the
 *   feature working as designed, not a bug. The owner opts in knowing
 *   this, and the 24-hour warning is the last chance to unlock.
 * - If the owner is incapacitated, the switch fires and the data is
 *   destroyed by design. There is no dead-man's switch that can tell
 *   "stolen" from "owner cannot reach the phone".
 * - The wipe uses the same 60-second EraseCountdown as remote erase:
 *   aborting it needs the device credential, so a thief holding a
 *   locked phone cannot stop it by tapping.
 */
object DeadManSwitch {

    const val WORK_NAME = "openguard-deadman-watch"

    /** Notification IDs 1-10 are taken (tracking, ring, lost mode, SIM, erase, WiFi offline, Bluetooth, evidence, geofence, offline-autolock warning). */
    // Notification ID 12 (and PendingIntent request code 12): 11 is
    // SnatchDetector's — two features sharing an ID would replace each
    // other's notification in the shade.
    const val WARNING_NOTIFICATION_ID = 12

    /** The warning goes out 24 hours before the wipe threshold. */
    const val WARNING_LEAD_MS = 24 * 60 * 60 * 1000L

    private const val PREFS_NAME = "openguard_deadman_switch"
    private const val KEY_ENABLED = "deadman_enabled"
    private const val KEY_LOCK_DAYS = "deadman_lock_days"
    private const val KEY_WIPE_DAYS = "deadman_wipe_days"
    private const val KEY_LAST_UNLOCK_AT = "deadman_last_unlock_at"
    private const val KEY_WARNING_SENT_AT = "deadman_warning_sent_at"
    private const val KEY_WIPE_FIRED_AT = "deadman_wipe_fired_at"

    private const val TEST_WORK_NAME = "openguard-deadman-test"
    private const val DAY_MS = 86_400_000L
    private const val DEFAULT_LOCK_DAYS = 7
    private const val DEFAULT_WIPE_DAYS = 30
    private val VALID_LOCK_DAYS = setOf(3, 7, 14, 30)
    private val VALID_WIPE_DAYS = setOf(7, 14, 30, 60)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        if (enabled) {
            // The clock starts now: enabling must never fire on stale data.
            prefs(context).edit { putLong(KEY_LAST_UNLOCK_AT, System.currentTimeMillis()) }
            ensureWatchScheduled(context)
        } else {
            cancelWatchIfIdle(context)
            prefs(context).edit {
                putLong(KEY_WARNING_SENT_AT, 0L)
                putLong(KEY_WIPE_FIRED_AT, 0L)
            }
            cancelWarningNotification(context)
        }
    }

    fun lockDays(context: Context): Int {
        val stored = prefs(context).getInt(KEY_LOCK_DAYS, DEFAULT_LOCK_DAYS)
        return if (stored in VALID_LOCK_DAYS) stored else DEFAULT_LOCK_DAYS
    }

    fun setLockDays(context: Context, days: Int) {
        if (days !in VALID_LOCK_DAYS) return
        // Backstop: the wipe must stay strictly longer than the lock.
        if (days >= wipeDays(context)) return
        prefs(context).edit { putInt(KEY_LOCK_DAYS, days) }
    }

    fun wipeDays(context: Context): Int {
        val stored = prefs(context).getInt(KEY_WIPE_DAYS, DEFAULT_WIPE_DAYS)
        return if (stored in VALID_WIPE_DAYS) stored else DEFAULT_WIPE_DAYS
    }

    fun setWipeDays(context: Context, days: Int) {
        if (days !in VALID_WIPE_DAYS) return
        // Backstop: the wipe must stay strictly longer than the lock.
        if (days <= lockDays(context)) return
        prefs(context).edit { putInt(KEY_WIPE_DAYS, days) }
    }

    fun lastUnlockAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_UNLOCK_AT, 0L)

    fun daysSinceUnlock(context: Context): Long {
        val lastUnlock = lastUnlockAt(context)
        if (lastUnlock == 0L) return 0L
        // A clock set backwards must never show negative days.
        return ((System.currentTimeMillis() - lastUnlock) / DAY_MS).coerceAtLeast(0L)
    }

    fun isWarningActive(context: Context): Boolean =
        prefs(context).getLong(KEY_WARNING_SENT_AT, 0L) != 0L

    /**
     * Unique periodic check every 15 minutes (the WorkManager minimum).
     * No constraints at all: the switch must run on low battery and
     * without network — a stolen phone has neither guaranteed.
     */
    fun ensureWatchScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<DeadManSwitchWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Cancels the shared watch only when nothing needs it: both the
     * dead-man's switch and the failed-attempt wipe share this worker.
     */
    fun cancelWatchIfIdle(context: Context) {
        if (isEnabled(context) || FailedAttemptWipe.isEnabled(context)) return
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Test hook, only when enabled: moves the last-unlock stamp to just
     * past the lock threshold, so the next check fires the lock. The
     * wipe threshold stays days away (wipeDays is always strictly
     * longer than lockDays), so this can only ever fire the lock, never
     * the wipe.
     */
    fun testLockFiring(context: Context) {
        if (!isEnabled(context)) return
        val now = System.currentTimeMillis()
        prefs(context).edit {
            putLong(KEY_LAST_UNLOCK_AT, now - lockDays(context) * DAY_MS - 60_000L)
            putLong(KEY_WARNING_SENT_AT, 0L)
        }
        val request = OneTimeWorkRequestBuilder<DeadManSwitchWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TEST_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * The state machine, in exact order: disabled -> first run (no
     * stamp) -> unlocked (presence proved) -> wipe threshold ->
     * warning window -> lock threshold. The warning is checked before
     * the lock: the warning window (24h before the wipe) is always
     * past the lock threshold, so checking the lock first would return
     * before ever reaching the warning. Unlike OfflineAutoLock, there
     * is no early return when Lost Mode is already on: the wipe
     * threshold must still fire while Lost Mode holds the phone
     * locked. The wipeFiredAt guard stops re-firing every 15 minutes
     * if the owner aborts the countdown; the next unlock clears it.
     */
    suspend fun check(context: Context) {
        if (!isEnabled(context)) return
        val now = System.currentTimeMillis()
        val lastUnlock = lastUnlockAt(context)
        if (lastUnlock == 0L) {
            prefs(context).edit { putLong(KEY_LAST_UNLOCK_AT, now) }
            return
        }
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceLocked) {
            // The owner just proved presence: reset the clock.
            prefs(context).edit {
                putLong(KEY_LAST_UNLOCK_AT, now)
                putLong(KEY_WARNING_SENT_AT, 0L)
                putLong(KEY_WIPE_FIRED_AT, 0L)
            }
            cancelWarningNotification(context)
            return
        }
        val lockedMs = now - lastUnlock
        val wipeMs = wipeDays(context) * DAY_MS
        val lockMs = lockDays(context) * DAY_MS
        if (lockedMs >= wipeMs) {
            if (prefs(context).getLong(KEY_WIPE_FIRED_AT, 0L) == 0L) {
                EraseCountdown.start(context, "deadman-wipe-$now")
                prefs(context).edit { putLong(KEY_WIPE_FIRED_AT, now) }
            }
            return
        }
        if (lockedMs >= wipeMs - WARNING_LEAD_MS) {
            ensureWarningPosted(context, lockedMs)
        }
        if (lockedMs >= lockMs) {
            if (!LostModeStore.isOn(context)) {
                LostMode.turnOn(context, "No unlock for ${lockDays(context)} days — auto-locked")
            }
            return
        }
    }

    /**
     * Posts the warning notification once per locked stretch. A warning
     * stamped before the last unlock time belongs to a previous stretch,
     * so it does not count. Re-posting the notification itself is
     * harmless — the same ID replaces the old one.
     */
    private fun ensureWarningPosted(context: Context, lockedMs: Long) {
        val warningSentAt = prefs(context).getLong(KEY_WARNING_SENT_AT, 0L)
        if (warningSentAt != 0L && warningSentAt >= lastUnlockAt(context)) return
        postWarningNotification(context, lockedMs / DAY_MS)
        prefs(context).edit { putLong(KEY_WARNING_SENT_AT, System.currentTimeMillis()) }
    }

    /**
     * High-priority warning: the phone will wipe itself when the wipe
     * threshold is reached. Tapping it opens the app. Request code 12,
     * not 0, 10 or 11: LocationService uses 0, OfflineAutoLock uses 10
     * and SnatchDetector uses 11, and same-class plus same-request-code
     * PendingIntents collide.
     */
    fun postWarningNotification(context: Context, daysSinceUnlock: Long) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(SecurityConfig.SECURITY_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SecurityConfig.SECURITY_CHANNEL_ID,
                    context.getString(R.string.security_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val openApp = PendingIntent.getActivity(
            context, WARNING_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, SecurityConfig.SECURITY_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.deadman_warning_title))
            .setContentText(
                context.getString(R.string.deadman_warning_text, daysSinceUnlock),
            )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            manager.notify(WARNING_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications revoked: nothing to show.
        }
    }

    fun cancelWarningNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(WARNING_NOTIFICATION_ID)
    }
}
