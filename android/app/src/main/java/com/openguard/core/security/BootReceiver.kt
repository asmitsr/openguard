package com.openguard.core.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openguard.core.evidence.EvidenceService
import com.openguard.core.location.LocationService

/**
 * After a reboot, Lost Mode comes back: if the phone was lost when it
 * rebooted, the decoy lock shows again as soon as the boot completes.
 *
 * Honest limit: BOOT_COMPLETED fires after the user unlocks (the
 * credential-encrypted storage holding Lost Mode state is unavailable
 * before that), so there is a short window after a reboot where the
 * phone is usable until it is unlocked once. A forced restart
 * (holding power ~10 seconds) cannot be blocked by any app — this
 * receiver is the mitigation for it, not a prevention.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!LostModeStore.isOn(context)) return
        DecoyLock.show(context)
        // Theft response (wake counter + auto-call, chaos timer) is
        // hosted by the tracking foreground service: restart it so a
        // force-reboot does not silently kill the response — the chaos
        // flag survives in prefs, and the service's supervisor picks
        // it up on start. Starting a foreground service from
        // BOOT_COMPLETED is an explicit Android 12+ exemption; the
        // try/catch covers the while-in-use location check
        // (SecurityException) when background location was revoked.
        // If the start is denied, the decoy still holds and the theft
        // response resumes when tracking next runs.
        try {
            LocationService.start(context)
        } catch (e: IllegalStateException) {
            // Background start denied.
        } catch (e: SecurityException) {
            // Background location revoked.
        }
        // Evidence records only while Lost Mode is on — and it stays
        // on across the reboot, so restart it too. Same BOOT_COMPLETED
        // exemption and the same try/catch (a denied start just waits
        // for the periodic worker's backstop).
        try {
            EvidenceService.start(context)
        } catch (e: IllegalStateException) {
            // Background start denied.
        } catch (e: SecurityException) {
            // Camera/microphone permission revoked.
        }
    }
}
