package com.openguard.core.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.openguard.OpenGuardApp
import com.openguard.R
import com.openguard.core.common.SecurityConfig
import com.openguard.core.location.EraseWorker
import com.openguard.core.network.AckCommandRequest
import com.openguard.feature.security.EraseCountdownActivity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Remote erase countdown: a verified erase command never wipes at once.
 * It stores a pending erase with a 60-second deadline, shows an ongoing
 * notification with a full-screen intent to EraseCountdownActivity, and
 * enqueues a backstop worker that wipes if the activity or process died
 * mid-countdown. The owner can abort from the activity (after proving it
 * with the device credential) until the deadline passes.
 */
object EraseCountdown {

    fun start(context: Context, commandId: String) {
        val deadline = System.currentTimeMillis() + SecurityConfig.ERASE_GRACE_MS
        EraseStore.setPending(context, commandId, deadline)
        showNotification(context)
        val request = OneTimeWorkRequestBuilder<EraseWorker>()
            .setInitialDelay(SecurityConfig.ERASE_GRACE_MS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SecurityConfig.ERASE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Aborts a pending erase: clears the state, cancels the backstop
     * worker and the notification, and acks the command as failed so the
     * dashboard shows the abort. The local abort always takes effect,
     * even if the ack never reaches the server.
     */
    fun abort(context: Context) {
        val pending = EraseStore.pending(context)
        EraseStore.clear(context)
        WorkManager.getInstance(context).cancelUniqueWork(SecurityConfig.ERASE_WORK_NAME)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(SecurityConfig.ERASE_NOTIFICATION_ID)
        val commandId = pending?.commandId ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as OpenGuardApp
                val deviceId = app.tokenStore.deviceId
                val deviceToken = app.tokenStore.deviceToken
                if (deviceId != null && deviceToken != null) {
                    app.api.ackCommand(
                        "Bearer $deviceToken",
                        commandId,
                        AckCommandRequest(deviceId, "failed", "Erase aborted by owner"),
                    )
                }
            } catch (e: Exception) {
                // Abort already took effect locally; a failed ack must not undo it.
            }
        }
    }

    /**
     * Ongoing high-priority notification with a full-screen intent to
     * EraseCountdownActivity — the same pattern as LostMode: the
     * full-screen intent is the sanctioned way to surface an activity
     * from the background on Android 10+.
     */
    private fun showNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(SecurityConfig.ERASE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SecurityConfig.ERASE_CHANNEL_ID,
                    context.getString(R.string.erase_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val fullScreen = PendingIntent.getActivity(
            context, 0,
            Intent(context, EraseCountdownActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, SecurityConfig.ERASE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.erase_notification_title))
            .setContentText(context.getString(R.string.erase_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        try {
            manager.notify(SecurityConfig.ERASE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications revoked: the countdown still runs; the backstop
            // worker wipes at the deadline and the activity surfaces on launch.
        }
    }
}
