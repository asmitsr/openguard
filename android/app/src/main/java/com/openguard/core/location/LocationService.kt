package com.openguard.core.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.openguard.MainActivity
import com.openguard.OpenGuardApp
import com.openguard.R
import com.openguard.core.common.TrackingConfig
import com.openguard.core.database.AppDatabase
import com.openguard.core.database.LocationEntity
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-local tracking state, observed by the UI. */
object TrackingState {
    private val _isTracking = MutableStateFlow(false)
    val isTracking = _isTracking.asStateFlow()
    internal fun set(value: Boolean) {
        _isTracking.value = value
    }
}

/**
 * Foreground service that records this device's location into the Room
 * outbox queue. Uploads happen separately via UploadWorker.
 *
 * CONSENT: the service only ever runs after the user completes the consent
 * screen, and a persistent notification ("OpenGuard is tracking this
 * device") is visible the whole time, with a Stop action. There is no
 * silent or hidden tracking anywhere in this app.
 */
class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var database: AppDatabase

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            scope.launch {
                for (location in result.locations) {
                    database.locationDao().insert(location.toEntity())
                }
                // Try an upload soon; the periodic worker is the backstop.
                UploadWorker.enqueueOneTime(applicationContext)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as OpenGuardApp
        fused = LocationServices.getFusedLocationProviderClient(this)
        database = app.database
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }
        startTracking()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        fused.removeLocationUpdates(locationCallback)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        TrackingState.set(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun startTracking() {
        createChannel()
        ServiceCompat.startForeground(
            this,
            TrackingConfig.TRACKING_NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        if (!TrackingPermissions.hasFineLocation(this)) {
            // Permission was revoked while tracking: stop instead of crashing.
            stopSelf()
            return
        }
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            TrackingConfig.LOCATION_INTERVAL_MS,
        )
            .setMinUpdateIntervalMillis(TrackingConfig.LOCATION_MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(TrackingConfig.LOCATION_MIN_DISTANCE_M)
            .build()
        try {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            TrackingState.set(true)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun stopTracking() {
        fused.removeLocationUpdates(locationCallback)
        TrackingState.set(false)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(TrackingConfig.TRACKING_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    TrackingConfig.TRACKING_CHANNEL_ID,
                    getString(R.string.tracking_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.tracking_channel_description) },
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
            Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, TrackingConfig.TRACKING_CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(R.string.tracking_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.tracking_notification_stop), stop)
            .build()
    }

    private fun Location.toEntity(): LocationEntity = LocationEntity(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        recordedAt = Instant.ofEpochMilli(time).toString(),
        provider = provider,
    )

    companion object {
        const val ACTION_START = "com.openguard.action.START_TRACKING"
        const val ACTION_STOP = "com.openguard.action.STOP_TRACKING"

        /** Start from a foreground context only (button tap in the app). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocationService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
