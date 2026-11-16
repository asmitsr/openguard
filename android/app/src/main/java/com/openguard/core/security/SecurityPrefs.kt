package com.openguard.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plain (unencrypted) SharedPreferences for Phase 2 security state.
 * Nothing here is a secret: last-known permission/provider state,
 * timestamps, executed command ids, SIM ids, and Lost Mode state.
 * Tokens still live in TokenStore's encrypted prefs.
 */
private fun securityPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences("openguard_security", Context.MODE_PRIVATE)

object SecurityPrefs {

    fun lastLocationPermissionGranted(context: Context): Boolean? {
        val prefs = securityPrefs(context)
        if (!prefs.contains(KEY_LOCATION_PERMISSION_GRANTED)) return null
        return prefs.getBoolean(KEY_LOCATION_PERMISSION_GRANTED, false)
    }

    fun setLastLocationPermissionGranted(context: Context, granted: Boolean) {
        securityPrefs(context).edit { putBoolean(KEY_LOCATION_PERMISSION_GRANTED, granted) }
    }

    fun lastLocationEnabled(context: Context): Boolean? {
        val prefs = securityPrefs(context)
        if (!prefs.contains(KEY_LOCATION_ENABLED)) return null
        return prefs.getBoolean(KEY_LOCATION_ENABLED, false)
    }

    fun setLastLocationEnabled(context: Context, enabled: Boolean) {
        securityPrefs(context).edit { putBoolean(KEY_LOCATION_ENABLED, enabled) }
    }

    fun lastLowBatteryEventAt(context: Context): Long =
        securityPrefs(context).getLong(KEY_LAST_LOW_BATTERY_EVENT_AT, 0)

    fun setLastLowBatteryEventAt(context: Context, atMillis: Long) {
        securityPrefs(context).edit { putLong(KEY_LAST_LOW_BATTERY_EVENT_AT, atMillis) }
    }

    fun lastSuccessfulUploadAt(context: Context): Long =
        securityPrefs(context).getLong(KEY_LAST_SUCCESSFUL_UPLOAD_AT, 0)

    fun setLastSuccessfulUploadAt(context: Context, atMillis: Long) {
        securityPrefs(context).edit { putLong(KEY_LAST_SUCCESSFUL_UPLOAD_AT, atMillis) }
    }

    fun lastSyncGapEventAt(context: Context): Long =
        securityPrefs(context).getLong(KEY_LAST_SYNC_GAP_EVENT_AT, 0)

    fun setLastSyncGapEventAt(context: Context, atMillis: Long) {
        securityPrefs(context).edit { putLong(KEY_LAST_SYNC_GAP_EVENT_AT, atMillis) }
    }

    /** Last offline-transition event (LocationService.onLost), for the cooldown. */
    fun lastOfflineEventAt(context: Context): Long =
        securityPrefs(context).getLong(KEY_LAST_OFFLINE_EVENT_AT, 0)

    fun setLastOfflineEventAt(context: Context, atMillis: Long) {
        securityPrefs(context).edit { putLong(KEY_LAST_OFFLINE_EVENT_AT, atMillis) }
    }

    // Replay protection: executed commands are never executed twice.
    // Stored as "commandId:nonce" strings (nonce may be empty).
    fun hasExecutedCommand(context: Context, id: String, nonce: String): Boolean =
        securityPrefs(context).getStringSet(KEY_EXECUTED_COMMANDS, emptySet())
            ?.contains("$id:$nonce") == true

    fun addExecutedCommand(context: Context, id: String, nonce: String) {
        val prefs = securityPrefs(context)
        val updated = prefs.getStringSet(KEY_EXECUTED_COMMANDS, emptySet()).orEmpty().toMutableSet()
        updated.add("$id:$nonce")
        prefs.edit { putStringSet(KEY_EXECUTED_COMMANDS, updated) }
    }

    // SIM ids are stored as strings (SharedPreferences has no int sets).
    // Null = never observed yet (first run stores silently, no event).
    fun lastSeenSubscriptionIds(context: Context): Set<String>? {
        val prefs = securityPrefs(context)
        if (!prefs.contains(KEY_LAST_SEEN_SUBSCRIPTION_IDS)) return null
        return prefs.getStringSet(KEY_LAST_SEEN_SUBSCRIPTION_IDS, emptySet()).orEmpty()
    }

    fun setLastSeenSubscriptionIds(context: Context, ids: Collection<Int>) {
        securityPrefs(context).edit {
            putStringSet(KEY_LAST_SEEN_SUBSCRIPTION_IDS, ids.map { it.toString() }.toSet())
        }
    }

