package com.openguard.core.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import com.openguard.MainActivity
import com.openguard.R
import com.openguard.core.common.SecurityConfig
import com.openguard.core.database.AppDatabase
import com.openguard.core.location.TrackingPermissions
import com.openguard.core.location.UploadWorker

/**
 * Watches for SIM-card changes by comparing the active subscription ids
 * with the last-seen ids. A change is recorded as an UNCONFIRMED
 * sim_changed event — it is an indication, never proof of theft — and the
 * owner gets a local notification asking them to check it was them.
 *
 * One change is special: when NO SIM is active anymore while Lost Mode
 * is on, chaos mode is armed (a persisted flag the tracking service's
 * theft-response supervisor picks up). A SIM swap to a different SIM
 * does not arm it — only removal/unavailability does.
 */
object SimMonitor {

    /** Empty without the phone-state permission (or with no SIM). */
    fun getSubscriptionIds(context: Context): List<Int> {
        if (!TrackingPermissions.hasPhoneState(context)) return emptyList()
        val manager = SubscriptionManager.from(context)
        return manager.activeSubscriptionInfoList?.map { it.subscriptionId }.orEmpty()
    }

    /**
     * Called from UploadWorker each run. First run with no stored ids:
     * stores silently, no event. No permission: skips (an empty list must
     * never look like a SIM removal).
     */
    suspend fun checkAndRecord(context: Context, db: AppDatabase) {
        if (!TrackingPermissions.hasPhoneState(context)) return
        val current = getSubscriptionIds(context)
        val lastSeen = SecurityPrefs.lastSeenSubscriptionIds(context)
        if (lastSeen == null) {
            SecurityPrefs.setLastSeenSubscriptionIds(context, current)
            return
        }
        if (current.map { it.toString() }.toSet() == lastSeen) return
        val trusted = SecurityPrefs.trustedSubscriptionIds(context)
        val isTrusted = trusted.isNotEmpty() && current.all { it.toString() in trusted }
        SecurityEvents.record(
            db,
            "sim_changed",
            details = mapOf("trusted" to isTrusted),
            confirmed = false,
        )
        SecurityPrefs.setLastSeenSubscriptionIds(context, current)
        postSimChangedNotification(context)
        // Trusted-contact alert (owner toggle, default on): one SMS per hour.
        TrustedAlerts.onSimChanged(context, isTrusted)
        // SIM removed (no active subscription left, where one was seen
        // before) while Lost Mode is on: arm chaos mode. Only the flag
        // is set here — the chaos timer runs from the tracking
        // service's scope, never from this worker, so it survives the
        // worker finishing. The flag is cleared when Lost Mode turns
        // off; chaos stops only on owner exit.
        if (current.isEmpty() && lastSeen.isNotEmpty() && LostModeStore.isOn(context)) {
            SecurityPrefs.setChaosModeActive(context, true)
            SecurityEvents.record(db, "chaos_started", confirmed = true)
        }
    }

    /**
     * Fast chaos trigger from the SIM-state broadcast (no network
     * needed): the worker above needs a network connection, but a
     * removed SIM often means no network at all. Re-checks the real
     * SIM state first, so a spoofed broadcast alone can never arm
     * chaos mode. The worker still records the sim_changed /
     * chaos_started events when it next runs.
     */
    fun onSimStateChanged(context: Context) {
        if (!LostModeStore.isOn(context)) return
        if (!TrackingPermissions.hasPhoneState(context)) return
        if (getSubscriptionIds(context).isNotEmpty()) return
        if (SecurityPrefs.lastSeenSubscriptionIds(context).isNullOrEmpty()) return
        SecurityPrefs.setChaosModeActive(context, true)
    }

    /** Marks the currently inserted SIM(s) as trusted by the owner. */
    fun trustCurrentSim(context: Context) {
        SecurityPrefs.setTrustedSubscriptionIds(context, getSubscriptionIds(context))
    }

    private fun postSimChangedNotification(context: Context) {
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
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, SecurityConfig.SECURITY_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.sim_notification_title))
            .setContentText(context.getString(R.string.sim_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            manager.notify(SecurityConfig.SIM_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications revoked: the event is still queued for upload.
        }
    }
}

/**
 * The SIM-state broadcast is only a nudge: it enqueues a one-time worker
 * run, and the worker's check does the actual compare + recording. It
 * also arms chaos mode directly when the SIM is gone during Lost Mode —
 * the worker needs network, and a removed SIM often means there is none.
 */
class SimChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        SimMonitor.onSimStateChanged(context)
        UploadWorker.enqueueOneTime(context)
    }
}
