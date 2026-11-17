package com.openguard.core.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openguard.MainActivity
import com.openguard.OpenGuardApp
import com.openguard.core.network.GeofenceDto
import com.openguard.core.network.ReportGeofenceEventRequest
import com.openguard.core.security.DeviceAdminManager
import com.openguard.core.security.LostMode
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geofence engine: hand-written haversine zone math over the cached
 * geofence list, with no Play Services geofencing API. State lives in
 * plain SharedPreferences (not Room) so it survives process death.
 *
 * A transition fires only after 2 consecutive fixes on the new side
 * (hysteresis), with a 5-minute cooldown per geofence + transition.
 * Fixes with accuracy worse than 200 m are skipped entirely. After a
 * reboot the first fix only initialises state and fires no event.
 *
 * onFix never throws into its caller: a geofence failure must never
 * break location tracking.
 */
object GeofenceManager {

    private const val PREFS_NAME = "openguard_geofences"
    private const val KEY_CACHE = "cache"
    private const val KEY_PENDING_EVENTS = "pending_events"
    private const val KEY_BOOT_MARKER = "boot_marker"

    // Notification ids are per-app, not per-channel: 8 is evidence
    // (EvidenceConfig), so geofence alerts use 9.
    private const val GEOFENCE_CHANNEL_ID = "openguard-geofence"
    private const val GEOFENCE_NOTIFICATION_ID = 9

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val HYSTERESIS_FIXES = 2
    private const val COOLDOWN_MS = 5 * 60 * 1000L
    private const val MAX_ACCURACY_M = 200f
    // The boot marker is only stable to a few ms (the two clocks are
    // read sequentially), so it is compared with tolerance: a real
    // reboot shifts it by the whole off-time (tens of seconds at least).
    // An exact == comparison would report a reboot on nearly every fix
    // and hysteresis would never accumulate.
    private const val BOOT_MARKER_TOLERANCE_MS = 10_000L

    private val gson = Gson()

    /** One queued event, waiting for an upload the server confirms. */
    private data class PendingEvent(
        val geofenceId: String,
        val deviceId: String,
        val transition: String,
        val recordedAt: String,
    )

    /** Haversine distance in metres between two lat/lng pairs. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Evaluates all cached geofences against one fix and fires any
     * confirmed transitions. Never throws.
     */
    suspend fun onFix(
        context: Context,
        app: OpenGuardApp,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
    ) {
        // Unreliable for zone math; do not count toward hysteresis.
        if (accuracy > MAX_ACCURACY_M) return
        runCatching {
            val prefs = prefs(context)
            val rebooted = checkBoot(prefs)
            for (geofence in cachedGeofences(context)) {
                runCatching {
                    evaluateFix(context, app, prefs, geofence, latitude, longitude, rebooted)
                }
            }
        }
    }

    /** Refreshes the cached geofence list from the server (user token). */
    suspend fun refreshCache(context: Context, app: OpenGuardApp) {
        val deviceId = app.tokenStore.deviceId ?: return
        runCatching {
            val response = app.api.listGeofences(deviceId)
            if (response.isSuccessful) {
                response.body()?.geofences?.let { saveCache(context, it) }
            }
        }
    }

    /** Overwrites the cache; used by the geofence screen after a server fetch. */
    fun saveCache(context: Context, geofences: List<GeofenceDto>) {
        prefs(context).edit { putString(KEY_CACHE, gson.toJson(geofences)) }
    }

    fun cachedGeofences(context: Context): List<GeofenceDto> {
        val json = prefs(context).getString(KEY_CACHE, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<GeofenceDto>>(json, object : TypeToken<List<GeofenceDto>>() {}.type)
        }.getOrNull().orEmpty()
    }

