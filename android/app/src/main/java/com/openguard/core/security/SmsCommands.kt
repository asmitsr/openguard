package com.openguard.core.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.android.gms.location.LocationServices
import com.openguard.OpenGuardApp
import com.openguard.core.location.UploadWorker
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SMS commands: control this phone by text message when it has NO
 * internet. FMD-style concept, own implementation — no FMD code is used
 * or copied (FMD is GPL-3.0; this repo is Apache-2.0).
 *
 * Format: the first word is the owner's secret code, the second word is
 * the command (case-insensitive). Anything else in the message is ignored.
 *   <secret> RING    — ring the phone (same RingService as the remote command)
 *   <secret> LOCATE  — reply with the last known location + Maps link
 *   <secret> LOCK    — lock the phone (needs device admin)
 *   <secret> WIPE    — start the 60-second abortable erase countdown (needs device admin)
 *
 * Auth is the secret code only, compared in constant time. A text that
 * fails the secret is silently ignored: no reply, no log, no event.
 * Accepted commands are recorded as "sms_command" security events
 * (action only — never the secret, never the sender).
 *
 * Replies go ONLY to the owner's trusted number, never to the sender:
 * the sender might not be the owner. Without a trusted number the
 * commands still run, but nothing is sent back. SMS cost falls on the
 * app user via their own SIM.
 *
 * Sideload-only: RECEIVE_SMS and SEND_SMS are restricted permissions
 * the Play Store forbids for most apps. OpenGuard is distributed as an
 * APK, not via Play, so this is fine — but a Play release would need
 * this feature removed or re-approved.
 *
 * Honest note: the message stays visible in the messaging app — nothing
 * is hidden or deleted. This is the owner's own phone, controlled by
 * the owner's own secret, not surveillance.
 */
object SmsSecretStore {

    // EncryptedSharedPreferences, same pattern as TokenStore. The secret
    // is a user secret: device-only, never logged, never in the repo.
    // (DeviceKeys is an unimplemented stub, so it is not used here.)

    fun secret(context: Context): String? =
        prefs(context).getString(KEY_SECRET, null)?.takeIf { it.isNotBlank() }

    fun hasSecret(context: Context): Boolean = secret(context) != null

    /** Null or blank clears the secret (SMS commands stop working). */
    fun setSecret(context: Context, secret: String?) {
        prefs(context).edit {
            if (secret.isNullOrBlank()) remove(KEY_SECRET) else putString(KEY_SECRET, secret.trim())
        }
    }

    internal fun lastReplyAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_REPLY_AT, 0)

    internal fun setLastReplyAt(context: Context, atMillis: Long) {
        prefs(context).edit { putLong(KEY_LAST_REPLY_AT, atMillis) }
    }

    private fun prefs(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            FILE_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private const val FILE_NAME = "openguard_sms"
    private const val KEY_SECRET = "sms_secret"
    private const val KEY_LAST_REPLY_AT = "last_sms_reply_at"
}

/**
 * Reads the owner's trusted number (replies go there). Delegates to
 * SecurityPrefs, which stores it in encrypted prefs — the single
 * source of truth shared with the theft-response auto-call.
 */
object SmsTrustedNumber {
    fun get(context: Context): String? =
        SecurityPrefs.trustedNumber(context)?.takeIf { it.isNotBlank() }
}

object SmsCommandHandler {

    private val COMMANDS = setOf("ring", "locate", "lock", "wipe")

    /** At most one SMS reply per minute: a flood of texts must not run up a bill. */
    private const val REPLY_COOLDOWN_MS = 60_000L

    suspend fun handle(context: Context, body: String?) {
        val secret = SmsSecretStore.secret(context) ?: return
        val parts = body?.trim()?.split(Regex("\\s+")).orEmpty()
        if (parts.size < 2) return
        // Constant-time compare: the secret cannot be guessed byte by
        // byte from timing. The secret is never logged.
        if (!MessageDigest.isEqual(parts[0].toByteArray(), secret.toByteArray())) return
        val action = parts[1].lowercase()
        if (action !in COMMANDS) return
        val app = context.applicationContext as OpenGuardApp
        SecurityEvents.record(
            app.database, "sms_command",
            details = mapOf("action" to action), confirmed = true,
        )
        UploadWorker.enqueueOneTime(context)
        when (action) {
            "ring" -> {
                val ok = RingService.start(context)
                reply(
                    context,
                    if (ok) {
                        "OpenGuard: ringing."
                    } else {
                        "OpenGuard: could not start ringing — open the app or turn tracking on first."
                    },
                )
            }
            "locate" -> replyLocate(context)
            "lock" -> {
                val ok = DeviceAdminManager.lockNow(context)
                reply(
                    context,
                    if (ok) {
                        "OpenGuard: phone locked."
                    } else {
                        "OpenGuard: lock failed — device admin is not enabled."
                    },
                )
            }
            "wipe" -> {
                if (!DeviceAdminManager.isAdminActive(context)) {
                    reply(context, "OpenGuard: erase failed — device admin is not enabled.")
                    return
                }
                // Same 60-second abortable countdown as the remote erase
                // command — a wipe by SMS never wipes at once.
                EraseCountdown.start(context, "sms-wipe-" + System.currentTimeMillis())
                reply(context, "OpenGuard: erase countdown started — 60 seconds to abort on the phone.")
            }
        }
    }

    private suspend fun replyLocate(context: Context) {
        val location = withTimeoutOrNull(8_000) { lastKnownLocation(context) }
        if (location == null) {
            reply(context, "OpenGuard: no recent location available.")
            return
        }
        reply(
            context,
            "OpenGuard: last location ${location.latitude}, ${location.longitude} " +
                "(±${location.accuracy.toInt()}m). " +
                "https://maps.google.com/?q=${location.latitude},${location.longitude}",
        )
    }

    /** Last known fused location, or null (no fix yet, permission revoked, timeout). */
    private suspend fun lastKnownLocation(context: Context): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                LocationServices.getFusedLocationProviderClient(context).lastLocation
                    .addOnSuccessListener { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            }
        }

    /** Best-effort reply to the trusted number only, rate-limited to one per minute. */
    private fun reply(context: Context, text: String) {
        val number = SmsTrustedNumber.get(context) ?: return
        val now = System.currentTimeMillis()
        if (now - SmsSecretStore.lastReplyAt(context) < REPLY_COOLDOWN_MS) return
        SmsSecretStore.setLastReplyAt(context, now)
        try {
            smsManager(context).sendTextMessage(number, null, text, null, null)
        } catch (e: SecurityException) {
            // SEND_SMS revoked: the command already ran; the reply is best-effort.
        } catch (e: IllegalArgumentException) {
            // Malformed trusted number: nothing to send to.
        }
    }

    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
}

/**
 * Manifest-registered receiver for incoming SMS. Uses
 * SMS_RECEIVED_ACTION (not SMS_DELIVER — OpenGuard is not the default
 * SMS app). Multi-part messages are joined before parsing.
 */
class SmsCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmsCommandHandler.handle(context, body)
            } finally {
                pending.finish()
            }
        }
    }
}
