package com.openguard.core.security

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.openguard.MainActivity
import com.openguard.OpenGuardApp
import com.openguard.R
import com.openguard.core.common.SecurityConfig
import java.util.concurrent.TimeUnit

/**
 * Offline auto-lock: if the phone has no network for an owner-configured
 * duration AND the phone is locked, Lost Mode turns on automatically. It
 * closes the "thief disables data and WiFi to hide the phone" gap — a
 * phone that cannot be reached cannot be locked remotely, so it locks
 * itself.
 *
 * The check runs in [OfflineAutoLockWorker], a periodic worker with NO
 * network constraint: UploadWorker never runs while offline, so it could
 * never see the gap it is meant to close. There is no battery constraint
 * either — the check must still run on low battery.
 *
 * State machine (see [check]): disabled -> return; Lost Mode already on
 * -> clear the warning and return; network present -> stamp last-online
 * and return; first run with no stamp -> stamp now and return (never fire
 * on unknown data); offline past the threshold while locked -> fire once;
 * offline past the threshold while unlocked -> warn and wait for the next
 * run; inside the last hour before the threshold -> warn once per stretch.
 *
 * Honest limits:
 * - Airplane mode by the owner is the known false positive: the phone
 *   looks exactly like a stolen phone with its radios off. Mitigations
 *   are default-OFF, the locked-device gate (an unlocked phone never
 *   fires), and the 1-hour warning notification with a cancel path.
 * - KeyguardManager.isDeviceLocked is false when no secure screen lock
 *   (PIN, pattern, or password) is set, so without a screen lock
 *   auto-lock can never fire. Swipe-to-unlock does not count.
 * - WorkManager may delay the 15-minute checks while the phone is idle
 *   (Doze), so the fire time is approximate, not exact.
 * - The warning notification tap opens the app, which requires unlocking
 *   the phone first if it is locked — a thief cannot cancel without
 *   unlocking.
 * - There is no screen-off or lock hook: adding one would need
 *   LocationService edits, so the 15-minute worker run is the check
 *   cadence. Worst case, firing waits one worker run after the owner
 *   locks the phone.
 */
object OfflineAutoLock {

    const val WORK_NAME = "openguard-offline-autolock"

    /** Notification IDs 1-9 are taken (tracking, ring, lost mode, SIM, erase, WiFi offline, Bluetooth, evidence, geofence). */
    const val WARNING_NOTIFICATION_ID = 10

    /** The warning goes out one hour before the threshold. */
    const val WARNING_LEAD_MS = 3_600_000L

    /** MainActivity extra: the warning tap opens the app on the cancel path. */
    const val EXTRA_SHOW_CANCEL = "com.openguard.extra.OFFLINE_AUTOLOCK_CANCEL"

    private const val PREFS_NAME = "openguard_offline_protection"
    private const val KEY_ENABLED = "offline_autolock_enabled"
    private const val KEY_THRESHOLD_HOURS = "offline_autolock_threshold_hours"
    private const val KEY_LAST_ONLINE_AT = "offline_last_online_at"
    private const val KEY_WARNING_SENT_AT = "offline_warning_sent_at"

