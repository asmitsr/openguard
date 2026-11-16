package com.openguard.core.security

import com.google.gson.Gson
import com.openguard.core.database.AppDatabase
import com.openguard.core.database.SecurityEventEntity
import java.time.Instant

/**
 * Records security events into the Room outbox. UploadWorker uploads them
 * (and deletes them only after the server confirms).
 *
 * Event types (see the API contract): sim_changed, admin_disabled,
 * location_permission_revoked, location_disabled, low_battery, sync_gap,
 * sms_command, chaos_started.
 *
 * `confirmed` means the event is a verified fact (e.g. the permission
 * really was revoked). sim_changed is the exception: it is an indication,
 * never proof of theft, so it is always recorded unconfirmed.
 */
object SecurityEvents {
    private val gson = Gson()

    suspend fun record(
        db: AppDatabase,
        eventType: String,
        details: Map<String, Any?>? = null,
        confirmed: Boolean = false,
    ) {
        db.securityEventDao().insert(
            SecurityEventEntity(
                eventType = eventType,
                detailsJson = details?.let { gson.toJson(it) },
                confirmed = confirmed,
                createdAt = Instant.now().toString(),
            ),
        )
    }
}
