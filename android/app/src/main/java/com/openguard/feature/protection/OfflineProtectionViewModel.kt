package com.openguard.feature.protection

import android.content.Context
import androidx.lifecycle.ViewModel
import com.openguard.core.location.BatteryBurst
import com.openguard.core.location.TrackingState
import com.openguard.core.security.DeadManSwitch
import com.openguard.core.security.FailedAttemptWipe
import com.openguard.core.security.OfflineAutoLock
import com.openguard.core.theft.SnatchDetector
import com.openguard.core.theft.SnatchSensitivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OfflineProtectionUiState(
    val offlineEnabled: Boolean = false,
    val thresholdHours: Int = 12,
    val offlineDurationMs: Long = 0L,
    val hasNetwork: Boolean = true,
    val warningActive: Boolean = false,
    val burstEnabled: Boolean = false,
    val burstSentThisCycle: Boolean = false,
    val lastBurstAt: Long = 0L,
    val snatchEnabled: Boolean = false,
    val snatchSensitivity: SnatchSensitivity = SnatchSensitivity.MEDIUM,
    /** Enabled AND tracking is on — the detector is listening right now. */
    val snatchActive: Boolean = false,
    val lastSnatchAt: Long = 0L,
    val deadmanEnabled: Boolean = false,
    val lockDays: Int = 7,
    val wipeDays: Int = 30,
    val daysSinceUnlock: Long = 0L,
    val deadmanWarningActive: Boolean = false,
    val wipecapEnabled: Boolean = false,
    val attemptCap: Int = 10,
    val wipeEsim: Boolean = false,
    val failedCount: Int = 0,
)

/**
 * Offline protection screen state. Everything here is local to this
 * phone (SharedPreferences + WorkManager) — no server calls, so every
 * read is synchronous and refresh() needs no coroutine.
 */
class OfflineProtectionViewModel(private val appContext: Context) : ViewModel() {

    private val _state = MutableStateFlow(OfflineProtectionUiState())
    val state: StateFlow<OfflineProtectionUiState> = _state.asStateFlow()

    /**
     * Re-reads all state and re-ensures the workers. Both
     * ensure-scheduled calls are idempotent (KEEP), so a force-stop —
     * which cancels all WorkManager work until the app runs again —
     * does not silently leave a protection dead after the owner opens
     * this screen.
     */
    fun refresh() {
        if (OfflineAutoLock.isEnabled(appContext)) OfflineAutoLock.ensureScheduled(appContext)
        BatteryBurst.ensureScheduled(appContext)
        if (DeadManSwitch.isEnabled(appContext) || FailedAttemptWipe.isEnabled(appContext)) {
            DeadManSwitch.ensureWatchScheduled(appContext)
        }
        _state.value = OfflineProtectionUiState(
            offlineEnabled = OfflineAutoLock.isEnabled(appContext),
            thresholdHours = OfflineAutoLock.thresholdHours(appContext),
            offlineDurationMs = OfflineAutoLock.offlineDurationMs(appContext),
            hasNetwork = OfflineAutoLock.hasNetwork(appContext),
            warningActive = OfflineAutoLock.isWarningActive(appContext),
            burstEnabled = BatteryBurst.isEnabled(appContext),
            burstSentThisCycle = BatteryBurst.burstSent(appContext),
            lastBurstAt = BatteryBurst.lastBurstAt(appContext),
            snatchEnabled = SnatchDetector.isEnabled(appContext),
            snatchSensitivity = SnatchDetector.sensitivity(appContext),
            snatchActive = SnatchDetector.isEnabled(appContext) && TrackingState.isTracking.value,
            lastSnatchAt = SnatchDetector.lastDetectionAt(appContext),
            deadmanEnabled = DeadManSwitch.isEnabled(appContext),
            lockDays = DeadManSwitch.lockDays(appContext),
            wipeDays = DeadManSwitch.wipeDays(appContext),
            daysSinceUnlock = DeadManSwitch.daysSinceUnlock(appContext),
            deadmanWarningActive = DeadManSwitch.isWarningActive(appContext),
            wipecapEnabled = FailedAttemptWipe.isEnabled(appContext),
            attemptCap = FailedAttemptWipe.attemptCap(appContext),
            wipeEsim = FailedAttemptWipe.wipeEsim(appContext),
            failedCount = FailedAttemptWipe.failedCount(appContext),
        )
    }

    fun setOfflineEnabled(enabled: Boolean) {
        OfflineAutoLock.setEnabled(appContext, enabled)
        refresh()
    }

    fun setThreshold(hours: Int) {
        OfflineAutoLock.setThresholdHours(appContext, hours)
        refresh()
    }

    fun cancelAutoLock() {
        OfflineAutoLock.cancelPending(appContext)
        refresh()
    }

    fun setBurstEnabled(enabled: Boolean) {
        BatteryBurst.setEnabled(appContext, enabled)
        refresh()
    }

    /**
     * Enabling takes effect at once when tracking is already running
     * (the detector starts now); otherwise it starts with tracking.
     * Disabling stops the listener immediately.
     */
    fun setSnatchEnabled(enabled: Boolean) {
        SnatchDetector.setEnabled(appContext, enabled)
        if (enabled) {
            if (TrackingState.isTracking.value) SnatchDetector.start(appContext)
        } else {
            SnatchDetector.stop()
        }
        refresh()
    }

    fun setSnatchSensitivity(value: SnatchSensitivity) {
        SnatchDetector.setSensitivity(appContext, value)
        refresh()
    }

    fun setDeadmanEnabled(enabled: Boolean) {
        DeadManSwitch.setEnabled(appContext, enabled)
        refresh()
    }

    fun setLockDays(days: Int) {
        DeadManSwitch.setLockDays(appContext, days)
        refresh()
    }

    fun setWipeDays(days: Int) {
        DeadManSwitch.setWipeDays(appContext, days)
        refresh()
    }

    /**
     * Test hook: moves the unlock clock just past the lock threshold so
     * the next check fires the lock. The owner locks the phone right
     * after tapping; the wipe threshold stays days away, so this can
     * never trigger the wipe.
     */
    fun testDeadmanLock() {
        DeadManSwitch.testLockFiring(appContext)
        refresh()
    }

    fun setWipecapEnabled(enabled: Boolean) {
        FailedAttemptWipe.setEnabled(appContext, enabled)
        refresh()
    }

    fun setAttemptCap(cap: Int) {
        FailedAttemptWipe.setAttemptCap(appContext, cap)
        refresh()
    }

    fun setWipeEsim(enabled: Boolean) {
        FailedAttemptWipe.setWipeEsim(appContext, enabled)
        refresh()
    }
}
