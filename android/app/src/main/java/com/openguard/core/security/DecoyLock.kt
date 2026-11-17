package com.openguard.core.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.openguard.OpenGuardApp
import com.openguard.R
import com.openguard.core.location.UploadWorker
import com.openguard.feature.security.LostModeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Decoy lock: a full-screen overlay that looks like a lock screen. Shown
 * the moment Lost Mode turns on, it displays the owner's message and a
 * PIN pad (or a pattern grid when the app lock password is a pattern).
 * Nothing behind the overlay can be touched: it covers every app
 * window, survives the Home button (it is a system window, not an
 * activity), and swallows Back.
 *
 * When NO app lock password is set, the pad is a pure decoy: any
 * entered code shows "wrong" and never unlocks. When one IS set, the
 * pad/grid is real — the correct password turns Lost Mode off — and
 * a wrong entry shows the identical "Wrong PIN" message, so a thief
 * cannot tell whether the pad is real. Wrong entries record a
 * failed_unlock_attempt event and count toward the AppLockPassword
 * lockout (5 failures, then 30s doubling to a 5-minute cap), shared
 * with the Lost Mode screen.
 *
 * The overlay is plainly OpenGuard's Lost Mode screen ("This phone is
 * lost" + the owner's message + "Protected by OpenGuard"). It never
 * hides that OpenGuard is running.
 *
 * Built with classic Android Views on purpose: a ComposeView outside an
 * activity needs ViewTreeLifecycleOwner and ViewTreeSavedStateRegistryOwner,
 * and those classes do not resolve on this project's compile classpath
 * (lifecycle-runtime-ktx exposes lifecycle-runtime as runtime-only, and
 * the savedstate ViewTree class is missing from the resolved artifact —
 * two CI runs failed on exactly these four references). Views need no
 * lifecycle or saved-state owners at all, so the overlay cannot hit
 * that problem again. The UI is identical to the previous Compose one.
 *
 * HONEST LIMITS (verified against the official LayoutParams docs):
 * TYPE_APPLICATION_OVERLAY sits above all app windows but BELOW
 * critical system windows — the status bar, the keyguard and system
 * dialogs. So the notification shade CAN still be pulled down from the
 * top edge (quick settings, flashlight), the power menu is NOT covered
 * (the accessibility-based power-menu guard dismisses it instead), and
 * when the phone is locked the real keyguard covers the overlay
 * (LostModeActivity is the surface there). The flashlight is blocked
 * only via device admin (camera disabled — see DeviceAdminManager).
 * A forced restart (holding power ~10 seconds) cannot be blocked by
 * any app; BootReceiver re-shows the decoy after the reboot.
 * The overlay dies with the app process; the LocationService watchdog
 * and the UploadWorker backstop re-show it.
 */
object DecoyLock {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Best-effort background work (server lost_mode_off, event upload):
    // never blocks the overlay, never crashes it on failure.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isShowing = false
    private var viewRef: View? = null
    private var messageViewRef: TextView? = null
    private var expectingRemoval = false
    private var appContextRef: Context? = null

