package com.openguard.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plain (unencrypted) SharedPreferences for Phase 2 security state.
 * Nothing here is a secret: last-known permission/provider state,
 * timestamps, executed command ids, SIM ids, and Lost Mode state.
 * Tokens still live in TokenStore's encrypted prefs, and the trusted
 * number (a user secret) lives in the encrypted prefs below.
 */
private fun securityPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences("openguard_security", Context.MODE_PRIVATE)

/** Encrypted prefs for the trusted number only (same pattern as TokenStore). */
private fun secretPrefs(context: Context): SharedPreferences =
    EncryptedSharedPreferences.create(
        "openguard_secrets",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

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

    // Power-menu guard (accessibility service): in-app toggle, default
    // on. The service itself must also be enabled in system Settings.
    fun isPowerMenuGuardEnabled(context: Context): Boolean =
        securityPrefs(context).getBoolean(KEY_POWER_MENU_GUARD_ENABLED, true)

    fun setPowerMenuGuardEnabled(context: Context, enabled: Boolean) {
        securityPrefs(context).edit { putBoolean(KEY_POWER_MENU_GUARD_ENABLED, enabled) }
    }

    // Live tracking (fast updates): owner-enabled; Lost Mode also turns
    // live mode on automatically (see LiveUploader.isLiveMode).
    fun isLiveTrackingEnabled(context: Context): Boolean =
        securityPrefs(context).getBoolean(KEY_LIVE_TRACKING_ENABLED, false)

    fun setLiveTrackingEnabled(context: Context, enabled: Boolean) {
        securityPrefs(context).edit { putBoolean(KEY_LIVE_TRACKING_ENABLED, enabled) }
    }

    // Trusted number for theft response (auto-call + the SMS location
    // replies). A user secret: encrypted prefs, device-only, never
    // uploaded, never logged. Null = not set. The SMS handler reads it
    // by this exact accessor. The read is defensive: before the first
    // unlock after a reboot the Keystore is unavailable, so a failed
    // read returns null (theft response simply waits) instead of
    // crashing; the write runs from Settings on an unlocked phone.
    fun trustedNumber(context: Context): String? = try {
        secretPrefs(context).getString(KEY_TRUSTED_NUMBER, null)
    } catch (e: Exception) {
        null
    }

    fun setTrustedNumber(context: Context, number: String?) {
        secretPrefs(context).edit {
            if (number.isNullOrBlank()) remove(KEY_TRUSTED_NUMBER)
            else putString(KEY_TRUSTED_NUMBER, number.trim())
        }
    }

    // Auto-call during Lost Mode: off by default, the owner opts in.
    fun isAutoCallEnabled(context: Context): Boolean =
        securityPrefs(context).getBoolean(KEY_AUTO_CALL_ENABLED, false)

    fun setAutoCallEnabled(context: Context, enabled: Boolean) {
        securityPrefs(context).edit { putBoolean(KEY_AUTO_CALL_ENABLED, enabled) }
    }

    // Chaos mode (SIM removed during Lost Mode): set by SimMonitor, read
    // by the tracking service's theft-response supervisor, cleared when
    // Lost Mode turns off. Persisted so it survives a process death.
    fun isChaosModeActive(context: Context): Boolean =
        securityPrefs(context).getBoolean(KEY_CHAOS_MODE_ACTIVE, false)

    fun setChaosModeActive(context: Context, active: Boolean) {
        securityPrefs(context).edit { putBoolean(KEY_CHAOS_MODE_ACTIVE, active) }
    }

    // Trusted contact alerts (Workstream C): which recovery alerts go by
    // SMS to the trusted number. Keys MUST match TrustedAlerts, which
    // reads the same keys directly (trusted_alert_<type>). Defaults:
    // SIM change on, offline + low battery off.
    fun isTrustedAlertSimChangeEnabled(context: Context): Boolean =
        securityPrefs(context).getBoolean(KEY_TRUSTED_ALERT_SIM_CHANGE, true)

    fun setTrustedAlertSimChangeEnabled(context: Context, enabled: Boolean) {
        securityPrefs(context).edit { putBoolean(KEY_TRUSTED_ALERT_SIM_CHANGE, enabled) }
    }

    fun isTrustedAlertOfflineEnabled(context: Context): Boolean =
        securityPrefs(context).getBoolean(KEY_TRUSTED_ALERT_OFFLINE, false)

    fun setTrustedAlertOfflineEnabled(context: Context, enabled: Boolean) {
        securityPrefs(context).edit { putBoolean(KEY_TRUSTED_ALERT_OFFLINE, enabled) }
    }

    fun isTrustedAlertLowBatteryEnabled(context: Context): Boolean =
        securityPrefs(context).getBoolean(KEY_TRUSTED_ALERT_LOW_BATTERY, false)

    fun setTrustedAlertLowBatteryEnabled(context: Context, enabled: Boolean) {
        securityPrefs(context).edit { putBoolean(KEY_TRUSTED_ALERT_LOW_BATTERY, enabled) }
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
    private const val KEY_POWER_MENU_GUARD_ENABLED = "power_menu_guard_enabled"
    private const val KEY_LIVE_TRACKING_ENABLED = "live_tracking_enabled"
    private const val KEY_TRUSTED_NUMBER = "trusted_number"
    private const val KEY_AUTO_CALL_ENABLED = "auto_call_enabled"
    private const val KEY_CHAOS_MODE_ACTIVE = "chaos_mode_active"
    private const val KEY_TRUSTED_ALERT_SIM_CHANGE = "trusted_alert_sim_change"
    private const val KEY_TRUSTED_ALERT_OFFLINE = "trusted_alert_offline"
    private const val KEY_TRUSTED_ALERT_LOW_BATTERY = "trusted_alert_low_battery"
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
