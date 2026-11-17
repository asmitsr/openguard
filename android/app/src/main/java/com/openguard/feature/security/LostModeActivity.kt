package com.openguard.feature.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.openguard.OpenGuardApp
import com.openguard.R
import com.openguard.core.location.UploadWorker
import com.openguard.core.security.AppLockPassword
import com.openguard.core.security.AppLockType
import com.openguard.core.security.DecoyLock
import com.openguard.core.security.DuressResponse
import com.openguard.core.security.IntruderSelfie
import com.openguard.core.security.LostMode
import com.openguard.core.security.LostModeStore
import com.openguard.core.security.PatternGridView
import com.openguard.core.security.SecurityEvents
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardOutlinedButton
import com.openguard.ui.OpenGuardTheme
import com.openguard.ui.TintedIcon
import kotlinx.coroutines.delay
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
    val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val hasScreenLock = remember { keyguard.isDeviceSecure }
    val appLockType = remember { AppLockPassword.getType(context) }
    var error by remember { mutableStateOf<String?>(null) }
    var turningOff by remember { mutableStateOf(false) }
    // No screen lock + app password set: the password entry IS the way
    // in, so show it directly instead of a dead-end button.
    var showAppPassword by remember { mutableStateOf(!hasScreenLock && appLockType != null) }

    // Shared by the credential check and the app password: tell the
    // server, then clear local Lost Mode either way.
    val exitLostMode: () -> Unit = {
        turningOff = true
        activity.lifecycleScope.launch {
            val app = activity.application as OpenGuardApp
            val deviceId = app.tokenStore.deviceId
            if (deviceId != null) {
                app.commandRepository.createCommand(deviceId, "lost_mode_off")
            }
            LostMode.turnOff(activity)
            onTurnedOff()
        }
    }

    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            exitLostMode()
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
            when {
                showAppPassword && appLockType != null -> {
                    AppLockEntry(
                        type = appLockType,
                        onSuccess = exitLostMode,
                        onBack = if (hasScreenLock) {
                            { showAppPassword = false; error = null }
                        } else {
                            null
                        },
                    )
                }
                !hasScreenLock && appLockType == null -> {
                    // Neither way in exists locally: name both options
                    // plus the remote one, instead of a dead end.
                    Text(
                        "No screen lock is set on this phone, and no app lock password is set. " +
                            "Set a screen lock in the phone Settings, or set an app lock password " +
                            "in OpenGuard Settings — or turn Lost Mode off from the web dashboard " +
                            "on another device.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    GuardButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                        text = "Open phone Settings",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    GuardButton(
                        onClick = {
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
                    if (appLockType != null) {
                        Spacer(Modifier.height(12.dp))
                        GuardOutlinedButton(
                            onClick = { showAppPassword = true; error = null },
                            text = "Use app lock password instead",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * App lock password entry on the Lost Mode screen: a masked PIN field
 * for PIN, a pattern grid for PATTERN, and a toggle between the two
 * for MIXED (either secret unlocks). Wrong attempts record a
 * failed_unlock_attempt event and count toward the shared lockout
 * (5 failures, then 30s doubling to a 5-minute cap); the lockout is
 * shared with the decoy pad, so attempts on one screen count on the
 * other. The password is never logged and never leaves the phone.
 */
@Composable
private fun AppLockEntry(
    type: AppLockType,
    onSuccess: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var showPattern by remember { mutableStateOf(type == AppLockType.PATTERN) }
    var error by remember { mutableStateOf<String?>(null) }
    var lockoutMs by remember { mutableStateOf(AppLockPassword.lockoutRemainingMs(context)) }
    // Tick the countdown once a second while locked out; restarts on
    // every lockoutMs change, so a fresh lockout starts ticking too.
    LaunchedEffect(lockoutMs) {
        if (lockoutMs > 0) {
            delay(1000)
            lockoutMs = AppLockPassword.lockoutRemainingMs(context)
        }
    }

    fun recordFailedAttempt() {
        scope.launch {
            try {
                val app = context.applicationContext as OpenGuardApp
                SecurityEvents.record(
                    app.database, "failed_unlock_attempt",
                    details = mapOf(
                        "source" to "lost_mode_activity",
                        "attempt" to AppLockPassword.consecutiveFailures(context),
                    ),
                    confirmed = true,
                )
                UploadWorker.enqueueOneTime(context)
            } catch (e: Exception) {
                // Best-effort: the event is lost if the DB write fails.
            }
        }
    }

    fun submitPin() {
        if (lockoutMs > 0) return
        if (AppLockPassword.verifyPin(context, pin)) {
            AppLockPassword.resetFailures(context)
            onSuccess()
        } else if (AppLockPassword.verifyDuressPin(context, pin)) {
            // Duress: the identical "Wrong PIN" to the coercer, while
            // the silent response fires. Not a failure (no lockout
            // count, no failed_unlock_attempt from this branch — the
            // response records its own source=duress event).
            pin = ""
            DuressResponse.trigger(context)
            error = "Wrong PIN — try again"
        } else {
            AppLockPassword.recordFailure(context)
            recordFailedAttempt()
            if (AppLockPassword.consecutiveFailures(context) ==
                AppLockPassword.MAX_ATTEMPTS
            ) {
                // 5th failure — the lockout just engaged: photograph
                // the intruder (only when the owner enabled it).
                IntruderSelfie.onAppLockLockout(context)
            }
            pin = ""
            error = "Wrong PIN — try again"
            lockoutMs = AppLockPassword.lockoutRemainingMs(context)
        }
    }

    fun submitPattern(cells: List<Int>) {
        if (lockoutMs > 0) return
        if (cells.size < AppLockPassword.MIN_PATTERN_CELLS ||
            !AppLockPassword.verifyPattern(context, cells)
        ) {
            AppLockPassword.recordFailure(context)
            recordFailedAttempt()
            if (AppLockPassword.consecutiveFailures(context) ==
                AppLockPassword.MAX_ATTEMPTS
            ) {
                // 5th failure — the lockout just engaged: photograph
                // the intruder (only when the owner enabled it).
                IntruderSelfie.onAppLockLockout(context)
            }
            error = "Wrong pattern — try again"
            lockoutMs = AppLockPassword.lockoutRemainingMs(context)
            return
        }
        AppLockPassword.resetFailures(context)
        onSuccess()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (lockoutMs > 0) {
            Text(
                "Try again in ${(lockoutMs + 999) / 1000}s",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))
        }
        if (type == AppLockType.MIXED) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GuardOutlinedButton(
                    onClick = { showPattern = false; error = null },
                    text = "PIN",
                )
                GuardOutlinedButton(
                    onClick = { showPattern = true; error = null },
                    text = "Pattern",
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        if (showPattern) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                AndroidView(
                    factory = { viewContext ->
                        PatternGridView(viewContext).apply {
                            onPatternComplete = { cells -> submitPattern(cells) }
                        }
                    },
                    update = { view -> view.isEnabled = lockoutMs <= 0 },
                    modifier = Modifier.size(280.dp),
                )
            }
        } else {
            OutlinedTextField(
                value = pin,
                onValueChange = {
                    pin = it.filter { c -> c in '0'..'9' }
                        .take(AppLockPassword.MAX_PIN_LENGTH)
                    error = null
                },
                label = { Text("App lock PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                enabled = lockoutMs <= 0,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            GuardButton(
                onClick = { submitPin() },
                text = "Unlock",
                modifier = Modifier.fillMaxWidth(),
                enabled = lockoutMs <= 0 && pin.length >= AppLockPassword.MIN_PIN_LENGTH,
            )
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            ErrorText(it)
        }
        if (onBack != null) {
            Spacer(Modifier.height(12.dp))
            GuardOutlinedButton(
                onClick = onBack,
                text = "Back",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