    fun trustedSubscriptionIds(context: Context): Set<String> =
        securityPrefs(context).getStringSet(KEY_TRUSTED_SUBSCRIPTION_IDS, emptySet()).orEmpty()

    fun setTrustedSubscriptionIds(context: Context, ids: Collection<Int>) {
        securityPrefs(context).edit {
            putStringSet(KEY_TRUSTED_SUBSCRIPTION_IDS, ids.map { it.toString() }.toSet())
        }
    }

    private const val KEY_LOCATION_PERMISSION_GRANTED = "last_location_permission_granted"
    private const val KEY_LOCATION_ENABLED = "last_location_enabled"
    private const val KEY_LAST_LOW_BATTERY_EVENT_AT = "last_low_battery_event_at"
    private const val KEY_LAST_SUCCESSFUL_UPLOAD_AT = "last_successful_upload_at"
    private const val KEY_LAST_SYNC_GAP_EVENT_AT = "last_sync_gap_event_at"
    private const val KEY_LAST_OFFLINE_EVENT_AT = "last_offline_event_at"
    private const val KEY_EXECUTED_COMMANDS = "executed_commands"
    private const val KEY_LAST_SEEN_SUBSCRIPTION_IDS = "last_seen_subscription_ids"
    private const val KEY_TRUSTED_SUBSCRIPTION_IDS = "trusted_subscription_ids"
}

/**
 * Lost Mode on-device state, backed by the same prefs file.
 * The StateFlow lets LostModeActivity finish itself the moment Lost Mode
 * is turned off (e.g. by a remote command); refresh() re-reads the
 * persisted value after a process restart.
 */
object LostModeStore {
    private const val KEY_LOST_MODE = "lost_mode"
    private const val KEY_LOST_MESSAGE = "lost_message"

    private val _isOn = MutableStateFlow(false)
    val isOnFlow: StateFlow<Boolean> = _isOn.asStateFlow()

    fun isOn(context: Context): Boolean =
        securityPrefs(context).getBoolean(KEY_LOST_MODE, false)

    fun message(context: Context): String? =
        securityPrefs(context).getString(KEY_LOST_MESSAGE, null)

    fun setOn(context: Context, message: String?) {
        securityPrefs(context).edit {
            putBoolean(KEY_LOST_MODE, true)
            if (message.isNullOrBlank()) remove(KEY_LOST_MESSAGE) else putString(KEY_LOST_MESSAGE, message)
        }
        _isOn.value = true
    }

    fun clear(context: Context) {
        securityPrefs(context).edit {
            putBoolean(KEY_LOST_MODE, false)
            remove(KEY_LOST_MESSAGE)
        }
        _isOn.value = false
    }

    fun refresh(context: Context) {
        _isOn.value = isOn(context)
    }
}

/**
 * Pending remote-erase state, backed by the same prefs file. The deadline
 * is an absolute timestamp, so a rotation or process death can never
 * stretch the grace period. The StateFlow lets EraseCountdownActivity
 * finish itself the moment the erase is aborted; refresh() re-reads the
 * persisted value after a process restart.
 */
object EraseStore {
    private const val KEY_ERASE_COMMAND_ID = "erase_command_id"
    private const val KEY_ERASE_DEADLINE_MS = "erase_deadline_ms"

    data class PendingErase(val commandId: String, val deadlineMs: Long)

    private val _pending = MutableStateFlow<PendingErase?>(null)
    val pendingFlow: StateFlow<PendingErase?> = _pending.asStateFlow()

    fun pending(context: Context): PendingErase? {
        val prefs = securityPrefs(context)
        val id = prefs.getString(KEY_ERASE_COMMAND_ID, null) ?: return null
        val deadline = prefs.getLong(KEY_ERASE_DEADLINE_MS, 0)
        if (deadline <= 0) return null
        return PendingErase(id, deadline)
    }

    fun setPending(context: Context, commandId: String, deadlineMs: Long) {
        securityPrefs(context).edit {
            putString(KEY_ERASE_COMMAND_ID, commandId)
            putLong(KEY_ERASE_DEADLINE_MS, deadlineMs)
        }
        _pending.value = PendingErase(commandId, deadlineMs)
    }

    fun clear(context: Context) {
        securityPrefs(context).edit {
            remove(KEY_ERASE_COMMAND_ID)
            remove(KEY_ERASE_DEADLINE_MS)
        }
        _pending.value = null
    }

    fun refresh(context: Context) {
        _pending.value = pending(context)
    }
}
