package com.openguard.core.security

import android.content.Context
import com.openguard.OpenGuardApp
import com.openguard.core.location.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Silent duress response: the owner entered the duress PIN under
 * coercion (someone forcing them to unlock the phone). The entry
 * screen already showed the identical "Wrong PIN — try again" as a
 * wrong password, so the coercer learns nothing. Here, silently:
 * (1) turn Lost Mode on if it is not already on (decoy + evidence
 * recording + theft response come along — in practice Lost Mode is
 * already on, since the duress PIN is only entered on the decoy pad
 * or the Lost Mode screen); (2) record a failed_unlock_attempt
 * security event (source=duress); (3) send a silent SMS to the
 * trusted number via TrustedAlerts (1/hour cap, best-effort).
 *
 * ABORT: within 60 seconds the owner can enter the REAL app lock
 * password on the same screen — the normal exit turns Lost Mode off.
 * After 60s the response stands (Lost Mode stays on until the owner
 * turns it off normally). The 60s window is guidance for the owner,
 * not enforced in code — nothing stops the owner unlocking later.
 *
 * The duress PIN never unlocks the phone. A thief guessing it by
 * chance triggers the response — that is the design (it still
 * protects the owner). Best-effort throughout: never crash the
 * entry screen.
 */
object DuressResponse {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun trigger(context: Context) {
        val appContext = context.applicationContext
        if (!LostModeStore.isOn(appContext)) {
            LostMode.turnOn(appContext, "Duress PIN entered")
        }
        ioScope.launch {
            try {
                val app = appContext as OpenGuardApp
                SecurityEvents.record(
                    app.database, "failed_unlock_attempt",
                    details = mapOf("source" to "duress"),
                    confirmed = true,
                )
                UploadWorker.enqueueOneTime(appContext)
            } catch (e: Exception) {
                // Best-effort: the event is lost if the DB write fails.
            }
        }
        TrustedAlerts.onDuress(appContext)
    }
}
