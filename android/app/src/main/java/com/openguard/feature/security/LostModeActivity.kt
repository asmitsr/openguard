package com.openguard.feature.security

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
import androidx.lifecycle.lifecycleScope
import com.openguard.OpenGuardApp
import com.openguard.R
import com.openguard.core.security.DecoyLock
import com.openguard.core.security.LostMode
import com.openguard.core.security.LostModeStore
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.OpenGuardTheme
import com.openguard.ui.TintedIcon
import kotlinx.coroutines.launch

/**
 * Full-screen Lost Mode screen, shown over the lock screen. Displays the
 * owner's message; only the owner can turn Lost Mode off, after proving
 * it with the device credential (PIN / pattern / password / biometrics).
 *
 * Honest note: Home cannot be blocked without being a launcher — this is
 * a deterrent + contact channel, not a kiosk lock. The phone itself is
 * locked and location keeps uploading while Lost Mode is on.
 */
class LostModeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // API 27+ only (minSdk is 26); the manifest attributes cover older
        // devices declaratively.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        LostModeStore.refresh(this)
        if (!LostModeStore.isOn(this)) {
            finish()
            return
        }
        // Back press does nothing on this screen.
        onBackPressedDispatcher.addCallback(this) { }
        setContent {
            OpenGuardTheme {
                val isOn by LostModeStore.isOnFlow.collectAsState()
                // Turned off remotely (or from another path): go away.
                LaunchedEffect(isOn) {
                    if (!isOn) finish()
                }
                LostModeContent(
                    message = LostModeStore.message(this),
                    onTurnedOff = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        // Left without turning Lost Mode off (e.g. Home): the decoy
        // overlay takes over again. No-op when Lost Mode was turned off
        // (the store is already cleared) or without overlay permission.
        if (LostModeStore.isOn(this)) DecoyLock.show(this)
        super.onDestroy()
    }
}

@Composable
private fun LostModeContent(
    message: String?,
    onTurnedOff: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as LostModeActivity
    var error by remember { mutableStateOf<String?>(null) }
    var turningOff by remember { mutableStateOf(false) }

    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            turningOff = true
            activity.lifecycleScope.launch {
                // Tell the server too, so the dashboard state matches —
                // then clear local Lost Mode either way.
                val app = activity.application as OpenGuardApp
                val deviceId = app.tokenStore.deviceId
                if (deviceId != null) {
                    app.commandRepository.createCommand(deviceId, "lost_mode_off")
                }
                LostMode.turnOff(activity)
                onTurnedOff()
            }
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
            TintedIcon(R.drawable.ic_shield, size = 64.dp, iconSize = 32.dp)
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
            error?.let { ErrorText(it) }
            if (error != null) Spacer(Modifier.height(8.dp))
            GuardButton(
                onClick = {
                    val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    val intent = keyguard.createConfirmDeviceCredentialIntent(
                        "Turn off Lost Mode",
                        "Confirm it is you to turn off Lost Mode.",
                    )
                    if (intent == null) {
                        error = "No screen lock is set up on this phone. " +
                            "Set a screen lock first, then try again."
                    } else {
                        error = null
                        credentialLauncher.launch(intent)
                    }
                },
                text = "I found my phone — turn off Lost Mode",
                loading = turningOff,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
