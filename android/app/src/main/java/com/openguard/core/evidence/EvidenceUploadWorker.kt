package com.openguard.core.evidence

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.openguard.OpenGuardApp
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Uploads recorded clips: bytes to the owner's Google Drive (when
 * connected), metadata to our server. Same outbox pattern as
 * UploadWorker — a clip row is marked posted ONLY after the server
 * confirms the metadata. The local file stays until the storage cap
 * prunes it; Drive copies survive in Drive.
 *
 * - Drive connected: upload bytes first, remember the Drive file id on
 *   the row (so a later retry never uploads the same clip twice), then
 *   post metadata with storage=drive.
 * - Drive not connected: post metadata with storage=local — the clip
 *   stays on the device and the dashboard says "on device".
 * - Drive refresh failed / network error / 5xx: retry with backoff.
 * - Metadata 4xx: fail without retry (poisoned data must not spin).
 *
 * One-time work, network-constrained, appended to a unique chain so
 * runs never overlap: two concurrent runs could upload the same clip
 * to Drive twice and post its metadata twice.
 */
class EvidenceUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as OpenGuardApp
        val deviceId = app.tokenStore.deviceId
        val deviceToken = app.tokenStore.deviceToken
        // Not registered as a device yet: nothing to do.
        if (deviceId == null || deviceToken == null) return Result.success()
        val auth = "Bearer $deviceToken"
        val dao = app.database.evidenceClipDao()
        return try {
            for (clip in dao.pending(MAX_BATCH)) {
                val file = File(EvidenceStorage.dir(applicationContext), clip.fileName)
                if (!file.exists()) {
                    // Pruned by the storage cap before upload: drop the row.
                    dao.deleteById(clip.id)
                    continue
                }
                var storage = clip.storage
                var driveFileId = clip.driveFileId
                var driveLink = clip.driveLink
                var uploadedAt = clip.uploadedAt
                if (driveFileId == null) {
                    when (val token = DriveAuth.accessToken(applicationContext)) {
                        is DriveToken.Ok -> {
                            val uploaded = DriveUploader.upload(
                                applicationContext, token.token, file,
                                DriveUploader.mimeTypeFor(clip.camera),
                            )
                            driveFileId = uploaded.id
                            driveLink = uploaded.link
                            uploadedAt = Instant.now().toString()
                            storage = EvidenceConfig.STORAGE_DRIVE
                            dao.markDriveUploaded(clip.id, uploaded.id, uploaded.link, uploadedAt)
                        }
                        is DriveToken.NotConnected -> {
                            storage = EvidenceConfig.STORAGE_LOCAL
                        }
                        is DriveToken.RefreshFailed -> return Result.retry()
                    }
                }
                val response = app.evidenceApi.uploadMetadata(
                    auth, deviceId,
                    EvidenceMetadataRequest(
                        camera = clip.camera,
                        recordedAt = clip.recordedAt,
                        durationS = clip.durationS,
                        sizeBytes = clip.sizeBytes,
                        storage = storage,
                        driveFileId = driveFileId,
                        driveLink = driveLink,
                        uploadedAt = uploadedAt,
                    ),
                )
                if (!response.isSuccessful) {
                    return if (response.code() == 429 || response.code() >= 500) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
                dao.markMetadataPosted(clip.id, storage)
            }
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "openguard-evidence-upload"

        /** Clips per run; runs chain, so a backlog drains over runs. */
        private const val MAX_BATCH = 20

        /** One-off upload attempt, e.g. right after a clip is recorded. */
        fun enqueueOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<EvidenceUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND,
                request,
            )
        }
    }
}
