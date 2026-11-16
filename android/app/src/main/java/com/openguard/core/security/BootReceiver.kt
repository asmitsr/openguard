package com.openguard.core.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
        if (LostModeStore.isOn(context)) DecoyLock.show(context)
    }
}
