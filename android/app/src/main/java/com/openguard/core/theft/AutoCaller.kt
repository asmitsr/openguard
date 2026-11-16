package com.openguard.core.theft

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.openguard.core.location.TrackingPermissions
import com.openguard.core.security.LostModeStore
import com.openguard.core.security.SecurityPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wake counter + auto-call. While Lost Mode is on, every screen wake
 * is counted; at 3 wakes a call burst starts against the owner's
 * trusted number (Settings, stored device-only in SecurityPrefs, never
 * sent to the server).
 *
 * EXACT LOOP CAPS (the reason this cannot dial forever):
 * - WAKES_PER_BURST = 3 screen wakes start one burst.
 * - MAX_ATTEMPTS_PER_BURST = 5 dials per burst, first dial at once,
 *   then one redial every REDIAL_DELAY_MS (60 seconds).
 * - After a burst ends, the wake counter resets to 0: the next burst
 *   needs 3 fresh screen wakes. No wakes, no more dials.
 * - A dial is skipped (not counted) while a call is already active,
 *   so an answered call is never interrupted; the loop waits another
 *   60 seconds and checks again.
 * - Every dial re-checks Lost Mode: turning Lost Mode off ends the
 *   burst at the next step at the latest, and reset() (called by the
 *   TheftResponse supervisor) cancels the redial job outright.
 *
 * HONEST LIMITS: the call screen shows and the thief CAN end a call
 * (for example from the notification shade, which the decoy overlay
 * does not cover) — the defense is the redial loop, not an un-endable
 * call. The call needs a SIM with credit and network; if the SIM is
 * removed, auto-call cannot work and chaos mode takes over instead.
 * The trusted number is never logged.
 */
object AutoCaller {

    /** Screen wakes that start one call burst. */
    private const val WAKES_PER_BURST = 3

    /** Dials per burst, then the burst ends until 3 fresh wakes. */
    private const val MAX_ATTEMPTS_PER_BURST = 5

    /** Gap between redials inside a burst. */
    private const val REDIAL_DELAY_MS = 60_000L

    private var scope: CoroutineScope? = null
    // Written from the receiver (main thread) and the redial job
    // (service scope): volatile so both threads see each other's writes.
    @Volatile
    private var wakeCount = 0
    @Volatile
    private var attemptsMade = 0
    @Volatile
    private var burstActive = false
    private var redialJob: Job? = null

    /** Called by TheftResponse.start with the service scope. */
    fun attach(serviceScope: CoroutineScope) {
        scope = serviceScope
    }

    /** Called by TheftResponse.stop: cancels any redial, resets state. */
    fun detach() {
        reset()
        scope = null
    }

    /** Cancels any redial job and resets the counter and burst state. */
    fun reset() {
        redialJob?.cancel()
        redialJob = null
        wakeCount = 0
        attemptsMade = 0
        burstActive = false
    }

    /** True when the CALL_PHONE runtime permission is granted. */
    fun hasCallPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Loose plausibility check for the Settings field: at least 7
     * digits, and only phone-number characters. A warning, never a
     * hard block — the owner can save any number.
     */
    fun isPlausiblePhoneNumber(number: String): Boolean {
        if (number.count { it.isDigit() } < 7) return false
        return number.all { it.isDigit() || it in "+() -" }
    }

    /** One screen wake. Gates first; only then counts. */
    fun onScreenOn(context: Context) {
        val appContext = context.applicationContext
        if (!LostModeStore.isOn(appContext)) return
        if (!SecurityPrefs.isAutoCallEnabled(appContext)) return
        if (SecurityPrefs.trustedNumber(appContext).isNullOrBlank()) return
        if (!hasCallPermission(appContext)) return
        if (burstActive) return // wakes during a burst do not count
        val serviceScope = scope ?: return
        wakeCount++
        if (wakeCount < WAKES_PER_BURST) return
        startBurst(appContext, serviceScope)
    }

    private fun startBurst(context: Context, serviceScope: CoroutineScope) {
        wakeCount = 0
        attemptsMade = 0
        burstActive = true
        redialJob = serviceScope.launch {
            while (isActive &&
                attemptsMade < MAX_ATTEMPTS_PER_BURST &&
                LostModeStore.isOn(context)
            ) {
                // Never interrupt a call that is already in progress
                // (the owner may have answered): skip this round
                // without counting it, and check again in 60 seconds.
                if (isCallIdle(context)) {
                    // Counted even if the dial fails (no dialer,
                    // permission revoked mid-burst): a failing dial
                    // must still end the burst after 5 tries.
                    dial(context)
                    attemptsMade++
                }
                if (attemptsMade < MAX_ATTEMPTS_PER_BURST) {
                    delay(REDIAL_DELAY_MS)
                }
            }
            burstActive = false
            wakeCount = 0
        }
    }

    /**
     * Dials the trusted number with a plain ACTION_CALL intent. The
     * number is sanitized to digits plus a leading + before dialling.
     * Returns false when there is nothing diallable or the dialer
     * could not be started.
     */
    private fun dial(context: Context): Boolean {
        val raw = SecurityPrefs.trustedNumber(context) ?: return false
        val sanitized = raw.filter { it.isDigit() || it == '+' }
        if (sanitized.count { it.isDigit() } < 7) return false
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$sanitized"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // No dialer app, or the background start was denied: the
            // burst still counts the attempt (see startBurst).
            false
        }
    }

    /**
     * True when no call is active. The call-state read needs
     * READ_PHONE_STATE on newer Android; without it (or on error) we
     * cannot know, so we dial anyway — best effort, and the
     * permission is part of the consent flow so it is normally
     * granted.
     */
    private fun isCallIdle(context: Context): Boolean {
        if (!TrackingPermissions.hasPhoneState(context)) return true
        return try {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephony.callState == TelephonyManager.CALL_STATE_IDLE
        } catch (e: SecurityException) {
            true
        }
    }
}
