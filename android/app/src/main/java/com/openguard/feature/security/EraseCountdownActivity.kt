package com.openguard.feature.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openguard.R
import com.openguard.core.security.DeviceAdminManager
import com.openguard.core.security.EraseCountdown
import com.openguard.core.security.EraseStore
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.OpenGuardTheme
import com.openguard.ui.TintedIcon
import kotlinx.coroutines.delay

/**
 * Full-screen erase countdown, shown over the lock screen. The remaining
 * time is recomputed from the stored deadline every tick, so a rotation
 * or process death can never stretch the grace period. Only the owner
 * can abort, after proving it with the device credential (PIN / pattern
 * / password / biometrics). When the countdown reaches zero the phone is
 * factory-reset.
 */
class EraseCountdownActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // API 27+ only (minSdk is 26); the manifest attributes cover older
        // devices declaratively.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        EraseStore.refresh(this)
        if (EraseStore.pending(this) == null) {
            finish()
            return
        }
        // Back press does nothing on this screen.
        onBackPressedDispatcher.addCallback(this) { }
        setContent {
            OpenGuardTheme {
                val pending by EraseStore.pendingFlow.collectAsState()
                var wipeFailed by remember { mutableStateOf(false) }
                // Aborted (pending cleared): go away. A failed wipe keeps
                // the screen up so the owner sees the phone was NOT erased.
                LaunchedEffect(pending, wipeFailed) {
                    if (pending == null && !wipeFailed) finish()
                }
                val current = pending
                if (current != null || wipeFailed) {
                    EraseCountdownContent(
                        deadlineMs = current?.deadlineMs ?: 0L,
                        wipeFailed = wipeFailed,
                        onWipeFailed = { wipeFailed = true },
                        onAborted = { finish() },
                        onClose = { finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun EraseCountdownContent(
    deadlineMs: Long,
    wipeFailed: Boolean,
    onWipeFailed: () -> Unit,
    onAborted: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as EraseCountdownActivity
    var error by remember { mutableStateOf<String?>(null) }
    var remainingMs by remember { mutableStateOf(deadlineMs - System.currentTimeMillis()) }
    val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)

    // Countdown recomputed from the stored deadline every tick. At zero
    // the phone is wiped; if device admin was revoked mid-countdown the
    // wipe fails, so the pending state is cleared (otherwise this screen
    // would relaunch on every app start) and the failure is shown.
    LaunchedEffect(deadlineMs) {
        if (wipeFailed) return@LaunchedEffect
        while (true) {
            val left = deadlineMs - System.currentTimeMillis()
            remainingMs = left
            if (left <= 0) break
            delay(250)
        }
        if (!DeviceAdminManager.wipeData(activity)) {
            EraseStore.clear(activity)
            onWipeFailed()
        }
    }

    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            EraseCountdown.abort(activity)
            onAborted()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            TintedIcon(
                R.drawable.ic_triangle_alert,
                size = 64.dp,
                iconSize = 32.dp,
                iconTint = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
            Spacer(Modifier.height(24.dp))
            if (wipeFailed) {
                Text(
                    "Erase failed",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Device admin was removed, so this phone was NOT erased.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
                GuardButton(
                    onClick = onClose,
                    text = "Close",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    "This phone will be erased",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "$seconds",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "seconds left to abort",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Everything on this phone will be permanently erased: " +
                        "all apps, photos, files and accounts, including the " +
                        "SD card. The phone will stay locked to your Google " +
                        "account (Factory Reset Protection).",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
                error?.let { ErrorText(it) }
                if (error != null) Spacer(Modifier.height(8.dp))
                GuardButton(
                    onClick = {
                        val keyguard =
                            activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                        val intent = keyguard.createConfirmDeviceCredentialIntent(
                            "Abort erase",
                            "Confirm it is you to stop the erase.",
                        )
                        if (intent == null) {
                            // No screen lock = no credential confirm = abort
                            // is unavailable from this screen. The 60-second
                            // delay plus this visible screen is still the
                            // safeguard, and an erase needs device admin plus
                            // a step-up password plus typing ERASE to start.
                            error = "No screen lock is set up on this phone. " +
                                "Set a screen lock first, then try again."
                        } else {
                            error = null
                            credentialLauncher.launch(intent)
                        }
                    },
                    text = "Abort erase",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