    /** True when the user granted "Display over other apps". */
    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Opens the system "Display over other apps" screen for OpenGuard. */
    fun requestPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + activity.packageName),
        )
        activity.startActivity(intent)
    }

    /**
     * Shows the decoy overlay. Returns true when the overlay permission
     * is granted (the overlay is being shown); false when it is not
     * granted and the caller must fall back to the notification path.
     * Safe to call from any thread; the WindowManager work runs on the
     * main thread.
     */
    fun show(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!hasPermission(appContext)) return false
        onMain { showNow(appContext) }
        return true
    }

    /** Shows the overlay only if Lost Mode is on and it is not already up. */
    fun ensureShown(context: Context) {
        val appContext = context.applicationContext
        if (!LostModeStore.isOn(appContext)) return
        show(appContext)
    }

    /** Removes the overlay. Safe to call from any thread. */
    fun hide(context: Context) {
        onMain { hideNow(context.applicationContext) }
    }

    // Runs on the main thread only.
    private fun showNow(appContext: Context) {
        if (isShowing) {
            // Already up: refresh the message text only (a second
            // lost_mode_on may carry a new one). The PIN state survives
            // because the view is not rebuilt.
            messageViewRef?.text = ownerMessage(appContext)
            return
        }
        val (view, messageView) = createDecoyView(appContext)
        messageViewRef = messageView
        view.addOnAttachStateChangeListener(detachListener)
        try {
            windowManager(appContext).addView(view, overlayParams())
        } catch (e: Exception) {
            // WindowManager rejected it (permission revoked between the
            // check and the add, or an OEM restriction). The notification
            // fallback still shows the message.
            return
        }
        viewRef = view
        appContextRef = appContext
        isShowing = true
    }

    // Runs on the main thread only.
    private fun hideNow(appContext: Context) {
        val view = viewRef
        expectingRemoval = true
        try {
            view?.let { windowManager(appContext).removeView(it) }
        } catch (e: Exception) {
            // Already gone.
        }
        viewRef = null
        messageViewRef = null
        appContextRef = null
        isShowing = false
        expectingRemoval = false
    }

    /**
     * Owner exit: hide the decoy (it sits above every activity), then
     * open the Lost Mode screen, whose device-credential check proves
     * ownership. Starting an activity from here is allowed: the
     * SYSTEM_ALERT_WINDOW grant is a background-activity-start exemption.
     * Internal: called by the overlay content (same file) for the hidden
     * owner-exit. Not part of the public API.
     */
    internal fun exitToLostModeScreen(appContext: Context) {
        onMain {
            hideNow(appContext)
            val intent = Intent(appContext, LostModeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                appContext.startActivity(intent)
            } catch (e: Exception) {
                // Could not open the screen: bring the decoy back.
                showNow(appContext)
            }
        }
    }

    /**
     * Owner exit via the app lock password: clears Lost Mode locally
     * right away (the overlay hides, the camera re-enables, evidence
     * and the theft response stop), and tells the server best-effort
     * in the background — if the server call fails (no network), the
     * next heartbeat syncs lost_mode=false anyway.
     */
    internal fun exitWithAppPassword(appContext: Context) {
        LostMode.turnOff(appContext)
        ioScope.launch {
            try {
                val app = appContext.applicationContext as OpenGuardApp
                val deviceId = app.tokenStore.deviceId
                if (deviceId != null) {
                    app.commandRepository.createCommand(deviceId, "lost_mode_off")
                }
            } catch (e: Exception) {
                // Best-effort: the next heartbeat syncs lost_mode=false.
            }
        }
    }

    /** Records a wrong app-password attempt (decoy or Lost Mode screen). */
    internal fun recordFailedUnlockAttempt(appContext: Context, source: String) {
        ioScope.launch {
            try {
                val app = appContext.applicationContext as OpenGuardApp
                SecurityEvents.record(
                    app.database, "failed_unlock_attempt",
                    details = mapOf(
                        "source" to source,
                        "attempt" to AppLockPassword.consecutiveFailures(appContext),
                    ),
                    confirmed = true,
                )
                UploadWorker.enqueueOneTime(appContext)
            } catch (e: Exception) {
                // Best-effort: the event is lost if the DB write fails.
            }
        }
    }

    /**
     * If the overlay is detached unexpectedly (the system reclaimed it),
     * bring it back while Lost Mode is still on. Intentional removals
     * (hideNow) set expectingRemoval first, so they never re-show.
     */
    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            viewRef = null
            messageViewRef = null
            isShowing = false
            if (!expectingRemoval) {
                val ctx = appContextRef
                if (ctx != null && LostModeStore.isOn(ctx)) {
                    mainHandler.postDelayed({ ensureShown(ctx) }, 500)
                }
            }
        }
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

    private fun ownerMessage(appContext: Context): String =
        LostModeStore.message(appContext)?.takeIf { it.isNotBlank() }
            ?: "Please return this phone to its owner."
}

// Brand colors (same as the Compose theme: Midnight Navy + Champagne Gold
// + Soft Ivory), as raw ints because this file is outside Compose.
private const val COLOR_BG = 0xFF101B2D
private const val COLOR_CARD = 0xFF192A40
private const val COLOR_TEXT = 0xFFF4F3EF
private const val COLOR_SECONDARY = 0xFFA7B2C2
private const val COLOR_GOLD = 0xFFC6A46A
private const val COLOR_OUTLINE = 0xFF344358
private const val COLOR_ERROR = 0xFFCF6679

private fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

