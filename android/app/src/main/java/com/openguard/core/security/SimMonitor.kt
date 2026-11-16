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
 * run, and the worker's check does the actual compare + recording.
 */
class SimChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        UploadWorker.enqueueOneTime(context)
    }
}
