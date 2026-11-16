package com.openguard.core.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openguard.MainActivity
import com.openguard.R
import com.openguard.core.common.SecurityConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rings the phone at full alarm-stream volume, even in silent / Do Not
 * Disturb mode (the alarm stream is not silenced by DND). Loops the
 * default alarm sound and vibrates until the user taps Stop, unlocks the
 * phone, or 2 minutes pass.
 *
 * Android 12+ blocks starting a foreground service from the background:
 * start() returns false in that case (CommandExecutor acks the command
 * as failed with a clear reason). While tracking is on, LocationService's
 * foreground service keeps the process in the foreground, so ring works.
 */
class RingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var previousVolume: Int? = null
    private var stopJob: Job? = null

    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startRinging()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRinging()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    private fun startRinging() {
        if (player != null) return // already ringing
        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                SecurityConfig.RING_NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (e: SecurityException) {
            // Notifications revoked: cannot run as a foreground service.
            stopSelf()
            return
        }
        // Max alarm-stream volume; restored when ringing stops.
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0,
        )
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Sound failed: vibration + the visible notification still alert.
            player = null
        }
        getSystemService(Vibrator::class.java)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0),
        )
        // ACTION_USER_PRESENT is a protected system broadcast, so an
        // exported receiver is safe here (only the system can send it).
        ContextCompat.registerReceiver(
            this,
            userPresentReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED,
        )
        stopJob = scope.launch {
            delay(SecurityConfig.RING_DURATION_MS)
            stopSelf()
        }
    }

    private fun stopRinging() {
        stopJob?.cancel()
        stopJob = null
        try {
            unregisterReceiver(userPresentReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was never registered (startRinging bailed early).
        }
        player?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                // Already stopped/released; nothing to clean up.
            }
        }
        player = null
        getSystemService(Vibrator::class.java)?.cancel()
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        previousVolume?.let { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
        previousVolume = null
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(SecurityConfig.RING_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SecurityConfig.RING_CHANNEL_ID,
                    getString(R.string.ring_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, RingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SecurityConfig.RING_CHANNEL_ID)
            .setContentTitle(getString(R.string.ring_notification_title))
            .setContentText(getString(R.string.ring_notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.ring_notification_stop),
                stop,
            )
            .build()
    }

    companion object {
        const val ACTION_START = "com.openguard.action.START_RING"
        const val ACTION_STOP = "com.openguard.action.STOP_RING"

        /**
         * False when the service could not be started — on Android 12+
         * a background start is blocked unless the app is already in the
         * foreground (e.g. tracking is on).
         */
        fun start(context: Context): Boolean {
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RingService::class.java).setAction(ACTION_START),
                )
                true
            } catch (e: IllegalStateException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
