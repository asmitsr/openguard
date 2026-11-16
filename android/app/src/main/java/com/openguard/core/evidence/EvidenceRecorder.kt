package com.openguard.core.evidence

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.openguard.core.security.DeviceAdminManager
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Records one evidence clip: video + audio with CameraX, or audio-only
 * with MediaRecorder when the camera is unavailable.
 *
 * CameraX was chosen over raw Camera2 (see the workstream report): the
 * VideoCapture + Recorder API is the documented Jetpack path for short
 * clips, it owns the camera session lifecycle, and it needs a fraction
 * of the Camera2 session/callback code. No Preview use case is bound —
 * recording works without a viewfinder.
 *
 * Front and back cameras are NEVER recorded at the same time: most
 * phones cannot stream both cameras concurrently, so the service
 * alternates one camera per clip instead.
 *
 * Android's green camera/mic indicator always shows while recording.
 * That is platform-mandated and cannot be hidden — evidence is
 * owner-armed and visible by design, never stealth capture.
 */
object EvidenceRecorder {

    data class ClipResult(
        val file: File,
        /** EvidenceConfig.CAMERA_FRONT, CAMERA_BACK or CAMERA_AUDIO. */
        val camera: String,
        /** ISO-8601 UTC, when the clip started. */
        val recordedAt: String,
        val durationS: Int,
    )

    /**
     * Records one clip. Video first (front or back per [front]);
     * audio-only when the camera is disabled by device admin (Lost Mode
     * does this when admin is active), the camera permission is missing,
     * or video recording fails. Returns null when nothing could be
     * recorded (no microphone permission, or both paths failed).
     */
    suspend fun record(context: Context, front: Boolean): ClipResult? {
        val dir = EvidenceStorage.dir(context)
        val recordedAt = Instant.now().toString()
        val stamp = System.currentTimeMillis()
        if (hasCameraPermission(context) && !isCameraDisabledByAdmin(context)) {
            val camera = if (front) EvidenceConfig.CAMERA_FRONT else EvidenceConfig.CAMERA_BACK
            val file = File(dir, "clip_" + stamp + "_" + camera + ".mp4")
            if (recordVideo(context, file, front)) {
                return ClipResult(file, camera, recordedAt, EvidenceConfig.CLIP_DURATION_S)
            }
            file.delete()
        }
        if (hasMicPermission(context)) {
            val file = File(dir, "clip_" + stamp + "_" + EvidenceConfig.CAMERA_AUDIO + ".m4a")
            if (recordAudio(context, file)) {
                return ClipResult(
                    file, EvidenceConfig.CAMERA_AUDIO, recordedAt, EvidenceConfig.CLIP_DURATION_S,
                )
            }
            file.delete()
        }
        return null
    }

    /**
     * One CameraX video clip: bind VideoCapture to the chosen camera,
     * record for the clip duration, stop, and wait for the Finalize
     * event. False when the camera could not be opened or the file is
     * empty — the caller falls back to audio-only.
     */
    private suspend fun recordVideo(context: Context, file: File, front: Boolean): Boolean {
        val provider = awaitCameraProvider(context) ?: return false
        val owner = ServiceLifecycleOwner()
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.SD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)
        var recording: Recording? = null
        return try {
            owner.resume()
            val selector = if (front) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            provider.bindToLifecycle(owner, selector, videoCapture)
            val finalize = CompletableDeferred<VideoRecordEvent.Finalize>()
            recording = videoCapture.output
                .prepareRecording(context, FileOutputOptions.Builder(file).build())
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) finalize.complete(event)
                }
            try {
                delay(EvidenceConfig.CLIP_DURATION_MS)
            } finally {
                recording?.stop()
            }
            val result = withTimeoutOrNull(EvidenceConfig.FINALIZE_TIMEOUT_MS) {
                finalize.await()
            }
            result != null && !result.hasError() && file.exists() && file.length() > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            runCatching { recording?.close() }
            runCatching { provider.unbindAll() }
            owner.destroy()
        }
    }

    /**
     * Audio-only clip with MediaRecorder: AAC in an MP4 container. The
     * normal path when device admin disabled the camera during Lost
     * Mode — the microphone still works.
     */
    private suspend fun recordAudio(context: Context, file: File): Boolean {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            delay(EvidenceConfig.CLIP_DURATION_MS)
            runCatching { recorder.stop() }
            file.exists() && file.length() > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            runCatching { recorder.release() }
        }
    }

    private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider? =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { cont.resume(runCatching { future.get() }.getOrNull()) },
                ContextCompat.getMainExecutor(context),
            )
        }

    /**
     * Device admin disabled the camera: Lost Mode does this when admin
     * is active (it also disables the flashlight — a theft deterrent).
     * Any error reads as "not disabled": CameraX then fails naturally
     * and the audio fallback still runs.
     */
    private fun isCameraDisabledByAdmin(context: Context): Boolean {
        return try {
            if (!DeviceAdminManager.isAdminActive(context)) return false
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.getCameraDisabled(DeviceAdminManager.componentName(context))
        } catch (e: SecurityException) {
            false
        }
    }

    private fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

/**
 * CameraX binds use cases to a LifecycleOwner; a Service has none, so
 * this one drives the lifecycle by hand: RESUMED while a clip records,
 * DESTROYED when the recording ends. The camera opens on bind and
 * closes on unbind.
 */
private class ServiceLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun resume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
