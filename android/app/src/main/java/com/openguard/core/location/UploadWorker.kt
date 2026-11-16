package com.openguard.core.location

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.openguard.OpenGuardApp
import com.openguard.core.common.TrackingConfig
import com.openguard.core.database.LocationEntity
import com.openguard.core.network.HeartbeatRequest
import com.openguard.core.network.LocationPointDto
import com.openguard.core.network.UploadLocationsRequest
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads queued locations and sends a heartbeat.
 *
 * DATA-LOSS RULE: queued rows are deleted ONLY after the server returns
 * success for that batch. Network failures retry with exponential backoff;
 * 4xx failures (bad token, bad data) fail without retry so a poisoned
 * batch cannot spin forever.
 */
class UploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as OpenGuardApp
        val deviceId = app.tokenStore.deviceId
        val deviceToken = app.tokenStore.deviceToken
        // Not registered as a device yet: nothing to do.
        if (deviceId == null || deviceToken == null) return Result.success()
        val auth = "Bearer $deviceToken"
        return try {
            val dao = app.database.locationDao()
            val batch = dao.oldest(TrackingConfig.UPLOAD_BATCH_SIZE)
            if (batch.isNotEmpty()) {
                val response = app.api.uploadLocations(
                    auth, deviceId, UploadLocationsRequest(batch.map { it.toDto() }),
                )
                if (!response.isSuccessful) {
                    // 429 / 5xx: retry later. Anything else: fail, keep the rows.
                    return if (response.code() == 429 || response.code() >= 500) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
                dao.deleteByIds(batch.map { it.id })
            }
            // Heartbeat after every pass, even with an empty queue, so
            // last-seen / battery stay fresh on the dashboard.
            heartbeatBody(applicationContext)?.let { body ->
                app.api.heartbeat(auth, deviceId, body)
            }
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    companion object {
        /** Periodic upload every 15 minutes (the WorkManager minimum). */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                TrackingConfig.UPLOAD_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** One-off upload attempt, e.g. right after new fixes arrive. */
        fun enqueueOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(networkConstraint())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TrackingConfig.UPLOAD_WORK_NAME)
        }

        private fun networkConstraint(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Null when the battery level is unknown — heartbeat needs 0-100. */
        private fun heartbeatBody(context: Context): HeartbeatRequest? {
            val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level < 0 || level > 100) return null
            val status = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            return HeartbeatRequest(
                batteryLevel = level,
                batteryCharging = charging,
                networkType = networkType(context),
            )
        }

        private fun networkType(context: Context): String? {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return null
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        }
    }
}

private fun LocationEntity.toDto(): LocationPointDto = LocationPointDto(
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    recordedAt = recordedAt,
    provider = provider,
)
