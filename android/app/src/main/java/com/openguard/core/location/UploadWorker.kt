package com.openguard.core.location

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.JsonParser
import com.openguard.OpenGuardApp
import com.openguard.core.common.SecurityConfig
import com.openguard.core.common.TrackingConfig
import com.openguard.core.database.SecurityEventEntity
import com.openguard.core.evidence.EvidenceService
import com.openguard.core.network.AckCommandRequest
import com.openguard.core.network.SecurityEventRequest
import com.openguard.core.network.SecurityEventsUploadRequest
import com.openguard.core.security.CommandExecutor
import com.openguard.core.security.DecoyLock
import com.openguard.core.security.LostModeStore
import com.openguard.core.security.SecurityEvents
import com.openguard.core.security.SecurityPrefs
import com.openguard.core.security.SimMonitor
import com.openguard.core.security.TrustedAlerts
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads queued locations + security events, sends a heartbeat, polls
 * pending remote commands, and runs the security detection checks.
 *
 * DATA-LOSS RULE: queued rows are deleted ONLY after the server returns
 * success for that batch. Network failures retry with exponential backoff;
 * 4xx failures (bad token, bad data) fail without retry so a poisoned
 * batch cannot spin forever.
 *
 * Detection checks run first, so events recorded in this run ride along
 * in the same run's upload. Each check records only on STATE CHANGE (or,
 * for low battery / sync gap, at most once per gap/period).
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
        var contactedServer = false
        return try {
            // Best-effort: (re-)register the push topic so commands can
            // wake this phone in seconds. Never fails the worker.
            runCatching { NtfyClient.ensureRegistered(app) }
            runDetectionChecks(app)
            // Locations upload through the shared direct path (same
            // data-loss rule: rows are deleted only on server confirm).
            // The foreground service usually flushes them within seconds;
            // this run is the offline-retry/backfill for whatever is left.
            when (LiveUploader.flushOutbox(app)) {
                LiveUploader.FlushOutcome.RETRYABLE -> return Result.retry()
                LiveUploader.FlushOutcome.FAILED -> return Result.failure()
                LiveUploader.FlushOutcome.UPLOADED -> contactedServer = true
                LiveUploader.FlushOutcome.EMPTY -> { /* nothing queued */ }
            }
            // Security events, same data-loss rule as locations.
            val eventDao = app.database.securityEventDao()
            val events = eventDao.oldest(SecurityConfig.SECURITY_EVENTS_BATCH_SIZE)
            if (events.isNotEmpty()) {
                val response = app.api.uploadSecurityEvents(
                    auth, deviceId, SecurityEventsUploadRequest(events.map { it.toUploadItem() }),
                )
                if (!response.isSuccessful) {
                    return if (response.code() == 429 || response.code() >= 500) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
                contactedServer = true
                eventDao.deleteByIds(events.map { it.id })
            }
            // Heartbeat after every pass, even with an empty queue, so
            // last-seen / battery stay fresh on the dashboard.
            if (LiveUploader.sendHeartbeat(app)) contactedServer = true
            // Pending remote commands (oldest first): execute, then ack.
            // A failed ack is not fatal — replay protection in
            // CommandExecutor keeps a redelivery from re-running.
            val pending = app.api.pendingCommands(auth, deviceId)
            if (pending.isSuccessful) {
                contactedServer = true
                for (command in pending.body()?.commands.orEmpty()) {
                    val outcome = CommandExecutor.execute(applicationContext, command)
                    app.api.ackCommand(
                        auth, command.id,
                        AckCommandRequest(deviceId, outcome.status, outcome.result),
                    )
                }
            }
            // Decoy backstop: if Lost Mode is on but the overlay is gone
            // (process died, tracking off), bring it back. No-op when it
            // is already showing or the overlay permission was never granted.
            if (LostModeStore.isOn(applicationContext)) {
                DecoyLock.ensureShown(applicationContext)
            }
            // Evidence backstop: if Lost Mode is on but the evidence
            // service died (process killed, STICKY restart pending),
            // bring it back. No-op when it is already running. A denied
            // background start just waits for the next run.
            if (LostModeStore.isOn(applicationContext)) {
                runCatching { EvidenceService.start(applicationContext) }
            }
            if (contactedServer) {
                SecurityPrefs.setLastSuccessfulUploadAt(
                    applicationContext, System.currentTimeMillis(),
                )
            }
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private suspend fun runDetectionChecks(app: OpenGuardApp) {
        val context = applicationContext
        val db = app.database
        // Location permission granted -> revoked.
        val permissionNow = TrackingPermissions.hasFineLocation(context)
        val permissionLast = SecurityPrefs.lastLocationPermissionGranted(context)
        if (permissionLast == true && !permissionNow) {
            SecurityEvents.record(db, "location_permission_revoked", confirmed = true)
        }
        SecurityPrefs.setLastLocationPermissionGranted(context, permissionNow)
        // Location providers enabled -> disabled.
        val enabledNow = LiveUploader.locationProvidersEnabled(context)
        val enabledLast = SecurityPrefs.lastLocationEnabled(context)
        if (enabledLast == true && !enabledNow) {
            SecurityEvents.record(db, "location_disabled", confirmed = true)
        }
        SecurityPrefs.setLastLocationEnabled(context, enabledNow)
        // Low battery (<=15%, not charging), at most once per 24h.
        LiveUploader.batteryInfo(context)?.let { (level, charging) ->
            if (level <= SecurityConfig.LOW_BATTERY_THRESHOLD && !charging) {
                val now = System.currentTimeMillis()
                if (now - SecurityPrefs.lastLowBatteryEventAt(context) >
                    SecurityConfig.LOW_BATTERY_COOLDOWN_MS
                ) {
                    SecurityEvents.record(db, "low_battery", confirmed = true)
                    SecurityPrefs.setLastLowBatteryEventAt(context, now)
                    // Trusted-contact alert (owner toggle, default off).
                    TrustedAlerts.onLowBattery(context, level)
                }
            }
        }
        // Sync gap: last successful upload > 6h ago. One event per gap:
        // only record when no gap event was recorded since that upload.
        val lastUpload = SecurityPrefs.lastSuccessfulUploadAt(context)
        if (lastUpload > 0) {
            val gapMs = System.currentTimeMillis() - lastUpload
            if (gapMs > SecurityConfig.SYNC_GAP_MS &&
                SecurityPrefs.lastSyncGapEventAt(context) <= lastUpload
            ) {
                SecurityEvents.record(
                    db, "sync_gap",
                    details = mapOf("gap_hours" to gapMs / 3_600_000),
                    confirmed = true,
                )
                SecurityPrefs.setLastSyncGapEventAt(context, System.currentTimeMillis())
            }
            // Trusted-contact alert (owner toggle, default off): fires on
            // reconnect after a gap over an hour, once per gap. The
            // worker only runs with network, so the alert necessarily
            // arrives when the phone is back online, not while offline.
            if (gapMs > SecurityConfig.TRUSTED_ALERT_OFFLINE_GAP_MS) {
                TrustedAlerts.onOfflineGap(context, lastUpload, gapMs / 3_600_000)
            }
        }
        // SIM change (no-op without the phone-state permission).
        SimMonitor.checkAndRecord(context, db)
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
    }
}

private fun SecurityEventEntity.toUploadItem(): SecurityEventRequest = SecurityEventRequest(
    eventType = eventType,
    details = detailsJson?.let {
        runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull()
    },
    confirmed = confirmed,
    createdAt = createdAt,
)
