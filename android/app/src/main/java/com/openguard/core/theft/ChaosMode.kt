package com.openguard.core.theft

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import com.openguard.core.security.DecoyLock
import com.openguard.core.security.LostModeStore
import com.openguard.core.security.RingService
import com.openguard.core.security.SecurityPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Chaos mode: the SIM was removed while Lost Mode is on, so the phone
 * can no longer call or upload — it makes itself as loud and unusable
 * as possible instead, until the owner turns Lost Mode off.
 *
 * EXACT SEQUENCE (one tick every TICK_MS = 10 seconds, driven by the
 * tracking service's scope so it survives any worker finishing):
 * - Every tick: the decoy overlay is re-shown if it is gone, the alarm
 *   and media volumes are forced back to maximum (the thief can still
 *   press the hardware volume keys — the defense is re-maxing every
 *   10 seconds, not blocking the keys), and RingService is (re)started.
 *   RingService stops itself after 2 minutes, so the restart each tick
 *   caps any silence gap at one tick.
 * - One app launch per tick, cycling in order: the camera app, then
 *   YouTube, then Maps, Chrome and Photos, then back to the camera.
 *   Every launch opens BEHIND the decoy overlay, which stays up the
 *   whole time and swallows all touches — the thief cannot interact
 *   with anything that opens. A missing app is skipped.
 * - Everything stops ONLY on owner exit: Lost Mode off clears the
 *   chaos flag (LostMode.turnOff) and the TheftResponse supervisor
 *   stops this loop; the alarm is silenced and the media volume is
 *   restored to what it was before chaos started.
 *
 * NO HIDDEN CAPTURE: the camera app is opened with the normal
 * ACTION_IMAGE_CAPTURE intent — the plain camera app, visibly. It
 * never takes a picture by itself, and the thief cannot tap anything
 * behind the overlay. There is no CameraX, no hidden photo or video
 * capture, no silent recording anywhere in this file. Note that when
 * device admin is active, Lost Mode disables the camera entirely, so
 * the camera app then opens to its disabled notice instead of a
 * viewfinder — still disruptive, and the flashlight stays blocked.
 *
 * BACKGROUND LAUNCHES: starting these activities from a service is
 * allowed here — the official background-activity-start exemptions
 * include both "the app has a visible window" (the decoy overlay is
 * one) and "the app has the SYSTEM_ALERT_WINDOW permission granted".
 * Every launch is still best-effort with try/catch: without the
 * overlay permission the system may block the launch, and then the
 * alarm loop alone carries the response.
 *
 * HONEST LIMITS: the overlay sits below the status bar (platform
 * rule), so the notification shade can still be pulled down; and a
 * ~10 second power hold still force-reboots the phone — BootReceiver
 * re-locks it and restarts the tracking service after the reboot, so
 * chaos resumes from the persisted flag.
 */
object ChaosMode {

    /** One chaos tick: re-max volume, re-arm the alarm, launch one app. */
    private const val TICK_MS = 10_000L

    /**
     * Apps launched in order after the camera. Each needs a queries
     * entry in AndroidManifest.xml (Android 11+ package visibility),
     * or getLaunchIntentForPackage returns null and the app is
     * skipped. YouTube also has a web fallback below.
     */
    private val CYCLE_PACKAGES = listOf(
        "com.google.android.youtube",
        "com.google.android.apps.maps",
        "com.android.chrome",
        "com.google.android.apps.photos",
    )

    private var job: Job? = null
    private var cycleIndex = 0
    private var previousMusicVolume: Int? = null

    /**
     * Starts chaos mode. Idempotent — the TheftResponse supervisor
     * calls this every tick while the flag is set. Gates on Lost Mode
     * and the persisted chaos flag, so a stray call can never start
     * it while Lost Mode is off.
     */
    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        if (!LostModeStore.isOn(appContext)) return
        if (!SecurityPrefs.isChaosModeActive(appContext)) return
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        previousMusicVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        cycleIndex = 0
        job = scope.launch {
            try {
                while (isActive &&
                    LostModeStore.isOn(appContext) &&
                    SecurityPrefs.isChaosModeActive(appContext)
                ) {
                    // Overlay first: it is the thing the thief cannot
                    // get past, and it must be up before apps open.
                    DecoyLock.ensureShown(appContext)
                    maxVolumes(audio)
                    // No-op while already ringing; re-arms the alarm
                    // within one tick after its 2-minute auto-stop.
                    RingService.start(appContext)
                    launchNextApp(appContext)
                    delay(TICK_MS)
                }
            } finally {
                // Runs on owner exit, on flag clear, and on service
                // shutdown alike: silence the alarm and give the
                // owner their media volume back.
                RingService.stop(appContext)
                previousMusicVolume?.let {
                    try {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
                    } catch (e: Exception) {
                        // Volume restore is best effort.
                    }
                }
                previousMusicVolume = null
            }
        }
    }

    /** Stops chaos mode. Idempotent; the loop's finally does cleanup. */
    fun stop() {
        job?.cancel()
        job = null
        cycleIndex = 0
    }

    /** Forces the alarm and media streams back to maximum. */
    private fun maxVolumes(audio: AudioManager) {
        try {
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0,
            )
        } catch (e: Exception) {
            // Volume control failed on this device: the alarm still
            // rings at whatever volume it has.
        }
    }

    /**
     * Launches the next app in the cycle, behind the overlay. Step 0
     * is the camera app (implicit intent, needs no package query);
     * later steps are explicit package launches with a web fallback
     * for YouTube. Every launch is best-effort.
     */
    private fun launchNextApp(context: Context) {
        val step = cycleIndex
        cycleIndex = (cycleIndex + 1) % (CYCLE_PACKAGES.size + 1)
        if (step == 0) {
            // Plain camera app, visibly opened. It never takes a
            // picture by itself; the overlay blocks all touches.
            startBestEffort(
                context,
                Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val pkg = CYCLE_PACKAGES[step - 1]
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch != null) {
            startBestEffort(context, launch)
            return
        }
        // Package not installed (or not visible): YouTube falls back
        // to its web address, which opens the app or a browser.
        if (pkg == "com.google.android.youtube") {
            startBestEffort(
                context,
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun startBestEffort(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Background start denied, or no app handles the intent:
            // the alarm loop carries the response on its own.
        }
    }
}
