package com.openguard.core.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.openguard.R
import com.openguard.core.common.SecurityConfig
import com.openguard.feature.security.LostModeActivity

/**
 * Lost Mode: locks the phone (when device admin is active) and shows a
 * full-screen "This phone is lost" screen with the owner's message.
 *
 * Honest note: Home cannot be blocked without being a launcher, so Lost
 * Mode is a deterrent + contact channel, not a kiosk lock. The phone is
 * locked and location keeps uploading while it is on.
 */
object LostMode {

    /**
     * Turns Lost Mode on locally. Returns the ack result string: it notes
     * when the lock was skipped because device admin is not active.
     */
    fun turnOn(context: Context, message: String?): String {
        LostModeStore.setOn(context, message)
        val locked = DeviceAdminManager.lockNow(context)
        showNotification(context)
        return if (locked) {
            "Lost Mode on — phone locked"
        } else {
            "Lost Mode on (lock skipped: device admin is not enabled)"
        }
    }

    fun turnOff(context: Context) {
        LostModeStore.clear(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(SecurityConfig.LOST_MODE_NOTIFICATION_ID)
    }

    /**
     * High-priority notification with a full-screen intent to
     * LostModeActivity. The full-screen intent is the sanctioned way to
     * surface an activity from the background (a direct startActivity
     * from a worker is blocked on Android 10+); on an unlocked phone it
     * shows as a heads-up notification instead.
     */
    private fun showNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(SecurityConfig.LOST_MODE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SecurityConfig.LOST_MODE_CHANNEL_ID,
                    context.getString(R.string.lost_mode_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val fullScreen = PendingIntent.getActivity(
            context, 0,
            Intent(context, LostModeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, SecurityConfig.LOST_MODE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.lost_mode_notification_title))
            .setContentText(context.getString(R.string.lost_mode_notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        try {
            manager.notify(SecurityConfig.LOST_MODE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications revoked: Lost Mode is still on locally.
        }
    }
}
