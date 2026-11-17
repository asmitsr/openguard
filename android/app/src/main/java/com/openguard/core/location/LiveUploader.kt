package com.openguard.core.location

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.openguard.OpenGuardApp
import com.openguard.core.common.TrackingConfig
import com.openguard.core.database.LocationEntity
import com.openguard.core.network.HeartbeatRequest
import com.openguard.core.network.LocationPointDto
import com.openguard.core.network.UploadLocationsRequest
import com.openguard.core.security.LostModeStore
import java.io.IOException

/**
 * Direct (non-WorkManager) upload path used by the foreground
 * LocationService: locations go out seconds after they are recorded
 * instead of waiting up to 15 minutes for the periodic worker.
 *
 * The Room outbox stays the source of truth: fixes are written to the
 * outbox first, uploaded from it, and deleted ONLY after the server
 * confirms the batch. On failure the rows stay queued and the periodic
 * UploadWorker backfills them, so a failed direct upload can never lose
 * a location. UploadWorker calls [flushOutbox] too, so there is exactly
 * one upload implementation.
 *
 * LIVE TRACKING MODE: when the owner enables it (Settings toggle) or
 * while Lost Mode is on, the service requests fixes every ~20 seconds
 * and uploads each batch directly plus a heartbeat, so the map, the
 * last-seen time and the battery level stay fresh. Normal mode keeps
 * the battery-friendly 60-second fixes and batched uploads.
 */
object LiveUploader {

    /** Fix interval while live tracking is on. */
    const val LIVE_LOCATION_INTERVAL_MS = 20_000L

    /** Fastest accepted fix rate while live tracking is on. */
    const val LIVE_LOCATION_MIN_INTERVAL_MS = 15_000L

    enum class FlushOutcome {
        /** Nothing queued. */
        EMPTY,

        /** Batch uploaded and deleted from the outbox. */
        UPLOADED,

        /** Transient failure (no network, 429, 5xx): retry later. */
        RETRYABLE,

        /** Permanent failure (4xx): keep the rows, do not spin. */
        FAILED,
    }

    /**
     * True when the owner enabled live tracking, or while Lost Mode is
     * on (Lost Mode turns live mode on by itself). Reads the same
     * preference key the Settings toggle writes via
     * SecurityPrefs.isLiveTrackingEnabled, so this file compiles
     * standalone and both readers always agree.
     */
    fun isLiveMode(context: Context): Boolean =
        liveTrackingEnabled(context) || LostModeStore.isOn(context)

    fun liveTrackingEnabled(context: Context): Boolean =
        context.getSharedPreferences("openguard_security", Context.MODE_PRIVATE)
            .getBoolean(KEY_LIVE_TRACKING_ENABLED, false)

    fun locationIntervalMs(context: Context): Long =
        if (isLiveMode(context)) LIVE_LOCATION_INTERVAL_MS else TrackingConfig.LOCATION_INTERVAL_MS

    fun locationMinIntervalMs(context: Context): Long =
        if (isLiveMode(context)) {
            LIVE_LOCATION_MIN_INTERVAL_MS
        } else {
            TrackingConfig.LOCATION_MIN_INTERVAL_MS
        }

    /**
     * Uploads the oldest queued batch directly via Retrofit and deletes
     * it only on server confirmation. Same data-loss rule as the worker.
     */
    suspend fun flushOutbox(app: OpenGuardApp): FlushOutcome {
        val deviceId = app.tokenStore.deviceId ?: return FlushOutcome.EMPTY
        val deviceToken = app.tokenStore.deviceToken ?: return FlushOutcome.EMPTY
        val dao = app.database.locationDao()
        val batch = dao.oldest(TrackingConfig.UPLOAD_BATCH_SIZE)
        if (batch.isEmpty()) return FlushOutcome.EMPTY
        return try {
            val response = app.api.uploadLocations(
                "Bearer $deviceToken", deviceId, UploadLocationsRequest(batch.map { it.toDto() }),
            )
            if (!response.isSuccessful) {
                if (response.code() == 429 || response.code() >= 500) {
                    FlushOutcome.RETRYABLE
                } else {
                    FlushOutcome.FAILED
                }
            } else {
                dao.deleteByIds(batch.map { it.id })
                FlushOutcome.UPLOADED
            }
        } catch (e: IOException) {
            FlushOutcome.RETRYABLE
        } catch (e: Exception) {
            FlushOutcome.FAILED
        }
    }

    /**
     * Sends one heartbeat (battery, network, permission state) directly.
     * Used in live mode so the device list stays fresh between worker
     * runs. False when there is nothing to send or the send failed.
     */
    suspend fun sendHeartbeat(app: OpenGuardApp): Boolean {
        val deviceId = app.tokenStore.deviceId ?: return false
        val deviceToken = app.tokenStore.deviceToken ?: return false
        val body = heartbeatBody(app) ?: return false
        return try {
            app.api.heartbeat("Bearer $deviceToken", deviceId, body).isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /** (level 0-100, charging) or null when the level is unknown. */
    internal fun batteryInfo(context: Context): Pair<Int, Boolean>? {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level < 0 || level > 100) return null
        val status = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return level to charging
    }

    /** Null when the battery level is unknown — heartbeat needs 0-100. */
    internal fun heartbeatBody(context: Context): HeartbeatRequest? {
        val (level, charging) = batteryInfo(context) ?: return null
        val permission = when {
            TrackingPermissions.hasFineLocation(context) &&
                TrackingPermissions.hasBackgroundLocation(context) -> "all"
            TrackingPermissions.hasFineLocation(context) -> "foreground"
            else -> "none"
        }
        val health = DeviceHealthCollector.collect(context)
        return HeartbeatRequest(
            batteryLevel = level,
            batteryCharging = charging,
            networkType = networkType(context),
            locationPermission = permission,
            locationEnabled = locationProvidersEnabled(context),
            storageTotalBytes = health.storageTotalBytes,
            storageFreeBytes = health.storageFreeBytes,
            ramTotalBytes = health.ramTotalBytes,
            ramFreeBytes = health.ramFreeBytes,
            batteryTemperatureC = health.batteryTemperatureC,
            bootedAt = health.bootedAt,
            notificationsEnabled = health.notificationsEnabled,
            lostMode = LostModeStore.isOn(context),
        )
    }

    internal fun locationProvidersEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
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

    // Same key as SecurityPrefs.isLiveTrackingEnabled (shared-file spec).
    private const val KEY_LIVE_TRACKING_ENABLED = "live_tracking_enabled"
}

private fun LocationEntity.toDto(): LocationPointDto = LocationPointDto(
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    recordedAt = recordedAt,
    provider = provider,
)
