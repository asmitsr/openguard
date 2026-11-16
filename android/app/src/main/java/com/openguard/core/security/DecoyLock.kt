package com.openguard.core.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import com.openguard.R
import com.openguard.feature.security.LostModeActivity
import com.openguard.ui.OpenGuardTheme
import com.openguard.ui.TintedIcon
import kotlinx.coroutines.delay

/**
 * Decoy lock: a full-screen overlay that looks like a lock screen. Shown
 * the moment Lost Mode turns on, it displays the owner's message and a
 * PIN pad — any entered code always shows "wrong password" and never
 * unlocks. Nothing behind the overlay can be touched: it covers every
 * app window, survives the Home button (it is a system window, not an
 * activity), and swallows Back.
 *
 * The overlay is plainly OpenGuard's Lost Mode screen ("This phone is
 * lost" + the owner's message + "Protected by OpenGuard"). It never
 * hides that OpenGuard is running.
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

    @Volatile
    private var isShowing = false
    private var viewRef: ComposeView? = null
    private var ownersRef: OverlayOwners? = null
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
            // Already up: refresh the message (a second lost_mode_on may
            // carry a new one).
            viewRef?.setContent { DecoyContent(appContext) }
            return
        }
        val owners = OverlayOwners()
        val view = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(owners)
            setViewTreeSavedStateRegistryOwner(owners)
            setContent { DecoyContent(appContext) }
            addOnAttachStateChangeListener(detachListener)
        }
        try {
            windowManager(appContext).addView(view, overlayParams())
        } catch (e: Exception) {
            // WindowManager rejected it (permission revoked between the
            // check and the add, or an OEM restriction). The notification
            // fallback still shows the message.
            return
        }
        owners.attach()
        viewRef = view
        ownersRef = owners
        appContextRef = appContext
        isShowing = true
    }

    // Runs on the main thread only.
    private fun hideNow(appContext: Context) {
        val owners = ownersRef
        val view = viewRef
        expectingRemoval = true
        try {
            view?.let { windowManager(appContext).removeView(it) }
        } catch (e: Exception) {
            // Already gone.
        }
        owners?.detach()
        viewRef = null
        ownersRef = null
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
     * If the overlay is detached unexpectedly (the system reclaimed it),
     * bring it back while Lost Mode is still on. Intentional removals
     * (hideNow) set expectingRemoval first, so they never re-show.
     */
    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            ownersRef?.detach()
            viewRef = null
            ownersRef = null
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

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}

/**
 * View-tree owners for the overlay's ComposeView. A ComposeView outside
 * an activity needs a LifecycleOwner and a SavedStateRegistryOwner, or
 * setContent throws. detach() is idempotent: both the intentional hide
 * path and the detach listener call it.
 */
private class OverlayOwners : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private var destroyed = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun attach() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun detach() {
        if (destroyed) return
        destroyed = true
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

@Composable
private fun DecoyContent(appContext: Context) {
    OpenGuardTheme {
        DecoyLockContent(
            message = LostModeStore.message(appContext),
            onOwnerExit = { DecoyLock.exitToLostModeScreen(appContext) },
        )
    }
}

/**
 * The fake lock screen: the owner's message, a PIN pad, and an error
 * that always appears. No entered code ever unlocks — the only exits
 * are the remote lost_mode_off command or the hidden owner-exit
 * (long-press the shield icon, then the device credential check).
 */
@Composable
private fun DecoyLockContent(
    message: String?,
    onOwnerExit: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    LaunchedEffect(wrong) {
        if (wrong) {
            delay(900)
            pin = ""
            wrong = false
        }
    }

    fun pressDigit(digit: String) {
        if (wrong || pin.length >= 4) return
        val next = pin + digit
        pin = next
        // Always wrong — the decoy never unlocks.
        if (next.length == 4) wrong = true
    }

    fun pressBackspace() {
        if (!wrong) pin = pin.dropLast(1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        // Hidden owner-exit: long-press the shield. No visible hint — a
        // stranger must not find it by looking.
        Box(
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onLongPress = { onOwnerExit() })
            },
        ) {
            TintedIcon(R.drawable.ic_shield, size = 64.dp, iconSize = 32.dp)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "This phone is lost",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            message?.takeIf { it.isNotBlank() }
                ?: "Please return this phone to its owner.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        if (wrong) {
            Text(
                "Wrong PIN — try again",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                "Enter PIN to unlock",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { index ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (index < pin.length) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "⌫"),
        )
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { key ->
                    when (key) {
                        "" -> Spacer(Modifier.size(72.dp))
                        "⌫" -> PinKey("⌫") { pressBackspace() }
                        else -> PinKey(key) { pressDigit(key) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Protected by OpenGuard",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PinKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}
