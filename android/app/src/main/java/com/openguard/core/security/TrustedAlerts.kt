package com.openguard.core.security

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.edit
import com.openguard.core.common.SecurityConfig

/**
 * Recovery alerts by SMS to the owner's trusted number: SIM change,
 * offline for over an hour, low battery. The owner picks which alert
 * types go to the contact in Settings (default: SIM change only).
 *
 * SMS COST RULE: the SMS is sent from this phone's own SIM, so the cost
 * falls on the app user via their own mobile plan. Nothing is charged
 * to the developer/operator, and there is no central SMS gateway.
 * Rate limit: at most one SMS per alert type per hour, so a flapping
 * condition can never run up a bill.
 *
 * The enable toggles live in the "openguard_security" prefs file and
 * are read here directly (same shared-file pattern as
 * LiveUploader.liveTrackingEnabled) because SecurityPrefs.kt is owned
 * by the coordinator: the accessor methods it adds MUST use exactly
 * these keys and defaults. The per-type last-sent timestamps are
 * internal bookkeeping in the same file and need no accessor.
 *
 * All sends are best-effort: a missing trusted number, a revoked
 * SEND_SMS permission, or no signal fails silently — the security
 * event is still recorded and uploaded by the caller.
 */
object TrustedAlerts {

    fun onSimChanged(context: Context, trusted: Boolean) {
        val note = if (trusted) {
            "The new SIM is one you marked as trusted."
        } else {
            "If this was not you, check the phone."
        }
        trySend(
            context, TYPE_SIM_CHANGE,
            "OpenGuard: the SIM card in this phone was changed. $note",
        )
    }

    fun onLowBattery(context: Context, level: Int) {
        trySend(
            context, TYPE_LOW_BATTERY,
            "OpenGuard: this phone's battery is low ($level%).",
        )
    }

    /**
     * Duress PIN entered: the owner may be coerced. Silent by design —
     * nothing shows on the phone (the entry screen already showed the
     * ordinary "Wrong PIN"). The alert defaults ON once a duress PIN
     * is set; the owner can turn it off in Settings.
     */
    fun onDuress(context: Context) {
        trySend(
            context, TYPE_DURESS,
            "OpenGuard: duress PIN entered on this phone — the owner may be in danger.",
        )
    }

    /** Duress SMS alert toggle (Settings): default ON. */
    fun isDuressAlertEnabled(context: Context): Boolean =
        alertEnabled(context, TYPE_DURESS)

    fun setDuressAlertEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED_PREFIX + TYPE_DURESS, enabled) }
    }

    /**
     * Called from the worker's sync-gap check when the phone comes back
     * online after a gap. Fires only for gaps over an hour, and only
     * once per gap: lastUploadAt is the upload the gap is measured
     * from, so an alert already sent after that upload means this gap
     * was already reported.
     */
    fun onOfflineGap(context: Context, lastUploadAt: Long, gapHours: Long) {
        if (gapHours < 1) return
        val prefs = prefs(context)
        if (prefs.getLong(KEY_LAST_OFFLINE_ALERT_AT, 0) > lastUploadAt) return
        if (!trySend(
                context, TYPE_OFFLINE,
                "OpenGuard: this phone was offline for about $gapHours hours " +
                    "and is back online.",
            )
        ) {
            return
        }
        prefs.edit { putLong(KEY_LAST_OFFLINE_ALERT_AT, System.currentTimeMillis()) }
    }

    /** False when the alert is disabled, unconfigured, or rate-limited. */
    private fun trySend(context: Context, type: String, text: String): Boolean {
        if (!alertEnabled(context, type)) return false
        val number = SecurityPrefs.trustedNumber(context)?.takeIf { it.isNotBlank() } ?: return false
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val lastSentKey = KEY_LAST_SENT_PREFIX + type
        if (now - prefs.getLong(lastSentKey, 0) < SecurityConfig.TRUSTED_ALERT_COOLDOWN_MS) {
            return false
        }
        try {
            smsManager(context).sendTextMessage(number, null, text, null, null)
        } catch (e: SecurityException) {
            return false
        } catch (e: IllegalArgumentException) {
            return false
        }
        prefs.edit { putLong(lastSentKey, now) }
        return true
    }

    private fun alertEnabled(context: Context, type: String): Boolean =
        prefs(context).getBoolean(
            KEY_ENABLED_PREFIX + type,
            type == TYPE_SIM_CHANGE || type == TYPE_DURESS,
        )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // Mirror of the SmsManager lookup in SmsCommands (same API-level split).
    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    private const val PREFS_FILE = "openguard_security"

    const val TYPE_SIM_CHANGE = "sim_change"
    const val TYPE_OFFLINE = "offline"
    const val TYPE_LOW_BATTERY = "low_battery"
    const val TYPE_DURESS = "duress"

    // Shared-file spec with SecurityPrefs (coordinator-owned): the
    // Settings toggles MUST read/write these exact keys. Defaults:
    // SIM change on, offline + low battery off, duress on.
    private const val KEY_ENABLED_PREFIX = "trusted_alert_"
    private const val KEY_LAST_SENT_PREFIX = "trusted_alert_last_sent_"
    private const val KEY_LAST_OFFLINE_ALERT_AT = "trusted_alert_last_offline_alert_at"
}
