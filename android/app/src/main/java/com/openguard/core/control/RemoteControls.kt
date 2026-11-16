package com.openguard.core.control

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openguard.R
import com.openguard.core.common.SecurityConfig
import com.openguard.core.security.DecoyLock
import com.openguard.core.security.DeviceAdminManager
import com.openguard.core.security.LostModeStore
import java.io.IOException

// Remote Control Center (Workstream B): every owner-invoked remote
// control, one function per command action. Each returns a
// ControlResult: ok plus the exact ack text, so a missing grant is
// reported by name instead of failing silently. CommandExecutor maps
// these onto its acked/failed outcomes.
//
// Feasibility was checked against the official Android docs per
// control (see the per-function notes). Deliberately NOT here: WiFi
// on/off, mobile data, airplane mode, GPS, NFC, screenshots, setting
// a device PIN, disabling other apps, background clipboard, power-on
// and post-factory-reset tracking — the platform does not allow any
// of those to a normal app, and they must not be promised.
object RemoteControls {

    data class ControlResult(val ok: Boolean, val text: String)

    const val DEFAULT_SPOKEN_MESSAGE = "This phone is lost. Please contact the owner."
    const val DEFAULT_WALLPAPER_MESSAGE = "This phone is lost — please contact the owner"

    private const val WRITE_SETTINGS_MISSING =
        "Modify system settings is not granted — allow it in this phone's Settings first"

    // ---- Grant checks, reused by the Settings screen (coordinator) ----

    fun canWriteSettings(context: Context): Boolean = Settings.System.canWrite(context)

    fun dndAccessGranted(context: Context): Boolean =
        notificationManager(context).isNotificationPolicyAccessGranted

