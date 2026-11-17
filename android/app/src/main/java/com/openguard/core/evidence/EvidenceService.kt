package com.openguard.core.evidence

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openguard.MainActivity
import com.openguard.OpenGuardApp
import com.openguard.R
import com.openguard.core.database.EvidenceClipEntity
import com.openguard.core.security.LostModeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that records short evidence clips while Lost Mode
 * is on — and ONLY then. Started by LostMode.turnOn, stopped by
 * LostMode.turnOff, restarted after a reboot by BootReceiver and by
 * the UploadWorker backstop while Lost Mode stays on.
 *
 * Behaviour: one 12-second clip every 2 or 5 minutes (Settings
 * interval, default 5), alternating front and back camera — most
 * phones cannot record both cameras at once. Clips include audio.
 * When device admin disabled the camera (Lost Mode does this when
 * admin is active), clips are audio-only and the metadata says so.
 *
 * The ACTION_CAPTURE_PHOTO path (Intruder Selfie) is the one exception
 * to the Lost-Mode-only rule: one front-camera still, fired by failed
 * unlock attempts, with Lost Mode on or off.
 *
 * CONSENT: evidence is owner-armed (Lost Mode is an explicit owner
 * action), the persistent notification is visible the whole time, and
 * Android's green camera/mic indicator always shows while recording.
 * There is no stealth or hidden capture anywhere in this app. Clip
 * bytes go to the owner's own Google Drive; our server stores metadata
 * only (see EvidenceUploadWorker).
 */
class EvidenceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recordMutex = Mutex()
    private var loopJob: Job? = null
    // Alternate cameras per clip, starting with the front camera: the
    // face of whoever holds the phone is the most useful first frame.
    private var nextFront = true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLoop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CAPTURE_NOW -> {
                // Capture now: record one clip immediately, then keep
                // the regular interval loop running.
                if (ensureForeground()) {
                    scope.launch { recordOneClip() }
                }
            }
            ACTION_CAPTURE_PHOTO -> {
                // Intruder selfie: one front-camera still. No Lost Mode
                // requirement — keyguard failures usually happen with
                // Lost Mode off. When Lost Mode is on, the loop is
                // already running and this just captures; when it is
                // off, the service goes foreground camera-only for the
                // single photo and stops itself right after.
                if (LostModeStore.isOn(applicationContext)) {
                    if (hasMicPermission()) {
                        if (ensureForeground()) {
                            scope.launch { captureOnePhoto() }
                        }
                    } else if (ensurePhotoForeground()) {
                        // Mic revoked: clips cannot run, but a still
                        // photo needs the camera only. Capture
                        // camera-only, then stop; the UploadWorker
                        // backstop restarts the service while Lost
                        // Mode stays on.
                        scope.launch {
                            captureOnePhoto()
                            stopSelf()
                        }
                    }
                } else if (ensurePhotoForeground()) {
                    scope.launch {
                        captureOnePhoto()
                        stopSelf()
                    }
                }
            }
            else -> ensureForeground() // ACTION_START or null (START_STICKY restart)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLoop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Promotes the service to foreground and starts the recording loop.
     * False when the service cannot run: Lost Mode is off, or the
     * microphone permission is missing (without it there is nothing to
     * record — video needs audio and the fallback is audio-only). A
     * missing camera permission only drops the camera type: clips are
     * audio-only and the service runs as microphone-only.
     */
    private fun ensureForeground(): Boolean {
        if (!LostModeStore.isOn(applicationContext)) {
            stopSelf()
            return false
        }
        if (!hasMicPermission()) {
            stopSelf()
            return false
        }
        createChannel()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (hasCameraPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        try {
            ServiceCompat.startForeground(
                this,
                EvidenceConfig.EVIDENCE_NOTIFICATION_ID,
                buildNotification(),
                types,
            )
        } catch (e: SecurityException) {
            // Runtime prerequisite revoked between the check and the call.
            stopSelf()
            return false
        }
        startLoop()
        return true
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                if (!LostModeStore.isOn(applicationContext)) {
                    stopSelf()
                    return@launch
                }
                recordOneClip()
                delay(EvidencePrefs.intervalMs(applicationContext))
            }
        }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * Records one clip and queues it for upload. Serialized: the
     * interval loop and capture-now can never record at the same time.
     */
    private suspend fun recordOneClip() {
        recordMutex.withLock {
            if (!LostModeStore.isOn(applicationContext)) return
            val app = applicationContext as OpenGuardApp
            val clip = EvidenceRecorder.record(applicationContext, nextFront) ?: return
            if (clip.camera != EvidenceConfig.CAMERA_AUDIO) {
                nextFront = !nextFront
            }
            val dao = app.database.evidenceClipDao()
            dao.insert(
                EvidenceClipEntity(
                    fileName = clip.file.name,
                    camera = clip.camera,
                    recordedAt = clip.recordedAt,
                    durationS = clip.durationS,
                    sizeBytes = clip.file.length(),
                ),
            )
            EvidenceStorage.enforceCap(applicationContext, dao)
            EvidenceUploadWorker.enqueueOneTime(applicationContext)
        }
    }

    /**
     * Foreground for the photo-only path (Lost Mode off): camera type
     * only — a still photo has no audio, and no interval loop starts.
     * False when the camera permission is missing: nothing to capture.
     */
    private fun ensurePhotoForeground(): Boolean {
        if (!hasCameraPermission()) {
            stopSelf()
            return false
        }
        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                EvidenceConfig.EVIDENCE_NOTIFICATION_ID,
                buildPhotoNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } catch (e: SecurityException) {
            stopSelf()
            return false
        }
        return true
    }

    /**
     * Captures one front-camera still and queues it for upload,
     * serialized with clip recording via the same mutex. Unlike
     * recordOneClip this does NOT require Lost Mode: intruder selfies
     * fire on keyguard failures, usually with Lost Mode off. The photo
     * is stored as an EvidenceClipEntity with duration 1s — the server
     * rejects duration 0, so 1 is the minimum for a still.
     */
    private suspend fun captureOnePhoto() {
        recordMutex.withLock {
            val app = applicationContext as OpenGuardApp
            val photo = EvidenceRecorder.capturePhoto(applicationContext) ?: return
            val dao = app.database.evidenceClipDao()
            dao.insert(
                EvidenceClipEntity(
                    fileName = photo.file.name,
                    camera = photo.camera,
                    recordedAt = photo.recordedAt,
                    durationS = photo.durationS,
                    sizeBytes = photo.file.length(),
                ),
            )
            EvidenceStorage.enforceCap(applicationContext, dao)
            EvidenceUploadWorker.enqueueOneTime(applicationContext)
        }
    }

    /**
     * Photo-only notification: must NOT say "Lost Mode" — it is off
     * on this path. Hardcoded text, matching the DecoyLock and
     * LostModeActivity convention.
     */
    private fun buildPhotoNotification(): Notification {
        return NotificationCompat.Builder(this, EvidenceConfig.EVIDENCE_CHANNEL_ID)
            .setContentTitle("OpenGuard")
            .setContentText("Capturing intruder photo")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(EvidenceConfig.EVIDENCE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    EvidenceConfig.EVIDENCE_CHANNEL_ID,
                    getString(R.string.evidence_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.evidence_channel_description) },
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, EvidenceConfig.EVIDENCE_CHANNEL_ID)
            .setContentTitle(getString(R.string.evidence_notification_title))
            .setContentText(getString(R.string.evidence_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.openguard.action.START_EVIDENCE"
        const val ACTION_STOP = "com.openguard.action.STOP_EVIDENCE"
        const val ACTION_CAPTURE_NOW = "com.openguard.action.EVIDENCE_CAPTURE_NOW"
        const val ACTION_CAPTURE_PHOTO = "com.openguard.action.EVIDENCE_CAPTURE_PHOTO"

        /**
         * Start from a foreground context, or from Lost Mode / boot /
         * the upload worker under the SYSTEM_ALERT_WINDOW /
         * BOOT_COMPLETED background-start exemptions. Background
         * callers MUST wrap this in try/catch (IllegalStateException +
         * SecurityException): the start can still be denied, e.g. when
         * the microphone permission was revoked.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EvidenceService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, EvidenceService::class.java).setAction(ACTION_STOP),
            )
        }

        /**
         * Records one clip right now (dashboard "Capture now" button,
         * via the evidence_capture command). No-op when Lost Mode is
         * off: the service stops itself instead of recording.
         */
        fun captureNow(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EvidenceService::class.java).setAction(ACTION_CAPTURE_NOW),
            )
        }

        /**
         * Captures one front-camera still (Intruder Selfie). Works
         * with Lost Mode on or off. Background callers MUST wrap this
         * in try/catch (IllegalStateException + SecurityException):
         * the foreground-service start can be denied.
         */
        fun capturePhoto(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EvidenceService::class.java).setAction(ACTION_CAPTURE_PHOTO),
            )
        }
    }
}
