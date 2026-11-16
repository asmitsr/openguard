package com.openguard.core.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.openguard.R
import com.openguard.core.common.SecurityConfig
import com.openguard.core.evidence.EvidenceService
import com.openguard.core.location.LocationService
import com.openguard.feature.security.LostModeActivity

/**
 * Lost Mode: locks the phone (when device admin is active), disables
 * the camera (same condition), and shows the owner's message — as the
 * decoy lock overlay when "Display over other apps" is allowed, else
 * as a high-priority notification with a full-screen intent to
 * LostModeActivity. Location keeps uploading while it is on, and
 * EvidenceService records short clips (audio-only while the camera is
 * disabled by admin).
 *
 * Honest note: Home cannot be blocked without being a launcher, and
 * the overlay sits below the status bar and keyguard (platform rule),
 * so Lost Mode is a deterrent + contact channel, not a kiosk lock.
 * A forced restart cannot be blocked; BootReceiver re-locks after it.
 */
object LostMode {

    /**
     * Turns Lost Mode on locally. Returns the ack result string: it
     * notes when the lock was skipped (no device admin) and when the
     * decoy overlay is unavailable (no overlay permission).
     */
    fun turnOn(context: Context, message: String?): String {
        LostModeStore.setOn(context, message)
        val locked = DeviceAdminManager.lockNow(context)
        DeviceAdminManager.setCameraDisabled(context, true)
        showNotification(context)
        val decoy = DecoyLock.show(context)
        // Theft response (wake counter + auto-call, chaos mode) is
        // hosted by the tracking foreground service, so start tracking
        // now — otherwise nothing hosts it when tracking was off. The
        // start runs after the decoy show so the overlay window exists
        // (show() itself posts to the main thread, so this ordering is
        // best-effort; on targetSdk 34 the SYSTEM_ALERT_WINDOW grant
        // alone is the documented Android 12+ exemption for starting a
        // foreground service from the background, and the
        // ACCESS_BACKGROUND_LOCATION grant from consent covers the
        // location while-in-use check). If the start is denied anyway,
        // the catch keeps Lost Mode on: the decoy and the boot
        // receiver still hold the line, and the theft response starts
        // when tracking next runs.
        val trackingStarted = try {
            LocationService.start(context)
            true
        } catch (e: IllegalStateException) {
            // Background start denied (ForegroundServiceStartNotAllowed).
            false
        } catch (e: SecurityException) {
            // While-in-use location check (background location revoked).
            false
        }
        // Evidence (Workstream A): short clips while Lost Mode is on.
        // Same background-start rules as tracking (the decoy overlay
        // grant is the exemption); a denied start leaves Lost Mode on,
        // and the periodic worker retries the start while Lost Mode
        // stays on. The service itself stops when the microphone
        // permission is missing — there is nothing to record then.
        val evidenceStarted = try {
            EvidenceService.start(context)
            true
        } catch (e: IllegalStateException) {
            false
        } catch (e: SecurityException) {
            false
        }
        val parts = mutableListOf<String>()
        parts += if (locked) {
            "phone locked"
        } else {
            "lock skipped (device admin is not enabled)"
        }
        parts += if (decoy) {
            "decoy lock showing"
        } else {
            "overlay not allowed — message shows as notification"
        }
        parts += if (trackingStarted) {
            "tracking started"
        } else {
            "tracking start denied (theft response waits for tracking)"
        }
        parts += if (evidenceStarted) {
            "evidence recording"
        } else {
            "evidence start denied (needs microphone permission)"
        }
        return "Lost Mode on — " + parts.joinToString("; ")
    }

    fun turnOff(context: Context) {
        LostModeStore.clear(context)
        DecoyLock.hide(context)
        DeviceAdminManager.setCameraDisabled(context, false)
        // Theft response stops with Lost Mode: clear the chaos flag
        // (the tracking service's supervisor also stops the chaos
        // timer and resets the wake counter when it sees Lost Mode
        // off) and silence any chaos alarm right away, even if the
        // service is not running.
        SecurityPrefs.setChaosModeActive(context, false)
        RingService.stop(context)
        // Evidence records only while Lost Mode is on: stop it now.
        EvidenceService.stop(context)
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
