package com.openguard.core.security

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Fake shutdown screen: when Lost Mode is on and the fake-shutdown
 * setting is enabled, the power-menu guard dismisses the system power
 * menu and shows this instead of the decoy lock — a pure black,
 * full-screen overlay with nothing on it. A real powered-off phone
 * shows nothing, and black is universal across OEMs, so the simplest
 * honest version is just black: no text, no logo, no animation.
 * A thief long-pressing power sees a phone that looks switched off
 * and is more likely to leave it alone (and powered, so it keeps
 * reporting its location).
 *
 * The decoy lock is already showing underneath: the fake screen goes
 * up on top of it. If the fake screen dies for any reason it does
 * NOT re-show itself — the decoy underneath is still there.
 *
 * OWNER EXIT (no visible hint anywhere — a thief must not discover
 * it by looking): LONG-PRESS the TOP-LEFT corner of the black
 * screen. That hides the fake screen and shows the decoy lock, and
 * the owner then unlocks with the app password or credential as
 * normal. The Settings copy must tell the owner this gesture.
 *
 * TRIGGER: only while Lost Mode is on (the power-menu guard only
 * fires in Lost Mode). A thief powering off a NOT-lost phone sees
 * the normal power menu.
 *
 * SELF-HIDE WATCHDOG: LostMode.turnOff cannot hide this overlay
 * (it does not know about it), so while the overlay is shown a
 * Handler checks every 5 seconds: if LostModeStore.isOn is false
 * (Lost Mode was turned off remotely while the fake screen was
 * showing), the overlay hides itself. The checks stop when the
 * overlay hides.
 *
 * Built with classic Android Views on purpose, mirroring DecoyLock:
 * a ComposeView outside an activity needs ViewTree owners that do
 * not resolve on this project's classpath (two CI runs failed on
 * exactly that). Same WindowManager addView and removeView pattern,
 * same main-thread handling, same LayoutParams flags.
 *
 * No notification for the fake screen: the decoy and the Lost Mode
 * notification already exist, and the fake screen is silent by
 * design — it pretends the phone is off.
 *
 * HONEST LIMITS:
 * - Holding power about 10 seconds forces a hardware restart
 *   (PMIC-level, unblockable) — the fake screen cannot stop it.
 * - A real reboot clears the fake screen. BootReceiver re-shows
 *   the decoy if Lost Mode is on; the fake screen does NOT re-show
 *   after reboot, by design.
 * - The power-menu window detection is OEM-fragile (Infinix XOS
 *   may name the power-menu window differently — the guard already
 *   carries this caveat, and a miss just means the menu is not
 *   dismissed).
 * - The overlay sits below the status bar and keyguard like the
 *   decoy (platform rule for TYPE_APPLICATION_OVERLAY).
 */
object FakeShutdownLock {

    private const val PREFS_NAME = "openguard_fake_shutdown"
    private const val KEY_ENABLED = "fake_shutdown_enabled"

    private const val WATCHDOG_INTERVAL_MS = 5_000L
    private const val EXIT_SIZE_DP = 100

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isShowing = false
    private var viewRef: View? = null
    private var appContextRef: Context? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when the owner enabled the fake shutdown screen. Default false. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Shows the black overlay. Returns false when the overlay
     * permission is not granted (the caller falls back to the decoy);
     * true when the overlay is being shown. Safe to call from any
     * thread; the WindowManager work runs on the main thread.
     */
    fun show(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) return false
        onMain { showNow(appContext) }
        return true
    }

    /** Removes the overlay. Safe to call from any thread. */
    fun hide(context: Context) {
        onMain { hideNow(context.applicationContext) }
    }

    // Runs on the main thread only.
    private fun showNow(appContext: Context) {
        if (isShowing) return
        val view = createFakeShutdownView(appContext)
        try {
            windowManager(appContext).addView(view, overlayParams())
        } catch (e: Exception) {
            // WindowManager rejected it (permission revoked between the
            // check and the add, or an OEM restriction). The caller
            // already fell back to the decoy only when show returned
            // false; here the decoy is underneath anyway.
            return
        }
        viewRef = view
        appContextRef = appContext
        isShowing = true
        startWatchdog()
    }

    // Runs on the main thread only.
    private fun hideNow(appContext: Context) {
        stopWatchdog()
        val view = viewRef
        try {
            view?.let { windowManager(appContext).removeView(it) }
        } catch (e: Exception) {
            // Already gone.
        }
        viewRef = null
        appContextRef = null
        isShowing = false
    }

    /**
     * Owner exit: hide the fake screen, then show the decoy lock.
     * Lost Mode is still on, so the owner unlocks with the app
     * password or credential as normal. Internal: called by the
     * overlay content (same file) for the hidden owner-exit. Not
     * part of the public API.
     */
    internal fun exitToDecoy(appContext: Context) {
        hideNow(appContext)
        DecoyLock.show(appContext)
    }

    /**
     * While the overlay is shown, check every 5 seconds whether Lost
     * Mode is still on. If it was turned off remotely, hide the
     * overlay (LostMode.turnOff cannot do it — it does not know
     * about this overlay). Stops when the overlay hides.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val ctx = appContextRef
            if (!isShowing || ctx == null) return
            if (!LostModeStore.isOn(ctx)) {
                hideNow(ctx)
                return
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun startWatchdog() {
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        mainHandler.removeCallbacks(watchdog)
    }

    private fun overlayParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        )

    private fun windowManager(context: Context): WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }
}

private fun createFakeShutdownView(appContext: Context): View {
    val root = FrameLayout(appContext).apply {
        // Pure black, nothing on it — a powered-off phone shows
        // nothing. Clickable so touches are swallowed, not passed
        // through to the decoy underneath.
        setBackgroundColor(Color.BLACK)
        isClickable = true
    }
    // Hidden owner-exit: long-press the top-left corner. Invisible —
    // no hint a thief could spot. About 100x100dp, big enough to
    // find by feel once you know it is there.
    val exitSize = appContext.dp100()
    val exitView = View(appContext).apply {
        layoutParams = FrameLayout.LayoutParams(exitSize, exitSize, Gravity.TOP or Gravity.START)
        isClickable = true
        isLongClickable = true
        setOnLongClickListener {
            FakeShutdownLock.exitToDecoy(appContext)
            true
        }
    }
    root.addView(exitView)
    return root
}

private fun Context.dp100(): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        100f,
        resources.displayMetrics,
    ).toInt()
