package com.openguard.core.location

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.openguard.OpenGuardApp
import com.openguard.core.database.LocationEntity
import com.openguard.core.security.SecurityEvents
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Low-battery last-location burst: when the battery is about to die
 * (level at or below [BURST_THRESHOLD]), send ONE last location before
 * the phone goes dark. One burst only per discharge cycle — never
 * continuous tracking, which would drain the last battery faster.
 *
 * The burst works even when tracking is off: the one-shot
 * getCurrentLocation call needs only the fine-location permission, not
 * the LocationService. Without the permission the burst still flushes
 * the outbox and sends a heartbeat (a recent queued fix may still go
 * out), but it cannot get a fresh fix. It never starts
 * LocationService and never switches to continuous live tracking.
 *
 * TRIGGER: while the feature is enabled, [BatteryBurstWorker] runs
 * every 15 minutes and checks the level — the 5 percent check in the
 * worker is the precise trigger. There is deliberately no
 * ACTION_BATTERY_LOW receiver: BATTERY_LOW and ACTION_POWER_CONNECTED
 * are NOT on the Android 8+ implicit-broadcast exemption list
 * (developer.android.com/guide/components/broadcast-exceptions), so a
 * manifest-registered receiver for them never fires on API 26+. A
 * dynamic receiver would only work while tracking runs, and the burst
 * must work with tracking off — so the always-on 15-minute watch is
 * the trigger. Its cost is one cheap battery read per run, and the
 * periodic upload worker already wakes the phone on the same cadence.
 *
 * BEST-EFFORT: WorkManager may delay the 15-minute watch checks while
 * the phone is idle (Doze), so a phone that dies suddenly may die
 * before the burst runs. The burst is silent — no notification, the
 * phone is about to die — and audit-logged via the low_battery
 * security event plus the heartbeat.
 *
 * State lives in its own SharedPreferences file so no other round's
 * prefs are touched. The owner opts in (default off, like the app's
 * other protections).
 */
object BatteryBurst {

    /** Burst when the level is at or below this percent. */
    const val BURST_THRESHOLD = 5

    /** Above this percent the discharge cycle is over (burst may fire again next cycle). */
    const val RESET_LEVEL = 10

    /** Unique name of the periodic watch worker. */
    const val WATCH_WORK_NAME = "openguard-battery-watch"

    /** How long to wait for a fresh fix before falling back to lastLocation. */
    const val FIX_TIMEOUT_MS = 30_000L

    private const val PREFS = "openguard_battery_burst"
    private const val KEY_ENABLED = "battery_burst_enabled"
    private const val KEY_SENT = "battery_burst_sent"
    private const val KEY_LAST_AT = "battery_burst_last_at"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * Enabling starts the always-on 15-minute watch (it runs while the
     * feature is enabled and stops only on disable — the sent flag, not
     * the schedule, gates the burst). Enabling with the battery already
     * at or below the threshold and no burst sent this cycle also
     * checks right away instead of waiting for the next watch run.
     * Disabling cancels the watch worker.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            BatteryBurstWorker.cancelWatch(context)
            return
        }
        BatteryBurstWorker.enqueueWatch(context)
        val level = LiveUploader.batteryInfo(context)?.first ?: return
        if (level <= BURST_THRESHOLD && !burstSent(context)) {
            BatteryBurstWorker.enqueueOneTime(context)
        }
    }

    /**
     * Re-schedules the watch when the feature is enabled. Idempotent
     * (KEEP): safe to call on every screen refresh, so a force-stop —
     * which cancels all WorkManager work until the app runs again —
     * does not silently leave the watch dead.
     */
    fun ensureScheduled(context: Context) {
        if (isEnabled(context)) BatteryBurstWorker.enqueueWatch(context)
    }

    /** True once the burst has fired in the current discharge cycle. */
    fun burstSent(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SENT, false)

    /** Epoch millis of the last burst, or 0 when it never fired. */
    fun lastBurstAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_AT, 0L)

    /**
     * Ends the discharge cycle: clears the sent flag so the burst may
     * fire again next cycle. The watch keeps running — only disabling
     * the feature cancels it. Called when the charger is connected
     * (seen as charging by the worker) and when the level rises above
     * [RESET_LEVEL].
     */
    fun reset(context: Context) {
        prefs(context).edit().putBoolean(KEY_SENT, false).apply()
    }

    /**
     * The burst, in order: one fresh fix into the outbox (when the
     * permission allows), flush the outbox, send a heartbeat, record
     * the low_battery event, prompt the upload worker, then mark the
     * burst sent. A failing fix attempt never skips the flush,
     * heartbeat or event. The watch keeps running after the burst —
     * the sent flag gates re-fire, and [reset] clears it when the
     * phone is charged again.
     */
    suspend fun performBurst(app: OpenGuardApp) {
        // No registered device: nothing to send to. Do NOT mark sent —
        // a later cycle may have a device.
        if (app.tokenStore.deviceId == null || app.tokenStore.deviceToken == null) return
        val level = LiveUploader.batteryInfo(app)?.first ?: -1
        var fix = "none"
        runCatching {
            if (TrackingPermissions.hasFineLocation(app)) {
                val fresh = freshFix(app)
                val location = fresh ?: lastFix(app)
                if (location != null) {
                    app.database.locationDao().insert(location.toEntity())
                    fix = if (fresh != null) "fresh" else "last"
                }
            }
        }
        // flushOutbox deletes rows only on server confirm (same
        // data-loss rule as everywhere else); both calls swallow their
        // own failures, so the event below always runs.
        LiveUploader.flushOutbox(app)
        LiveUploader.sendHeartbeat(app)
        SecurityEvents.record(
            app.database, "low_battery",
            details = mapOf("burst" to true, "level" to level, "fix" to fix),
            confirmed = true,
        )
        UploadWorker.enqueueOneTime(app)
        prefs(app).edit()
            .putBoolean(KEY_SENT, true)
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .apply()
    }

    /** One-shot high-accuracy fix, or null on timeout/failure. */
    private suspend fun freshFix(app: OpenGuardApp): Location? =
        withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val tokenSource = CancellationTokenSource()
                cont.invokeOnCancellation { tokenSource.cancel() }
                try {
                    LocationServices.getFusedLocationProviderClient(app)
                        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
                        .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    /** Last known fused location, or null (no fix yet, permission revoked). */
    private suspend fun lastFix(app: OpenGuardApp): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                LocationServices.getFusedLocationProviderClient(app).lastLocation
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun Location.toEntity(): LocationEntity = LocationEntity(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        recordedAt = Instant.ofEpochMilli(time).toString(),
        provider = provider,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