    /** True = inside, false = outside, null = unknown (no fix yet). */
    fun currentState(context: Context, geofenceId: String): Boolean? {
        val prefs = prefs(context)
        val key = "inside_$geofenceId"
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    /**
     * Uploads queued geofence events (device token). Each event is
     * deleted from the queue only after the server confirms it.
     * Returns true when anything was uploaded.
     */
    suspend fun flushPendingEvents(context: Context, app: OpenGuardApp): Boolean {
        val deviceToken = app.tokenStore.deviceToken ?: return false
        val queued = pendingEvents(context)
        if (queued.isEmpty()) return false
        var uploaded = false
        val remaining = queued.toMutableList()
        for (event in queued) {
            val ok = runCatching {
                app.api.reportGeofenceEvent(
                    "Bearer $deviceToken",
                    event.deviceId,
                    event.geofenceId,
                    ReportGeofenceEventRequest(event.transition, event.recordedAt),
                ).isSuccessful
            }.getOrDefault(false)
            if (ok) {
                remaining.remove(event)
                uploaded = true
            }
        }
        if (uploaded) savePendingEvents(context, remaining)
        return uploaded
    }

    private suspend fun evaluateFix(
        context: Context,
        app: OpenGuardApp,
        prefs: SharedPreferences,
        geofence: GeofenceDto,
        latitude: Double,
        longitude: Double,
        rebooted: Boolean,
    ) {
        val insideNow = distanceMeters(
            latitude, longitude, geofence.latitude, geofence.longitude,
        ) <= geofence.radiusM
        val insideKey = "inside_${geofence.id}"
        val pendingKey = "pending_${geofence.id}"
        val knownInside = if (prefs.contains(insideKey)) prefs.getBoolean(insideKey, false) else null
        if (knownInside == null || rebooted) {
            // First fix (or first fix after a reboot): initialise only.
            prefs.edit { putBoolean(insideKey, insideNow); putInt(pendingKey, 0) }
            return
        }
        if (insideNow == knownInside) {
            // Back on the old side: reset the hysteresis count.
            prefs.edit { putInt(pendingKey, 0) }
            return
        }
        val pending = prefs.getInt(pendingKey, 0) + 1
        if (pending < HYSTERESIS_FIXES) {
            prefs.edit { putInt(pendingKey, pending) }
            return
        }
        // Confirmed transition. State updates even inside the cooldown
        // window, so it always reflects the latest fix.
        val transition = if (insideNow) "enter" else "exit"
        val lastKey = "last_${geofence.id}_$transition"
        val now = System.currentTimeMillis()
        prefs.edit {
            putBoolean(insideKey, insideNow)
            putInt(pendingKey, 0)
        }
        if (now - prefs.getLong(lastKey, 0L) < COOLDOWN_MS) return
        prefs.edit { putLong(lastKey, now) }
        onTransition(context, app, geofence, transition, now)
    }

    private suspend fun onTransition(
        context: Context,
        app: OpenGuardApp,
        geofence: GeofenceDto,
        transition: String,
        now: Long,
    ) {
        app.tokenStore.deviceId?.let { deviceId ->
            queueEvent(
                context,
                PendingEvent(geofence.id, deviceId, transition, Instant.ofEpochMilli(now).toString()),
            )
        }
        // Best-effort immediate upload; offline just leaves it queued.
        runCatching { flushPendingEvents(context, app) }
        fireAction(context, geofence, transition)
    }

    private fun fireAction(context: Context, geofence: GeofenceDto, transition: String) {
        val verb = if (transition == "enter") "entered" else "left"
        // Enforcement actions fire on EXIT only: entering a safe zone
        // must never lock the phone or turn on Lost Mode. 'notify'
        // alerts on both transitions. The notification posts either way.
        val note = when (geofence.action) {
            "lock" -> {
                if (transition != "exit") null
                else if (DeviceAdminManager.isAdminActive(context)) {
                    DeviceAdminManager.lockNow(context)
                    "Phone locked."
                } else {
                    "Lock skipped — device admin is not enabled."
                }
            }
            "lost_mode" -> {
                if (transition != "exit") null
                else {
                    LostMode.turnOn(context, "Geofence '${geofence.name}': device $verb the zone")
                    "Lost Mode turned on."
                }
            }
            // "notify" and anything unknown: notification only.
            else -> null
        }
        postNotification(context, geofence, transition, note)
    }

    private fun postNotification(
        context: Context,
        geofence: GeofenceDto,
        transition: String,
        note: String?,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(GEOFENCE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    GEOFENCE_CHANNEL_ID,
                    "Geofence alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val verb = if (transition == "enter") "entered" else "left"
        val text = "Device $verb '${geofence.name}'." + if (note != null) " $note" else ""
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, GEOFENCE_CHANNEL_ID)
            .setContentTitle("Geofence alert")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            manager.notify(GEOFENCE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications revoked: the event is still queued for upload.
        }
    }

    /**
     * Detects a reboot by comparing the boot marker (wall clock minus
     * uptime) with the stored one. On reboot, clears inside/pending
     * state but keeps the cached geofences and pending events.
     * Returns true when the phone rebooted (or on first run).
     */
    private fun checkBoot(prefs: SharedPreferences): Boolean {
        val marker = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val stored = prefs.getLong(KEY_BOOT_MARKER, Long.MIN_VALUE)
        if (stored != Long.MIN_VALUE && abs(stored - marker) <= BOOT_MARKER_TOLERANCE_MS) return false
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith("inside_") || key.startsWith("pending_") || key.startsWith("last_")) {
                editor.remove(key)
            }
        }
        editor.putLong(KEY_BOOT_MARKER, marker)
        editor.apply()
        return true
    }

    private fun queueEvent(context: Context, event: PendingEvent) {
        val events = pendingEvents(context).toMutableList()
        events += event
        savePendingEvents(context, events)
    }

    private fun pendingEvents(context: Context): List<PendingEvent> {
        val json = prefs(context).getString(KEY_PENDING_EVENTS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<PendingEvent>>(json, object : TypeToken<List<PendingEvent>>() {}.type)
        }.getOrNull().orEmpty()
    }

    private fun savePendingEvents(context: Context, events: List<PendingEvent>) {
        prefs(context).edit { putString(KEY_PENDING_EVENTS, gson.toJson(events)) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
