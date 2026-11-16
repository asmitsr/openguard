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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
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
import com.openguard.core.common.SecurityConfig
import com.openguard.core.common.TrackingConfig
import com.openguard.core.database.AppDatabase
import com.openguard.core.database.LocationEntity
import com.openguard.core.security.DecoyLock
import com.openguard.core.security.LostModeStore
import com.openguard.core.security.SecurityEvents
import com.openguard.core.theft.TheftResponse
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
 * outbox queue and uploads it directly (LiveUploader) seconds after each
 * fix; UploadWorker remains the offline-retry backfill. While tracking
 * runs it also listens for instant-push wake-ups (NtfyClient) so remote
 * commands arrive in seconds, and hosts the theft response
 * (TheftResponse: wake counter + auto-call, chaos mode).
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
    private var connectivityManager: ConnectivityManager? = null
    // startTracking runs on every start intent; the watchdog must be
    // launched only once per service instance.
    private var watchdogStarted = false
    private var ntfyClient: NtfyClient? = null
    private var liveModeWatcherStarted = false

    // Connectivity-triggered command fetch: when the network comes back,
    // poll for pending commands right away instead of waiting up to 15
    // minutes for the periodic worker. The same one-time run also flushes
    // the Room location outbox (UploadWorker uploads locations first),
    // so locations queued while offline go out on reconnect. Only active
    // while tracking runs — this service (and the callback) stops when
    // tracking stops. No manifest CONNECTIVITY_ACTION receiver: it no
    // longer fires on Android 7+.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            UploadWorker.enqueueOneTime(applicationContext)
        }

        override fun onLost(network: Network) {
            // onLost fires per network: a WiFi -> cellular handover loses
            // one network while another is available. Only act when NO
            // network is left — then record the offline transition and,
            // if WiFi is off, notify the owner (WifiReconnect).
            if (connectivityManager?.activeNetwork != null) return
            scope.launch { WifiReconnect.onOffline(applicationContext, database) }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            scope.launch {
                for (location in result.locations) {
                    database.locationDao().insert(location.toEntity())
                }
                // Fast path: upload directly instead of waiting up to 15
                // minutes for the periodic worker. The outbox + worker
                // remain the backstop when this fails.
                val app = applicationContext as OpenGuardApp
                LiveUploader.flushOutbox(app)
                if (LiveUploader.isLiveMode(applicationContext)) {
                    LiveUploader.sendHeartbeat(app)
                }
                UploadWorker.enqueueOneTime(applicationContext)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as OpenGuardApp
        fused = LocationServices.getFusedLocationProviderClient(this)
        database = app.database
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager?.registerNetworkCallback(
            NetworkRequest.Builder().build(),
            networkCallback,
        )
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
        ntfyClient?.stop()
        ntfyClient = null
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            // Callback was never registered; nothing to unregister.
        }
        connectivityManager = null
        // Stop the theft response first: unregisters the screen-on
        // receiver and stops any chaos alarm before the scope dies.
        TheftResponse.stop(applicationContext)
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
            // Permission was revoked while tracking: record the security
            // event (NonCancellable so the insert survives stopSelf's
            // scope cancellation), then stop instead of crashing.
            scope.launch(NonCancellable) {
                SecurityEvents.record(database, "location_permission_revoked", confirmed = true)
            }
            stopSelf()
            return
        }
        if (!requestLocationUpdates()) return
        // Instant push: register this device's ntfy topic, then listen
        // for wake-ups (commands arrive in seconds; the 15-minute poll
        // stays as the fallback). Runs in the service scope: stops with it.
        if (ntfyClient == null) {
            ntfyClient = NtfyClient(scope) { UploadWorker.enqueueOneTime(applicationContext) }
            scope.launch {
                NtfyClient.ensureRegistered(application as OpenGuardApp)
                ntfyClient?.start(applicationContext)
            }
        }
        // Live-mode watcher: Lost Mode turns live tracking on by itself.
        // Re-request location updates at the new interval when it flips.
        if (!liveModeWatcherStarted) {
            liveModeWatcherStarted = true
            scope.launch {
                var lastLive = LiveUploader.isLiveMode(applicationContext)
                LostModeStore.isOnFlow.collect {
                    val live = LiveUploader.isLiveMode(applicationContext)
                    if (live != lastLive) {
                        lastLive = live
                        requestLocationUpdates()
                    }
                }
            }
        }
        // Decoy watchdog: while tracking runs, re-show the Lost Mode
        // decoy overlay if it was dismissed (e.g. the process restarted
        // or the overlay was removed). Cheap: one prefs read per tick.
        // Dies with the service scope in onDestroy.
        if (!watchdogStarted) {
            watchdogStarted = true
            scope.launch {
                while (true) {
                    if (LostModeStore.isOn(applicationContext)) {
                        DecoyLock.ensureShown(applicationContext)
                    }
                    delay(SecurityConfig.DECOY_WATCHDOG_INTERVAL_MS)
                }
            }
        }
        // Theft response (wake counter + auto-call, chaos mode): hosted
        // by this service because ACTION_SCREEN_ON can only be received
        // by a dynamically registered receiver, and the chaos timer must
        // outlive any worker run. start() is idempotent; the supervisor
        // inside gates everything on Lost Mode being on.
        TheftResponse.start(applicationContext, scope)
    }

    /**
     * Requests location updates at the current interval (20s in live
     * mode, 60s otherwise). Re-requesting for the same callback replaces
     * the previous request. False when the permission is gone (the
     * service is stopping).
     */
    private fun requestLocationUpdates(): Boolean {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LiveUploader.locationIntervalMs(this),
        )
            .setMinUpdateIntervalMillis(LiveUploader.locationMinIntervalMs(this))
            .setMinUpdateDistanceMeters(TrackingConfig.LOCATION_MIN_DISTANCE_M)
            .build()
        return try {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            TrackingState.set(true)
            true
        } catch (e: SecurityException) {
            stopSelf()
            false
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

        /**
         * Start from a foreground context (button tap in the app), or
         * from Lost Mode / boot (LostMode.turnOn, BootReceiver) under
         * the SYSTEM_ALERT_WINDOW / BOOT_COMPLETED background-start
         * exemptions. Background callers MUST wrap this in try/catch
         * (IllegalStateException + SecurityException): the start can
         * still be denied, e.g. when background location was revoked.
         */
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
