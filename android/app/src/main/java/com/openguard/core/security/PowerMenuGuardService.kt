package com.openguard.core.security

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Power-menu guard: while Lost Mode is on, the system power menu
 * (long-press power) is dismissed the moment it appears, and the decoy
 * lock shows instead. The owner exits via the decoy's hidden owner-exit
 * path; after that the power menu works normally.
 *
 * Minimal scope: the service only receives window-state-changed events,
 * only acts when Lost Mode is on and the guard is enabled in Settings,
 * and never retrieves window content (see
 * accessibility_service_config). It reads only the event's package and
 * class names — never screen text, never anything typed.
 *
 * Detection: the AOSP power menu is
 * com.android.systemui.globalactions.GlobalActionsDialog; the match
 * accepts any SystemUI window whose class mentions GlobalActions or
 * PowerMenu. OEM skins (Infinix XOS included) may name it differently
 * — the guard must be tested on the real phone, and a miss only means
 * the menu is not dismissed, never a crash or a wrong dismissal.
 *
 * Honest limit: holding power ~10 seconds forces a hardware restart
 * (PMIC-level) on virtually all phones — no software, accessibility
 * service, or app can block that. The guard blocks the normal
 * power-off and restart MENU; BootReceiver re-locks the phone after
 * any reboot. Never claim the phone "cannot be turned off".
 */
class PowerMenuGuardService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!PowerMenuGuard.isGuardEnabled(this)) return
        if (!LostModeStore.isOn(this)) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != "com.android.systemui") return
        val className = event.className?.toString().orEmpty()
        if (!className.contains("GlobalActions") && !className.contains("PowerMenu")) return
        // Power menu appeared during Lost Mode: dismiss it and bring
        // the decoy lock back to the front — or the fake shutdown
        // screen, when the owner enabled it. If the fake screen
        // cannot show (no overlay permission), fall back to the
        // decoy, same as before.
        performGlobalAction(GLOBAL_ACTION_BACK)
        if (FakeShutdownLock.isEnabled(this)) {
            if (!FakeShutdownLock.show(this)) DecoyLock.show(this)
        } else {
            DecoyLock.show(this)
        }
    }

    override fun onInterrupt() {
        // Nothing to interrupt: the guard only reacts to events.
    }
}

object PowerMenuGuard {

    /** True when the user enabled OpenGuard in Accessibility settings. */
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val name = ComponentName(context, PowerMenuGuardService::class.java).flattenToString()
        return enabled.split(':').any { it == name }
    }

    /** In-app toggle (Settings): default on once the service is enabled. */
    fun isGuardEnabled(context: Context): Boolean =
        SecurityPrefs.isPowerMenuGuardEnabled(context)

    fun setGuardEnabled(context: Context, enabled: Boolean) =
        SecurityPrefs.setPowerMenuGuardEnabled(context, enabled)

    /** Opens the system Accessibility settings (the service cannot be enabled programmatically). */
    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