    private const val HOUR_MS = 3_600_000L
    private const val DEFAULT_THRESHOLD_HOURS = 12
    private val VALID_THRESHOLDS = setOf(6, 12, 24)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        if (enabled) {
            // The clock starts now: enabling must never fire on stale data.
            prefs(context).edit { putLong(KEY_LAST_ONLINE_AT, System.currentTimeMillis()) }
            ensureScheduled(context)
        } else {
            cancelScheduled(context)
            clearWarningState(context)
            cancelWarningNotification(context)
        }
    }

    fun thresholdHours(context: Context): Int {
        val stored = prefs(context).getInt(KEY_THRESHOLD_HOURS, DEFAULT_THRESHOLD_HOURS)
        return if (stored in VALID_THRESHOLDS) stored else DEFAULT_THRESHOLD_HOURS
    }

    fun setThresholdHours(context: Context, hours: Int) {
        if (hours !in VALID_THRESHOLDS) return
        prefs(context).edit { putInt(KEY_THRESHOLD_HOURS, hours) }
        // The new window gets its own warning later.
        clearWarningState(context)
        cancelWarningNotification(context)
    }

    fun lastOnlineAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_ONLINE_AT, 0L)

    fun offlineDurationMs(context: Context): Long {
        val lastOnline = lastOnlineAt(context)
        return if (lastOnline == 0L) 0L else System.currentTimeMillis() - lastOnline
    }

    fun isWarningActive(context: Context): Boolean =
        prefs(context).getLong(KEY_WARNING_SENT_AT, 0L) != 0L

    /** The owner's cancel path: resets the timer and clears the warning. */
    fun cancelPending(context: Context) {
        prefs(context).edit {
            putLong(KEY_LAST_ONLINE_AT, System.currentTimeMillis())
            putLong(KEY_WARNING_SENT_AT, 0L)
        }
        cancelWarningNotification(context)
    }

    fun hasNetwork(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.activeNetwork != null
    }

    /**
     * Unique periodic check every 15 minutes (the WorkManager minimum).
     * No constraints at all: a network constraint would stop the check
     * exactly when it matters, and a battery constraint would stop it on
     * low battery.
     */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<OfflineAutoLockWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelScheduled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * The state machine, in exact order: disabled -> Lost Mode already on
     * -> network present -> first run (no stamp) -> threshold plus locked
     * gate -> warning window. Firing happens at most once while Lost Mode
     * stays on: after firing, step 2 returns on every later run. If the
     * owner turns Lost Mode off while the phone is still offline past the
     * threshold, a later locked run can fire again — the owner avoids that
     * with cancelPending (which resets the timer) or by reconnecting.
     */
    suspend fun check(context: Context) {
        if (!isEnabled(context)) return
        if (LostModeStore.isOn(context)) {
            clearWarningState(context)
            cancelWarningNotification(context)
            return
        }
        val now = System.currentTimeMillis()
        if (hasNetwork(context)) {
            prefs(context).edit {
                putLong(KEY_LAST_ONLINE_AT, now)
                putLong(KEY_WARNING_SENT_AT, 0L)
            }
            cancelWarningNotification(context)
            return
        }
        val lastOnline = lastOnlineAt(context)
        if (lastOnline == 0L) {
            prefs(context).edit { putLong(KEY_LAST_ONLINE_AT, now) }
            return
        }
        val offlineMs = now - lastOnline
        val thresholdMs = thresholdHours(context) * HOUR_MS
        if (offlineMs >= thresholdMs) {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguard.isDeviceLocked) {
                val offlineHours = offlineMs / HOUR_MS
                val db = (context.applicationContext as OpenGuardApp).database
                SecurityEvents.record(
                    db, "sync_gap",
                    details = mapOf(
                        "reason" to "offline_auto_lock",
                        "offline_hours" to offlineHours,
                    ),
                    confirmed = true,
                )
                clearWarningState(context)
                cancelWarningNotification(context)
                LostMode.turnOn(context, "Offline for $offlineHours hours — auto-locked")
            } else {
                // The owner is using the phone: warn them Lost Mode will
                // turn on when the phone locks, and re-check next run.
                ensureWarningPosted(context, offlineMs)
            }
            return
        }
        if (offlineMs >= thresholdMs - WARNING_LEAD_MS) {
            ensureWarningPosted(context, offlineMs)
        }
    }

    /**
     * Posts the warning notification once per offline stretch. A warning
     * stamped before the last online time belongs to a previous stretch,
     * so it does not count. Re-posting the notification itself is
     * harmless — the same ID replaces the old one.
     */
    private fun ensureWarningPosted(context: Context, offlineMs: Long) {
        val warningSentAt = prefs(context).getLong(KEY_WARNING_SENT_AT, 0L)
        if (warningSentAt != 0L && warningSentAt >= lastOnlineAt(context)) return
        postWarningNotification(context, offlineMs / HOUR_MS)
        prefs(context).edit { putLong(KEY_WARNING_SENT_AT, System.currentTimeMillis()) }
    }

    /**
     * High-priority warning: Lost Mode will turn on when the threshold is
     * reached. Tapping it opens the app on the cancel path. Request code
     * 10, not 0: LocationService already uses request code 0 for a
     * MainActivity PendingIntent, and same-class plus same-request-code
     * PendingIntents collide.
     */
    fun postWarningNotification(context: Context, offlineHours: Long) {
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
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SHOW_CANCEL, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, SecurityConfig.SECURITY_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.offline_autolock_warning_title))
            .setContentText(
                context.getString(R.string.offline_autolock_warning_text, offlineHours),
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

    private fun clearWarningState(context: Context) {
        prefs(context).edit { putLong(KEY_WARNING_SENT_AT, 0L) }
    }
}