    fun cameraGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun bluetoothConnectGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    // ---- Volume (AudioManager.setStreamVolume) ----
    // Docs: developer.android.com/reference/android/media/AudioManager.
    // No special grant: setStreamVolume is available to any app (the
    // manifest declares MODIFY_AUDIO_SETTINGS for correctness; it is a
    // normal permission, granted at install). Levels arrive as 0-100
    // percent and are mapped onto each stream's own maximum, which
    // varies by phone, so getStreamMaxVolume is read at runtime.
    fun setVolume(context: Context, stream: String, levelPercent: Int): ControlResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streams = when (stream) {
            "media" -> listOf(AudioManager.STREAM_MUSIC to "Media")
            "ring" -> listOf(AudioManager.STREAM_RING to "Ring")
            "alarm" -> listOf(AudioManager.STREAM_ALARM to "Alarm")
            "notification" -> listOf(AudioManager.STREAM_NOTIFICATION to "Notification")
            else -> listOf(
                AudioManager.STREAM_MUSIC to "Media",
                AudioManager.STREAM_RING to "Ring",
                AudioManager.STREAM_ALARM to "Alarm",
                AudioManager.STREAM_NOTIFICATION to "Notification",
            )
        }
        val level = levelPercent.coerceIn(0, 100)
        return try {
            for ((type, _) in streams) {
                val max = audio.getStreamMaxVolume(type)
                audio.setStreamVolume(type, (level * max + 50) / 100, 0)
            }
            val names = streams.joinToString(", ") { it.second }
            // Setting the ring volume while silent changes nothing
            // audible: say so instead of implying the phone will ring.
            val silentNote = if (streams.any { it.first == AudioManager.STREAM_RING } &&
                audio.ringerMode == AudioManager.RINGER_MODE_SILENT
            ) {
                " (ringer mode is silent — set it to normal to hear the ringtone)"
            } else {
                ""
            }
            ControlResult(true, "$names volume set to $level%$silentNote")
        } catch (e: SecurityException) {
            ControlResult(false, "Could not set the volume (audio permission missing)")
        }
    }

    // ---- Brightness (Settings.System.SCREEN_BRIGHTNESS) ----
    // Docs: developer.android.com/reference/android/provider/Settings.System.
    // Range 1-255; needs WRITE_SETTINGS (manifest) plus the user's
    // grant via ACTION_MANAGE_WRITE_SETTINGS. A manual value only
    // applies in manual mode, so the mode is set first.
    fun setBrightness(context: Context, levelPercent: Int): ControlResult {
        if (!canWriteSettings(context)) return ControlResult(false, WRITE_SETTINGS_MISSING)
        val level = levelPercent.coerceIn(0, 100)
        val value = ((level * 255 + 50) / 100).coerceIn(1, 255)
        val resolver = context.contentResolver
        putSystemInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val ok = putSystemInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return if (ok) {
            ControlResult(true, "Brightness set to $level%")
        } else {
            writeSettingsFailure(context, "Could not set the brightness")
        }
    }

    // ---- Auto-brightness (Settings.System.SCREEN_BRIGHTNESS_MODE) ----
    // Same WRITE_SETTINGS grant as brightness.
    fun setAutoBrightness(context: Context, enabled: Boolean): ControlResult {
        if (!canWriteSettings(context)) return ControlResult(false, WRITE_SETTINGS_MISSING)
        val mode = if (enabled) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } else {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }
        val ok = putSystemInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
        return if (ok) {
            ControlResult(true, "Auto-brightness turned ${if (enabled) "on" else "off"}")
        } else {
            writeSettingsFailure(context, "Could not change auto-brightness")
        }
    }

    // ---- Torch (CameraManager.setTorchMode) ----
    // Docs: developer.android.com/reference/android/hardware/camera2/CameraManager.
    // Needs the CAMERA permission (getCameraIdList enforces it on
    // API 31+). CONFLICT with Lost Mode: the device-admin camera
    // disable (LostMode -> DeviceAdminManager.setCameraDisabled) also
    // kills the flashlight on most phones, and setTorchMode then
    // throws CAMERA_DISABLED. Resolution: when Lost Mode is on with
    // admin active, torch-on reports the conflict plainly instead of
    // a bare camera error; torch-off still succeeds, because the
    // flashlight is already off when the camera is disabled.
    fun setTorch(context: Context, enabled: Boolean): ControlResult {
        if (!cameraGranted(context)) {
            return ControlResult(
                false,
                "Camera permission is not granted — allow it in this phone's Settings first",
            )
        }
        if (enabled && LostModeStore.isOn(context) && DeviceAdminManager.isAdminActive(context)) {
            return ControlResult(
                false,
                "Torch unavailable — Lost Mode disabled the camera on this phone",
            )
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ControlResult(false, "This phone has no flashlight")
            manager.setTorchMode(cameraId, enabled)
            ControlResult(true, "Torch turned ${if (enabled) "on" else "off"}")
        } catch (e: CameraAccessException) {
            if (e.reason == CameraAccessException.CAMERA_DISABLED) {
                if (!enabled) {
                    // The camera is disabled, so the flashlight is off
                    // already: the requested state holds.
                    ControlResult(true, "Torch off — the camera is disabled by Lost Mode, so the flashlight is off already")
                } else {
                    ControlResult(
                        false,
                        "Torch unavailable — the camera is disabled on this phone (Lost Mode)",
                    )
                }
            } else {
                ControlResult(false, "Could not reach the flashlight (${e.message})")
            }
        } catch (e: SecurityException) {
            ControlResult(
                false,
                "Camera permission is not granted — allow it in this phone's Settings first",
            )
        }
    }

    // ---- Ringer mode (AudioManager.setRingerMode) ----
    // Docs: developer.android.com/reference/android/media/AudioManager.
    // No special grant. Note: Do Not Disturb still silences calls even
    // in normal ringer mode, so the ack says so when DND is active.
    fun setRingerMode(context: Context, mode: String): ControlResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringerMode = when (mode) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        return try {
            audio.ringerMode = ringerMode
            val dndNote = if (ringerMode == AudioManager.RINGER_MODE_NORMAL &&
                notificationManager(context).currentInterruptionFilter !=
                NotificationManager.INTERRUPTION_FILTER_ALL
            ) {
                " — Do Not Disturb is still on, so calls may stay silent"
            } else {
                ""
            }
            ControlResult(true, "Ringer mode set to $mode$dndNote")
        } catch (e: SecurityException) {
            ControlResult(false, "Could not set the ringer mode (audio permission missing)")
        }
    }

    // ---- Do Not Disturb (NotificationManager.setInterruptionFilter) ----
    // Docs: developer.android.com/reference/android/app/NotificationManager.
    // Needs ACCESS_NOTIFICATION_POLICY plus the user's grant via
    // ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS; without it the call
    // throws SecurityException. Modes: off (all), alarms, priority,
    // none (total silence).
    fun setDnd(context: Context, mode: String): ControlResult {
        val manager = notificationManager(context)
        if (!manager.isNotificationPolicyAccessGranted) {
            return ControlResult(
                false,
                "Do Not Disturb access is not granted — allow it in this phone's Settings first",
            )
        }
        val filter = when (mode) {
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> NotificationManager.INTERRUPTION_FILTER_ALL
        }
        return try {
            manager.setInterruptionFilter(filter)
            val label = when (mode) {
                "alarms" -> "alarms only"
                "priority" -> "priority only"
                "none" -> "total silence"
                else -> "off"
            }
            ControlResult(true, "Do Not Disturb set to $label")
        } catch (e: SecurityException) {
            ControlResult(
                false,
                "Do Not Disturb access is not granted — allow it in this phone's Settings first",
            )
        }
    }

    // ---- Screen timeout (Settings.System.SCREEN_OFF_TIMEOUT) ----
    // Docs: developer.android.com/reference/android/provider/Settings.System.
    // Milliseconds of inactivity before sleep; same WRITE_SETTINGS
    // grant as brightness. Bounded by any device-admin maximum
    // time-to-lock, which the system enforces silently.
    fun setScreenTimeout(context: Context, seconds: Int): ControlResult {
        if (!canWriteSettings(context)) return ControlResult(false, WRITE_SETTINGS_MISSING)
        val clamped = seconds.coerceIn(15, 1800)
        val ok = putSystemInt(
            context.contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            clamped * 1000,
        )
        return if (ok) {
            val label = if (clamped < 60) "$clamped seconds" else "${clamped / 60} minutes"
            ControlResult(true, "Screen timeout set to $label")
        } else {
            writeSettingsFailure(context, "Could not set the screen timeout")
        }
    }

    // ---- Auto-rotate (Settings.System.ACCELEROMETER_ROTATION) ----
    // Docs: developer.android.com/reference/android/provider/Settings.System.
    // 1 = the accelerometer drives rotation, 0 = fixed. Same
    // WRITE_SETTINGS grant as brightness.
    fun setAutoRotate(context: Context, enabled: Boolean): ControlResult {
        if (!canWriteSettings(context)) return ControlResult(false, WRITE_SETTINGS_MISSING)
        val ok = putSystemInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            if (enabled) 1 else 0,
        )
        return if (ok) {
            ControlResult(true, "Auto-rotate turned ${if (enabled) "on" else "off"}")
        } else {
            writeSettingsFailure(context, "Could not change auto-rotate")
        }
    }

    // ---- Spoken lost message (TextToSpeech, see TtsSpeaker) ----
    // TTS plays on the media stream: raise it to max first so the
    // message is heard, and leave it there — a lost phone should stay
    // loud. Do Not Disturb does not silence the media stream.
    suspend fun speakMessage(context: Context, message: String): ControlResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0,
        )
        return TtsSpeaker.speak(context, message)
    }

    // ---- Wake screen (PowerManager wake lock) ----
    // Docs: developer.android.com/reference/android/os/PowerManager.
    // SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP turns the
    // screen on from the background (the modern setTurnScreenOn path
    // needs an activity, which a worker has none of). The lock is
    // deprecated since API 17 but still works on API 34; it only
    // wakes the screen, the keyguard stays locked. Needs the WAKE_LOCK
    // permission (normal, granted at install). The lock auto-releases
    // after 10 seconds; ON_AFTER_RELEASE keeps the screen lit a
    // little longer.
    @Suppress("DEPRECATION")
    fun wakeScreen(context: Context): ControlResult {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isInteractive) return ControlResult(true, "Screen is already on")
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "OpenGuard:wake",
        )
        return try {
            lock.acquire(10_000L)
            ControlResult(true, "Screen turned on (the lock screen stays locked)")
        } catch (e: SecurityException) {
            ControlResult(false, "Could not wake the screen (WAKE_LOCK permission missing)")
        }
    }

    // ---- Lost-message wallpaper (WallpaperManager.setBitmap) ----
    // Docs: developer.android.com/reference/android/app/WallpaperManager.
    // Draws the message onto a navy/gold bitmap and sets it as the
    // lock-screen wallpaper (FLAG_LOCK), the home wallpaper
    // (FLAG_SYSTEM), or both. Needs SET_WALLPAPER (normal, granted at
    // install). Honest limits: this REPLACES the previous wallpaper
    // (there is no way to read or restore it — getDrawable is blocked
    // from Android 13 and throws from Android 14), and some makers
    // ignore the lock/home split.
    fun setWallpaper(context: Context, message: String, which: String): ControlResult {
        val manager = WallpaperManager.getInstance(context)
        if (!manager.isWallpaperSupported) {
            return ControlResult(false, "Wallpapers are not supported on this phone")
        }
        if (!manager.isSetWallpaperAllowed) {
            return ControlResult(false, "Setting the wallpaper is not allowed on this phone")
        }
        val flag = when (which) {
            "home" -> WallpaperManager.FLAG_SYSTEM
            "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            else -> WallpaperManager.FLAG_LOCK
        }
        val width = manager.desiredMinimumWidth.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val height = manager.desiredMinimumHeight.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        return try {
            manager.setBitmap(drawLostWallpaper(width, height, message), null, true, flag)
            val target = when (which) {
                "home" -> "home-screen"
                "both" -> "home- and lock-screen"
                else -> "lock-screen"
            }
            ControlResult(
                true,
                "Lost message set as the $target wallpaper (this replaced the previous wallpaper)",
            )
        } catch (e: IOException) {
            ControlResult(false, "Could not set the wallpaper (${e.message})")
        } catch (e: SecurityException) {
            ControlResult(false, "Could not set the wallpaper (permission missing)")
        }
    }

    // ---- Bluetooth enable (best-effort) ----
    // Docs: developer.android.com/reference/android/bluetooth/BluetoothAdapter.
    // BluetoothAdapter.enable() is deprecated in API 33 and ALWAYS
    // returns false for apps targeting API 33+ (we target 34); only a
    // device owner, profile owner or system app may enable Bluetooth
    // silently. The only sanctioned path is the system enable dialog
    // (ACTION_REQUEST_ENABLE), which the person holding the phone must
    // accept — a thief can deny it. From the background worker the
    // dialog opens directly only with the overlay exemption
    // (SYSTEM_ALERT_WINDOW granted, as for the decoy lock); otherwise
    // a high-priority notification carries the dialog as its tap
    // action. Reading isEnabled needs BLUETOOTH_CONNECT on API 31+.
    fun bluetoothOn(context: Context): ControlResult {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        if (adapter == null) return ControlResult(false, "This phone has no Bluetooth")
        val enabled = try {
            adapter.isEnabled
        } catch (e: SecurityException) {
            return ControlResult(
                false,
                "Bluetooth permission is not granted — allow it in this phone's Settings first",
            )
        }
        if (enabled) return ControlResult(true, "Bluetooth is already on")
        if (DecoyLock.hasPermission(context)) {
            return try {
                context.startActivity(bluetoothEnableIntent())
                ControlResult(
                    true,
                    "Bluetooth prompt shown on the phone — the person holding it must accept " +
                        "(Android 13+ blocks silent Bluetooth enable)",
                )
            } catch (e: Exception) {
                showBluetoothPromptNotification(context)
            }
        }
        return showBluetoothPromptNotification(context)
    }

    private fun bluetoothEnableIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun showBluetoothPromptNotification(context: Context): ControlResult {
        val manager = notificationManager(context)
        if (manager.getNotificationChannel(SecurityConfig.BLUETOOTH_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SecurityConfig.BLUETOOTH_CHANNEL_ID,
                    context.getString(R.string.bluetooth_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val enable = PendingIntent.getActivity(
            context, 0,
            bluetoothEnableIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, SecurityConfig.BLUETOOTH_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.bluetooth_notification_title))
            .setContentText(context.getString(R.string.bluetooth_notification_text))
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentIntent(enable)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        return try {
            manager.notify(SecurityConfig.BLUETOOTH_NOTIFICATION_ID, notification)
            ControlResult(
                true,
                "Bluetooth prompt sent to the phone — tap the notification, then accept, " +
                    "to turn Bluetooth on (Android 13+ blocks silent Bluetooth enable)",
            )
        } catch (e: SecurityException) {
            ControlResult(
                false,
                "Could not show the Bluetooth prompt — notifications are off, and " +
                    "Android 13+ blocks silent Bluetooth enable",
            )
        }
    }

    // ---- Helpers ----

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // putInt throws SecurityException when WRITE_SETTINGS was revoked
    // between the canWrite check and the write; false covers both.
    private fun putSystemInt(resolver: ContentResolver, key: String, value: Int): Boolean =
        try {
            Settings.System.putInt(resolver, key, value)
        } catch (e: SecurityException) {
            false
        }

    // A failed write is either a revoked grant (name it) or a real
    // failure (say so): re-check the grant to tell them apart.
    private fun writeSettingsFailure(context: Context, fallback: String): ControlResult =
        if (!canWriteSettings(context)) {
            ControlResult(false, WRITE_SETTINGS_MISSING)
        } else {
            ControlResult(false, fallback)
        }

    // Navy background, gold title + rule, wrapped white message,
    // centred — the app's own colours, drawn with plain Canvas.
    private fun drawLostWallpaper(width: Int, height: Int, message: String): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x10, 0x1B, 0x2D))
        val gold = Color.rgb(0xC6, 0xA4, 0x6A)
        val centerX = width / 2f
        val titleY = height * 0.38f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gold
            textSize = width * 0.09f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("LOST PHONE", centerX, titleY, titlePaint)
        val rulePaint = Paint().apply { color = gold }
        val ruleY = titleY + height * 0.02f
        canvas.drawRect(centerX - width * 0.15f, ruleY, centerX + width * 0.15f, ruleY + 4f, rulePaint)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = width * 0.055f
            textAlign = Paint.Align.CENTER
        }
        val layout = StaticLayout.Builder.obtain(message, 0, message.length, textPaint, (width * 0.84).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.3f)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate(centerX - layout.width / 2f, titleY + height * 0.06f)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }
}
