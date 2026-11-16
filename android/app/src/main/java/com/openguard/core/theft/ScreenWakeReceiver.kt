package com.openguard.core.theft

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Counts screen wakes while Lost Mode is on. ACTION_SCREEN_ON cannot
 * be received by a manifest-registered receiver — the platform only
 * delivers it to receivers registered dynamically — so TheftResponse
 * registers this from LocationService (a foreground service) and this
 * class exists only to be that registered receiver. It does no work
 * itself: every wake is handed to AutoCaller, which gates on Lost
 * Mode, the auto-call toggle, the trusted number and the CALL_PHONE
 * permission before counting anything.
 */
class ScreenWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_SCREEN_ON) return
        TheftResponse.onScreenOn(context)
    }
}