private fun circleDrawable(color: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

/**
 * Builds the lock screen: the owner's message, a PIN pad (or a
 * pattern grid when the app lock password is a pattern), and an
 * error on a wrong entry.
 *
 * When NO app lock password is set, the pad is a pure decoy: any
 * code shows "wrong" and never unlocks — the only exits are the
 * remote lost_mode_off command or the hidden owner-exit (long-press
 * the shield icon, then the device credential check).
 *
 * When an app lock password IS set, the pad/grid is REAL: the
 * correct password turns Lost Mode off (local clear + best-effort
 * server lost_mode_off). A wrong entry shows the identical "Wrong
 * PIN — try again" message, so a thief cannot tell whether the pad
 * is real; every wrong entry records a failed_unlock_attempt event
 * and counts toward the AppLockPassword lockout (5 failures, then
 * 30s doubling to a 5-minute cap), shared with the Lost Mode
 * screen. For MIXED (pattern + PIN) either secret unlocks, and a
 * toggle swaps between the pad and the grid.
 *
 * Discovery note (the owner asked why the pad rejected his
 * passwords): there is deliberately NO on-screen signal that the
 * pad is real — any signal a thief can read ("this pad accepts a
 * password") tells them guessing is worthwhile. The owner learns
 * it where he sets the password (Settings: "enter it on the Lost
 * Mode screen's PIN pad") and from the chat explanation. Two
 * implicit changes exist: the dot count matches the PIN length
 * when one is set (a 6-digit PIN shows 6 dots, not the decoy's 4),
 * and a pattern password replaces the pad with a grid. Both leak
 * the password's shape to a thief; the lockout is the real
 * protection, and without the dot count a 6-digit PIN could not
 * be entered at all.
 *
 * PIN/pattern state lives in this call's closure; the view is
 * built once per show, and message refreshes update the message
 * TextView only.
 */
private fun createDecoyView(appContext: Context): Pair<View, TextView> {
    val handler = Handler(Looper.getMainLooper())
    val appLockType = AppLockPassword.getType(appContext)
    val pinLength = AppLockPassword.pinLength(appContext)
    // Digit count on the pad: the owner's PIN length when a PIN is
    // set, else the decoy's 4.
    val padDigits =
        if (appLockType == AppLockType.PIN || appLockType == AppLockType.MIXED) pinLength else 4
    var showingPattern = appLockType == AppLockType.PATTERN
    var pin = ""
    var wrong = false

    fun lockoutRemainingMs(): Long = AppLockPassword.lockoutRemainingMs(appContext)

    fun defaultStatus(): String =
        if (showingPattern) "Draw your pattern to unlock" else "Enter PIN to unlock"

    fun textView(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(appContext).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    val root = LinearLayout(appContext).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(COLOR_BG.toInt())
        val pad = appContext.dp(24)
        setPadding(pad, pad, pad, pad)
    }

    fun spacer(heightDp: Int) {
        root.addView(
            View(appContext),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                appContext.dp(heightDp),
            ),
        )
    }

    spacer(32)
    // Hidden owner-exit: long-press the shield. No visible hint — a
    // stranger must not find it by looking.
    val shield = ImageView(appContext).apply {
        setImageResource(R.drawable.ic_shield)
        setColorFilter(COLOR_GOLD.toInt())
        layoutParams = LinearLayout.LayoutParams(
            appContext.dp(64),
            appContext.dp(64),
        )
        isClickable = true
        isLongClickable = true
        setOnLongClickListener {
            DecoyLock.exitToLostModeScreen(appContext)
            true
        }
    }
    root.addView(shield)
    spacer(24)
    root.addView(textView("This phone is lost", 24f, COLOR_TEXT.toInt(), bold = true))
    spacer(16)
    val messageView = textView(
        LostModeStore.message(appContext)?.takeIf { it.isNotBlank() }
            ?: "Please return this phone to its owner.",
        16f,
        COLOR_TEXT.toInt(),
    )
    root.addView(messageView)

    // Flexible space: pushes the entry area to the bottom.
    root.addView(
        View(appContext),
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ),
    )

    val statusView = textView(defaultStatus(), 14f, COLOR_SECONDARY.toInt())
    root.addView(statusView)
    spacer(12)

    // PIN container: dots + keys. Hidden when the pattern grid shows.
    val pinContainer = LinearLayout(appContext).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    val dotsRow = LinearLayout(appContext).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    val dots = (0 until padDigits).map {
        View(appContext).apply {
            background = circleDrawable(COLOR_OUTLINE.toInt())
            val size = appContext.dp(16)
            val margin = appContext.dp(8)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, 0, margin, 0)
            }
        }.also { dotsRow.addView(it) }
    }
    pinContainer.addView(dotsRow)
    pinContainer.addView(
        View(appContext),
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            appContext.dp(24),
        ),
    )
    root.addView(pinContainer)

    // Pattern container: the grid. Hidden unless the pattern shows.
    val patternContainer = LinearLayout(appContext).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    val patternGrid = PatternGridView(appContext).apply {
        layoutParams = LinearLayout.LayoutParams(
            appContext.dp(280),
            appContext.dp(280),
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }
    patternContainer.addView(patternGrid)
    root.addView(patternContainer)

    // MIXED only: swaps between the pad and the grid.
    val toggleView = textView("Draw pattern instead", 14f, COLOR_GOLD.toInt()).apply {
        isClickable = true
        visibility = if (appLockType == AppLockType.MIXED) View.VISIBLE else View.GONE
    }
    root.addView(toggleView)
    spacer(8)
    root.addView(textView("Protected by OpenGuard", 12f, COLOR_SECONDARY.toInt()))

    fun refreshDots() {
        dots.forEachIndexed { index, dot ->
            dot.background = circleDrawable(
                if (index < pin.length) COLOR_TEXT.toInt() else COLOR_OUTLINE.toInt(),
            )
        }
    }

    // Ticks the lockout countdown once a second; stops on its own when
    // the lockout expires or the overlay is gone.
    val lockoutTick = object : Runnable {
        override fun run() {
            if (!root.isAttachedToWindow) return
            val remaining = lockoutRemainingMs()
            if (remaining > 0) {
                statusView.text = "Try again in ${(remaining + 999) / 1000}s"
                statusView.setTextColor(COLOR_ERROR.toInt())
                handler.postDelayed(this, 1000)
            } else {
                // Lockout expired: mirrors refreshStatus()'s expired branch
                // inline. A direct call is impossible here — local functions
                // are only visible after their declaration, and refreshStatus
                // is declared below (it must stay below startLockoutTick,
                // which needs lockoutTick first: a declaration-order cycle).
                patternGrid.isEnabled = true
                statusView.text = defaultStatus()
                statusView.setTextColor(COLOR_SECONDARY.toInt())
            }
        }
    }

    fun startLockoutTick() {
        handler.removeCallbacks(lockoutTick)
        handler.post(lockoutTick)
    }

    fun refreshStatus() {
        val remaining = lockoutRemainingMs()
        patternGrid.isEnabled = remaining <= 0
        if (remaining > 0) {
            statusView.text = "Try again in ${(remaining + 999) / 1000}s"
            statusView.setTextColor(COLOR_ERROR.toInt())
            startLockoutTick()
        } else {
            statusView.text = defaultStatus()
            statusView.setTextColor(COLOR_SECONDARY.toInt())
        }
    }

    val clearWrong = Runnable {
        pin = ""
        wrong = false
        refreshDots()
        refreshStatus()
    }

    fun showWrong(message: String) {
        wrong = true
        statusView.text = message
        statusView.setTextColor(COLOR_ERROR.toInt())
        handler.postDelayed(clearWrong, 900)
    }

    fun submitPin() {
        val entered = pin
        if (appLockType == null) {
            // No password set: the decoy never unlocks.
            showWrong("Wrong PIN — try again")
            return
        }
        if (AppLockPassword.verifyPin(appContext, entered)) {
            AppLockPassword.resetFailures(appContext)
            DecoyLock.exitWithAppPassword(appContext)
        } else if (AppLockPassword.verifyDuressPin(appContext, entered)) {
            // Duress: the identical "Wrong PIN" to the coercer, while
            // the silent response fires. Not a failure (no lockout
            // count, no failed_unlock_attempt from this branch — the
            // response records its own source=duress event).
            DuressResponse.trigger(appContext)
            showWrong("Wrong PIN — try again")
        } else {
            AppLockPassword.recordFailure(appContext)
            DecoyLock.recordFailedUnlockAttempt(appContext, "decoy")
            if (AppLockPassword.consecutiveFailures(appContext) ==
                AppLockPassword.MAX_ATTEMPTS
            ) {
                // 5th failure — the lockout just engaged: photograph
                // the intruder (only when the owner enabled it).
                IntruderSelfie.onAppLockLockout(appContext)
            }
            if (lockoutRemainingMs() > 0) {
                pin = ""
                wrong = false
                refreshDots()
                refreshStatus()
            } else {
                showWrong("Wrong PIN — try again")
            }
        }
    }

    fun onPatternEntered(cells: List<Int>) {
        if (wrong) return
        if (lockoutRemainingMs() > 0) {
            refreshStatus()
            return
        }
        if (appLockType == null) return
        // Too short to be valid, or the wrong pattern: a failed
        // attempt either way (a thief scribbling gets no free tries).
        if (cells.size < AppLockPassword.MIN_PATTERN_CELLS ||
            !AppLockPassword.verifyPattern(appContext, cells)
        ) {
            AppLockPassword.recordFailure(appContext)
            DecoyLock.recordFailedUnlockAttempt(appContext, "decoy")
            if (AppLockPassword.consecutiveFailures(appContext) ==
                AppLockPassword.MAX_ATTEMPTS
            ) {
                // 5th failure — the lockout just engaged: photograph
                // the intruder (only when the owner enabled it).
                IntruderSelfie.onAppLockLockout(appContext)
            }
            if (lockoutRemainingMs() > 0) {
                refreshStatus()
            } else {
                showWrong("Wrong pattern — try again")
            }
            return
        }
        AppLockPassword.resetFailures(appContext)
        DecoyLock.exitWithAppPassword(appContext)
    }

    fun pressDigit(digit: String) {
        if (wrong || pin.length >= padDigits) return
        if (lockoutRemainingMs() > 0) {
            refreshStatus()
            return
        }
        pin += digit
        refreshDots()
        if (pin.length == padDigits) {
            submitPin()
        }
    }

    fun pressBackspace() {
        if (!wrong) {
            pin = pin.dropLast(1)
            refreshDots()
        }
    }

    fun swapEntryMode() {
        if (appLockType != AppLockType.MIXED) return
        showingPattern = !showingPattern
        pin = ""
        wrong = false
        handler.removeCallbacks(clearWrong)
        refreshDots()
        pinContainer.visibility = if (showingPattern) View.GONE else View.VISIBLE
        patternContainer.visibility = if (showingPattern) View.VISIBLE else View.GONE
        toggleView.text = if (showingPattern) "Use PIN instead" else "Draw pattern instead"
        refreshStatus()
    }

    fun pinKey(label: String, onClick: () -> Unit): TextView =
        textView(label, 24f, COLOR_TEXT.toInt()).apply {
            background = circleDrawable(COLOR_CARD.toInt())
            gravity = Gravity.CENTER
            val size = appContext.dp(72)
            val margin = appContext.dp(12)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, 0, margin, 0)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫"),
    )
    keys.forEach { row ->
        val rowLayout = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.forEach { key ->
            when (key) {
                "" -> {
                    val size = appContext.dp(72)
                    val margin = appContext.dp(12)
                    rowLayout.addView(
                        View(appContext),
                        LinearLayout.LayoutParams(size, size).apply {
                            setMargins(margin, 0, margin, 0)
                        },
                    )
                }
                "⌫" -> rowLayout.addView(pinKey("⌫") { pressBackspace() })
                else -> rowLayout.addView(pinKey(key) { pressDigit(key) })
            }
        }
        pinContainer.addView(rowLayout)
        pinContainer.addView(
            View(appContext),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                appContext.dp(16),
            ),
        )
    }

    patternGrid.onPatternComplete = { cells -> onPatternEntered(cells) }
    toggleView.setOnClickListener { swapEntryMode() }

    // Initial state: the right entry mode, and the countdown if a
    // lockout from an earlier session is still running.
    pinContainer.visibility = if (showingPattern) View.GONE else View.VISIBLE
    patternContainer.visibility = if (showingPattern) View.VISIBLE else View.GONE
    toggleView.text = if (showingPattern) "Use PIN instead" else "Draw pattern instead"
    refreshStatus()

    return root to messageView
}
