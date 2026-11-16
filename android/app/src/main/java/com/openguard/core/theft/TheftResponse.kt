package com.openguard.core.theft

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.openguard.core.security.LostModeStore
import com.openguard.core.security.SecurityPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Theft response coordinator. Hosted by LocationService (the tracking
 * foreground service) because the two things it needs cannot live
 * anywhere else: ACTION_SCREEN_ON can only be received by a
 * dynamically registered receiver (a manifest receiver never fires for
 * it), and the chaos-mode timer must outlive any worker run.
 *
 * LostMode.turnOn starts LocationService so this host is alive even
 * when tracking was off; BootReceiver restarts it after a reboot.
 * If the service is not running, nothing here runs — the decoy lock
 * and the boot receiver still hold the line, and the response resumes
 * when tracking next runs.
 *
 * Everything is gated on Lost Mode being on: AutoCaller checks it on
 * every wake and every redial, ChaosMode checks it on every tick, and
 * the supervisor below stops both the moment it sees Lost Mode off.
 * Nothing here runs while Lost Mode is off.
 */
object TheftResponse {

    /** How often the supervisor re-checks Lost Mode / chaos state. */
    private const val SUPERVISOR_INTERVAL_MS = 15_000L

    private var started = false
    private var receiverRegistered = false
    private var supervisorJob: Job? = null
    private val screenWakeReceiver = ScreenWakeReceiver()

    /**
     * Starts the theft response: registers the screen-on receiver and
     * launches the supervisor in the service's scope. Idempotent —
     * LocationService calls it on every start intent.
     */
    fun start(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        AutoCaller.attach(scope)
        try {
            ContextCompat.registerReceiver(
                appContext,
                screenWakeReceiver,
                IntentFilter(Intent.ACTION_SCREEN_ON),
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        } catch (e: Exception) {
            // Receiver registration failed (OEM restriction): the wake
            // counter cannot run, but the chaos supervisor below still
            // works — it does not need the receiver.
            receiverRegistered = false
        }
        supervisorJob = scope.launch {
            while (isActive) {
                supervise(appContext, scope)
                delay(SUPERVISOR_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops everything: unregisters the receiver, cancels the
     * supervisor, stops any chaos alarm, resets the wake counter.
     * Idempotent — safe to call from LocationService.onDestroy and
     * from any other shutdown path.
     */
    fun stop(context: Context) {
        if (!started) return
        started = false
        supervisorJob?.cancel()
        supervisorJob = null
        if (receiverRegistered) {
            try {
                context.applicationContext.unregisterReceiver(screenWakeReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was never registered; nothing to unregister.
            }
            receiverRegistered = false
        }
        AutoCaller.detach()
        ChaosMode.stop()
    }

    /** Entry point for ScreenWakeReceiver; AutoCaller gates on Lost Mode. */
    fun onScreenOn(context: Context) {
        AutoCaller.onScreenOn(context.applicationContext)
    }

    private fun supervise(context: Context, scope: CoroutineScope) {
        if (!LostModeStore.isOn(context)) {
            // Lost Mode turned off: stop the response and reset the
            // wake counter. The redial and chaos loops also check
            // Lost Mode on every step, so this is a backstop, not the
            // only gate.
            AutoCaller.reset()
            ChaosMode.stop()
            return
        }
        // Lost Mode is on: run chaos mode if the SIM-removal flag was
        // set (by SimMonitor, from the worker or the SIM broadcast).
        // start() is idempotent, so calling it every tick is cheap.
        if (SecurityPrefs.isChaosModeActive(context)) {
            ChaosMode.start(context, scope)
        }
    }
}
