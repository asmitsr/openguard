package com.openguard.feature.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openguard.R
import com.openguard.core.control.RemoteControls
import com.openguard.core.evidence.DriveAuth
import com.openguard.core.evidence.EvidencePrefs
import com.openguard.core.location.LocationService
import com.openguard.core.location.PushTopicStore
import com.openguard.core.location.TrackingPermissions
import com.openguard.core.location.TrackingState
import com.openguard.core.location.WifiReconnect
import com.openguard.core.security.DecoyLock
import com.openguard.core.security.DeviceAdminManager
import com.openguard.core.security.PowerMenuGuard
import com.openguard.core.security.SecurityPrefs
import com.openguard.core.security.SimMonitor
import com.openguard.core.security.SmsSecretStore
import com.openguard.core.theft.AutoCaller
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardOutlinedButton
import com.openguard.ui.GuardTopBar
import com.openguard.ui.SectionHeader
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    userEmail: String?,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onRecoveryClick: () -> Unit,
    onDeviceSignedOut: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state.loggedOut, state.accountDeleted, state.deviceSignedOut) {
        if (state.loggedOut || state.accountDeleted) onLoggedOut()
        if (state.deviceSignedOut) onDeviceSignedOut()
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }
    var showRevokeDialog by remember { mutableStateOf(false) }
    var showSignOutDeviceDialog by remember { mutableStateOf(false) }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var wifiResult by remember { mutableStateOf<String?>(null) }

    // Bump on every resume so admin/SIM state changed in system Settings
    // is picked up when the user comes back.
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val adminActive = remember(tick) { DeviceAdminManager.isAdminActive(context) }
    val hasPhoneState = remember(tick) { TrackingPermissions.hasPhoneState(context) }
    val trustedSimCount = remember(tick) { SecurityPrefs.trustedSubscriptionIds(context).size }
    val overlayAllowed = remember(tick) { DecoyLock.hasPermission(context) }
    val guardServiceOn = remember(tick) { PowerMenuGuard.isServiceEnabled(context) }
    var guardEnabled by remember(tick) { mutableStateOf(PowerMenuGuard.isGuardEnabled(context)) }
    var trustedNumberText by remember {
        mutableStateOf(SecurityPrefs.trustedNumber(context) ?: "")
    }
    var numberSaved by remember { mutableStateOf<String?>(null) }
    var autoCallEnabled by remember(tick) {
        mutableStateOf(SecurityPrefs.isAutoCallEnabled(context))
    }
    var callAsked by remember { mutableStateOf(false) }
    val callPermissionGranted = remember(tick) { AutoCaller.hasCallPermission(context) }
    val callLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }
    var liveTracking by remember(tick) { mutableStateOf(SecurityPrefs.isLiveTrackingEnabled(context)) }
    var smsSecret by remember { mutableStateOf("") }
    var smsSecretResult by remember { mutableStateOf<String?>(null) }
    var pushRotateResult by remember { mutableStateOf<String?>(null) }
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { tick++ }
    val hasSms = remember(tick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
    }
    val hasSmsSecret = remember(tick) { SmsSecretStore.hasSecret(context) }

    // Evidence (Lost Mode only): recording interval + Drive connection.
    var evidenceInterval by remember(tick) {
        mutableStateOf(EvidencePrefs.intervalMinutes(context))
    }
    var driveConnected by remember(tick) { mutableStateOf(DriveAuth.isConnected(context)) }
    var driveResult by remember { mutableStateOf<String?>(null) }
    val driveConfigured = DriveAuth.isConfigured()
    val hasCamera = remember(tick) { RemoteControls.cameraGranted(context) }
    val hasMic = remember(tick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val evidencePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { tick++ }
    val scope = rememberCoroutineScope()
    val driveSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        scope.launch {
            if (DriveAuth.handleSignInResponse(context, result.data)) {
                driveConnected = true
                driveResult = "Google Drive connected."
            } else {
                driveResult = "Could not connect Google Drive."
            }
            tick++
        }
    }

    // Remote controls: grant status, refreshed on resume.
    val writeSettingsGranted = remember(tick) { RemoteControls.canWriteSettings(context) }
    val dndGranted = remember(tick) { RemoteControls.dndAccessGranted(context) }
    val btGranted = remember(tick) { RemoteControls.bluetoothConnectGranted(context) }
    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }

    // Trusted contact alerts: which recovery alerts go by SMS to the
    // trusted number (defaults: SIM change on, offline + low battery off).
    val hasTrustedNumber = remember(tick) { SecurityPrefs.trustedNumber(context) != null }
    var trustedAlertSim by remember(tick) {
        mutableStateOf(SecurityPrefs.isTrustedAlertSimChangeEnabled(context))
    }
    var trustedAlertOffline by remember(tick) {
        mutableStateOf(SecurityPrefs.isTrustedAlertOfflineEnabled(context))
    }
    var trustedAlertLowBattery by remember(tick) {
        mutableStateOf(SecurityPrefs.isTrustedAlertLowBatteryEnabled(context))
    }

    Scaffold(
        topBar = { GuardTopBar(title = "Settings", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(iconRes = R.drawable.ic_mail, title = "Account")
            Spacer(Modifier.height(8.dp))
            Text(userEmail ?: "Not signed in", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            GuardOutlinedButton(
                onClick = viewModel::logout,
                text = "Log out",
                iconRes = R.drawable.ic_log_out,
                enabled = !state.isBusy,
            )
            Spacer(Modifier.height(12.dp))
            GuardOutlinedButton(
                onClick = onRecoveryClick,
                text = "Recovery codes",
                iconRes = R.drawable.ic_shield_check,
                enabled = !state.isBusy,
            )
            Spacer(Modifier.height(12.dp))
            GuardOutlinedButton(
                onClick = { showRevokeDialog = true },
                text = "Sign out all other sessions",
                iconRes = R.drawable.ic_log_out,
                enabled = !state.isBusy,
            )
            if (viewModel.thisDeviceId != null) {
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = { showSignOutDeviceDialog = true },
                    text = "Sign out this phone",
                    iconRes = R.drawable.ic_log_out,
                    enabled = !state.isBusy,
                )
            }
            state.info?.let { info ->
                Spacer(Modifier.height(8.dp))
                Text(info, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_shield, title = "Privacy")
            Spacer(Modifier.height(8.dp))
            Text(
                "OpenGuard records this phone's location only while tracking is " +
                    "on, and only after you consent on the Add-phone screen. A " +
                    "notification stays visible the whole time tracking runs. " +
                    "Locations are stored in your own account; removing a device " +
                    "or deleting your account removes its data from the server.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_battery, title = "Battery")
            Spacer(Modifier.height(8.dp))
            Text(
                "If tracking stops in the background, Android's battery " +
                    "optimization may be stopping the app. Exempt OpenGuard from " +
                    "battery optimization to keep tracking reliable:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            GuardOutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                },
                text = "Open battery optimization settings",
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_shield_check, title = "Device admin")
            Spacer(Modifier.height(8.dp))
            Text(
                if (adminActive) {
                    "Device admin is active — Lock and Lost Mode can lock " +
                        "this phone, remote erase can wipe it, and the " +
                        "camera (and flashlight) is disabled while Lost " +
                        "Mode is on. OpenGuard never sets or changes your " +
                        "password."
                } else {
                    "Device admin is not enabled. It unlocks Lock, Lost " +
                        "Mode locking and remote erase. Without it, Lost " +
                        "Mode still covers the screen with the decoy lock, " +
                        "but the phone itself is not locked, the camera " +
                        "stays on, and erase is refused."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!adminActive) {
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = { activity?.let { DeviceAdminManager.requestAdmin(it) } },
                    text = "Enable device admin",
                    iconRes = R.drawable.ic_lock,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "To uninstall OpenGuard, use Uninstall in Settings below — " +
                    "Android blocks uninstall while device admin is active.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_trash_2, title = "Uninstall")
            Spacer(Modifier.height(8.dp))
            Text(
                "OpenGuard cannot be uninstalled while device admin is " +
                    "active — that is what stops a thief deleting the app " +
                    "from the home screen. This button removes device " +
                    "admin first, then opens the system uninstall prompt.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            GuardOutlinedButton(
                onClick = { DeviceAdminManager.uninstall(context) },
                text = "Uninstall OpenGuard",
                iconRes = R.drawable.ic_trash_2,
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_lock, title = "Lost Mode screen")
            Spacer(Modifier.height(8.dp))
            Text(
                if (overlayAllowed) {
                    "OpenGuard may display over other apps — during Lost " +
                        "Mode the decoy lock covers the whole screen and " +
                        "shows your message right away, no tap needed."
                } else {
                    "Allow OpenGuard to display over other apps so Lost " +
                        "Mode can cover the whole screen with the decoy " +
                        "lock and your message. Without it, Lost Mode " +
                        "only shows as a notification you must tap."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!overlayAllowed) {
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = { activity?.let { DecoyLock.requestPermission(it) } },
                    text = "Allow display over other apps",
                    iconRes = R.drawable.ic_shield,
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_camera, title = "Evidence (Lost Mode only)")
            Spacer(Modifier.height(8.dp))
            Text(
                "While Lost Mode is on, OpenGuard records a short " +
                    "12-second clip (video + audio) every few minutes " +
                    "and uploads it to your Google Drive. The camera " +
                    "and microphone indicators always show while " +
                    "recording — nothing is hidden. Without a Drive " +
                    "connection, clips stay on this phone. The phone " +
                    "keeps at most the newest 50 clips or 500 MB; " +
                    "oldest clips are deleted first.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Record a clip every 2 minutes (otherwise every 5)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = evidenceInterval == 2,
                    onCheckedChange = {
                        evidenceInterval = if (it) 2 else 5
                        EvidencePrefs.setIntervalMinutes(context, evidenceInterval)
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (hasCamera && hasMic) {
                    "Camera and Microphone are allowed — Lost Mode " +
                        "records video + audio clips."
                } else if (hasMic) {
                    "Microphone is allowed but Camera is not — clips " +
                        "are audio-only."
                } else {
                    "Allow Camera and Microphone so Lost Mode can " +
                        "record evidence. Without Camera, clips are " +
                        "audio-only. Without Microphone, no evidence " +
                        "is recorded."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!hasCamera || !hasMic) {
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = {
                        evidencePermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO,
                            ),
                        )
                    },
                    text = "Allow camera and microphone",
                    iconRes = R.drawable.ic_camera,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (!driveConfigured) {
                    "Google Drive is not set up yet — the OAuth " +
                        "client ID has not been added (see the setup " +
                        "guide)."
                } else if (driveConnected) {
                    "Google Drive is connected — clips upload to " +
                        "your \"OpenGuard Evidence\" folder."
                } else {
                    "Google Drive is not connected — clips stay on " +
                        "this phone until you connect it."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (driveConfigured && !driveConnected) {
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = {
                        DriveAuth.createSignInIntent(context)?.let {
                            driveSignInLauncher.launch(it)
                        } ?: run {
                            driveResult = "Could not start Google sign-in."
                        }
                    },
                    text = "Connect Google Drive",
                )
            }
            if (driveConfigured && driveConnected) {
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = {
                        scope.launch {
                            DriveAuth.disconnect(context)
                            driveConnected = false
                            driveResult = "Google Drive disconnected."
                            tick++
                        }
                    },
                    text = "Disconnect Google Drive",
                    iconRes = R.drawable.ic_log_out,
                )
            }
            driveResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(result, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_settings, title = "Remote controls")
            Spacer(Modifier.height(8.dp))
            Text(
                "The Remote Control Center commands need these " +
                    "grants. Each command names its missing grant " +
                    "until you allow it here. The Torch command also " +
                    "needs the Camera permission, granted in the " +
                    "Evidence section above.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (writeSettingsGranted) {
                    "Modify system settings: allowed — the " +
                        "brightness, screen timeout and auto-rotate " +
                        "commands work."
                } else {
                    "Modify system settings: not allowed — the " +
                        "brightness, screen timeout and auto-rotate " +
                        "commands need it. Android asks once, in " +
                        "system Settings."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!writeSettingsGranted) {
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = {
                        activity?.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:" + context.packageName),
                            ),
                        )
                    },
                    text = "Allow modifying system settings",
                    iconRes = R.drawable.ic_settings,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (dndGranted) {
                    "Do Not Disturb access: allowed — the DND " +
                        "command works."
                } else {
                    "Do Not Disturb access: not allowed — the DND " +
                        "command needs it. Android asks once, in " +
                        "system Settings."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!dndGranted) {
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = {
                        activity?.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                        )
                    },
                    text = "Allow Do Not Disturb access",
                    iconRes = R.drawable.ic_dnd,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (btGranted) {
                    "Bluetooth permission: allowed — OpenGuard can " +
                        "check Bluetooth state and show the enable " +
                        "prompt."
                } else {
                    "Bluetooth permission: not allowed — OpenGuard " +
                        "needs it to check Bluetooth state and show " +
                        "the enable prompt. Android 13+ never lets " +
                        "apps turn Bluetooth on silently."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!btGranted) {
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = { btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    text = "Allow Bluetooth permission",
                    iconRes = R.drawable.ic_bluetooth,
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_shield_check, title = "Power-off protection")
            Spacer(Modifier.height(8.dp))
            Text(
                "During Lost Mode, the power-off menu is blocked so a " +
                    "lost phone cannot be switched off from the power " +
                    "button. This needs OpenGuard turned on in " +
                    "Accessibility settings. OpenGuard only watches for " +
                    "the power menu window — it never reads screen content.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (guardServiceOn) {
                    if (guardEnabled) {
                        "Power-off protection is on."
                    } else {
                        "Protection is paused — turn it back on below."
                    }
                } else {
                    "Not enabled yet — turn on OpenGuard in Accessibility settings."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Block power menu during Lost Mode",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = guardEnabled,
                    onCheckedChange = {
                        guardEnabled = it
                        PowerMenuGuard.setGuardEnabled(context, it)
                    },
                    enabled = guardServiceOn,
                )
            }
            if (!guardServiceOn) {
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = { PowerMenuGuard.openAccessibilitySettings(context) },
                    text = "Open accessibility settings",
                    iconRes = R.drawable.ic_shield,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Limit: holding power for about 10 seconds forces a " +
                    "restart no app can block. Lost Mode comes back " +
                    "after the reboot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_smartphone, title = "Trusted SIM")
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    !hasPhoneState -> "Grant the phone permission first " +
                        "(Add this phone, step 4) so OpenGuard can see the SIM."
                    trustedSimCount > 0 -> "Trusted SIM is set " +
                        "($trustedSimCount SIM${if (trustedSimCount > 1) "s" else ""}). " +
                        "A SIM change to anything else is flagged as untrusted."
                    else -> "No trusted SIM set. Trust the SIM that is in " +
                        "this phone now, so a different SIM is flagged."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            GuardOutlinedButton(
                onClick = {
                    SimMonitor.trustCurrentSim(context)
                    tick++
                },
                text = "Trust current SIM",
                iconRes = R.drawable.ic_check,
                enabled = hasPhoneState,
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_bell, title = "Theft response")
            Spacer(Modifier.height(8.dp))
            Text(
                "While Lost Mode is on, OpenGuard can call a number you " +
                    "trust and, if the SIM is removed, keep the phone " +
                    "noisy and unusable. Nothing here runs while Lost " +
                    "Mode is off. The number stays on this phone only " +
                    "— it is never sent to the server — and it is also " +
                    "the number SMS location replies go to.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = trustedNumberText,
                onValueChange = { trustedNumberText = it; numberSaved = null },
                label = { Text("Trusted number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            if (trustedNumberText.isNotBlank() &&
                !AutoCaller.isPlausiblePhoneNumber(trustedNumberText)
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "That does not look like a phone number — check it, " +
                        "or leave it and save anyway.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            GuardOutlinedButton(
                onClick = {
                    SecurityPrefs.setTrustedNumber(
                        context,
                        trustedNumberText.trim().ifBlank { null },
                    )
                    numberSaved = if (trustedNumberText.isBlank()) {
                        "Trusted number cleared."
                    } else {
                        "Trusted number saved."
                    }
                    tick++
                },
                text = "Save trusted number",
                iconRes = R.drawable.ic_check,
            )
            numberSaved?.let { saved ->
                Spacer(Modifier.height(8.dp))
                Text(saved, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Auto-call: after the screen is woken 3 times during " +
                    "Lost Mode, OpenGuard calls the trusted number, " +
                    "then redials every minute up to 5 times. A thief " +
                    "can end a call — the redial loop is the defense. " +
                    "It needs the SIM, signal and credit; if the SIM " +
                    "is removed, chaos mode takes over instead.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Auto-call trusted number during Lost Mode",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = autoCallEnabled,
                    onCheckedChange = {
                        autoCallEnabled = it
                        SecurityPrefs.setAutoCallEnabled(context, it)
                    },
                    enabled = callPermissionGranted &&
                        SecurityPrefs.trustedNumber(context) != null,
                )
            }
            if (!callPermissionGranted) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Calling needs the phone permission. Grant it to " +
                        "turn auto-call on.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                if (callAsked && activity?.let {
                        !ActivityCompat.shouldShowRequestPermissionRationale(
                            it, Manifest.permission.CALL_PHONE,
                        )
                    } == true
                ) {
                    GuardOutlinedButton(
                        onClick = { TrackingPermissions.openAppSettings(context) },
                        text = "Open app settings",
                        iconRes = R.drawable.ic_settings,
                    )
                } else {
                    GuardOutlinedButton(
                        onClick = {
                            callAsked = true
                            callLauncher.launch(Manifest.permission.CALL_PHONE)
                        },
                        text = "Grant phone permission",
                        iconRes = R.drawable.ic_bell,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Chaos mode: if the SIM is removed during Lost Mode, " +
                    "the camera app, YouTube and other apps open one " +
                    "after another behind the decoy lock while the " +
                    "alarm loops at full volume. It stops only when " +
                    "Lost Mode is turned off. The camera app only " +
                    "opens — it never takes a picture by itself. " +
                    "Limit: the overlay sits below the status bar, " +
                    "and holding power about 10 seconds still " +
                    "force-restarts the phone; Lost Mode comes back " +
                    "after the reboot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_bell, title = "Trusted contact alerts")
            Spacer(Modifier.height(8.dp))
            Text(
                "Send recovery alerts by SMS to your trusted number. " +
                    "Each alert is sent at most once an hour. SMS is " +
                    "sent from this phone's own SIM — the cost is on " +
                    "your mobile plan, never on OpenGuard.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SIM change",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = trustedAlertSim,
                    onCheckedChange = {
                        trustedAlertSim = it
                        SecurityPrefs.setTrustedAlertSimChangeEnabled(context, it)
                    },
                    enabled = hasTrustedNumber,
                )
            }
            Text(
                "Text your trusted number when the SIM card changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Offline for over an hour",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = trustedAlertOffline,
                    onCheckedChange = {
                        trustedAlertOffline = it
                        SecurityPrefs.setTrustedAlertOfflineEnabled(context, it)
                    },
                    enabled = hasTrustedNumber,
                )
            }
            Text(
                "Text your trusted number when this phone comes back " +
                    "online after being offline for over an hour.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Low battery",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = trustedAlertLowBattery,
                    onCheckedChange = {
                        trustedAlertLowBattery = it
                        SecurityPrefs.setTrustedAlertLowBatteryEnabled(context, it)
                    },
                    enabled = hasTrustedNumber,
                )
            }
            Text(
                "Text your trusted number when the battery drops to " +
                    "15% or below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!hasTrustedNumber) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Set a trusted number above to turn these alerts on.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_radar, title = "WiFi reconnect")
            Spacer(Modifier.height(8.dp))
            Text(
                "Android does not let any app turn WiFi on or force a " +
                    "connection — OpenGuard cannot do that either. What it " +
                    "can do: suggest a network you enter here. Android asks " +
                    "you once for approval, then connects automatically " +
                    "whenever that network is in range, even after the " +
                    "phone was offline. Your password is handed to Android " +
                    "only and is never stored by OpenGuard.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!WifiReconnect.isSupported()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Needs Android 10 or newer.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = wifiSsid,
                    onValueChange = { wifiSsid = it },
                    label = { Text("WiFi network name") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = wifiPassword,
                    onValueChange = { wifiPassword = it },
                    label = { Text("Password (leave empty for open networks)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = {
                        wifiResult = WifiReconnect.describeAddStatus(
                            WifiReconnect.suggestNetwork(context, wifiSsid, wifiPassword),
                        )
                        tick++
                    },
                    text = "Suggest this network",
                    enabled = wifiSsid.isNotBlank(),
                )
                wifiResult?.let { result ->
                    Spacer(Modifier.height(8.dp))
                    Text(result, style = MaterialTheme.typography.bodyMedium)
                }
                val suggested = remember(tick) { WifiReconnect.suggestedSsids(context) }
                if (suggested.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Suggested networks: ${suggested.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    GuardOutlinedButton(
                        onClick = {
                            WifiReconnect.removeAllSuggestions(context)
                            wifiResult = "Suggestions removed."
                            tick++
                        },
                        text = "Remove suggestions",
                    )
                }
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = { WifiReconnect.openWifiPanel(context) },
                    text = "Open WiFi settings",
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_radar, title = "Live tracking")
            Spacer(Modifier.height(8.dp))
            Text(
                "Uploads this phone's location every 20 seconds instead of " +
                    "every 15 minutes, so the map and device list stay fresh. " +
                    "Uses more battery and mobile data — turn it off when you " +
                    "don't need it. Live tracking also turns on automatically " +
                    "while Lost Mode is on.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Live tracking",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = liveTracking,
                    onCheckedChange = {
                        liveTracking = it
                        SecurityPrefs.setLiveTrackingEnabled(context, it)
                        if (TrackingState.isTracking.value) {
                            LocationService.stop(context)
                            LocationService.start(context)
                        }
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_shield_check, title = "Instant push")
            Spacer(Modifier.height(8.dp))
            Text(
                "While tracking is on, OpenGuard keeps a direct connection " +
                    "to ntfy.sh so Ring, Lock and Lost Mode reach this phone " +
                    "in seconds instead of up to 15 minutes. The push carries " +
                    "only a random wake-up code — never your location or the " +
                    "command itself. If ntfy.sh is unreachable, commands still " +
                    "arrive on the next 15-minute check.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            GuardOutlinedButton(
                onClick = {
                    PushTopicStore.rotate(context)
                    pushRotateResult = "Push topic rotated. It takes effect when tracking restarts."
                    tick++
                },
                text = "Rotate push topic",
            )
            pushRotateResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(result, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_smartphone, title = "SMS commands")
            Spacer(Modifier.height(8.dp))
            Text(
                "Control this phone by text message when it has no internet. " +
                    "Set a secret code below, then text \"<code> RING\", " +
                    "\"<code> LOCATE\", \"<code> LOCK\" or \"<code> WIPE\" from any " +
                    "phone. Replies go to your trusted number. SMS cost falls " +
                    "on the app user via their own SIM. This needs the SMS " +
                    "permission, and only works in the sideloaded APK — not " +
                    "in a Play Store release.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (hasSmsSecret) "A secret code is set." else "No secret code set — SMS commands are off.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!hasSms) {
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = {
                        smsPermissionLauncher.launch(
                            arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS),
                        )
                    },
                    text = "Grant SMS permission",
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = smsSecret,
                onValueChange = { smsSecret = it },
                label = { Text("Secret code") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            GuardOutlinedButton(
                onClick = {
                    SmsSecretStore.setSecret(context, smsSecret.ifBlank { null })
                    smsSecretResult = if (smsSecret.isBlank()) {
                        "Secret code cleared — SMS commands are off."
                    } else {
                        "Secret code saved."
                    }
                    smsSecret = ""
                    tick++
                },
                text = "Save secret code",
            )
            smsSecretResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(result, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(
                iconRes = R.drawable.ic_triangle_alert,
                title = "Danger zone",
                tint = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
            Spacer(Modifier.height(8.dp))
            GuardOutlinedButton(
                onClick = { showDeleteDialog = true },
                text = "Delete my account",
                iconRes = R.drawable.ic_trash_2,
                destructive = true,
            )
            state.error?.let { error ->
                Spacer(Modifier.height(8.dp))
                ErrorText(error)
            }

            Spacer(Modifier.height(32.dp))
            Text(
                "OpenGuard 0.1.0 (MVP)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    painterResource(R.drawable.ic_triangle_alert),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Delete account?") },
            text = {
                Column {
                    Text("This permanently deletes your account, devices and locations.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = deletePassword,
                        onValueChange = { deletePassword = it },
                        label = { Text("Confirm with your password") },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_lock), contentDescription = null)
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = deletePassword.isNotEmpty() && !state.isBusy,
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteAccount(deletePassword)
                        deletePassword = ""
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showRevokeDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeDialog = false },
            title = { Text("Sign out all other sessions?") },
            text = {
                Text(
                    "Every other signed-in session will be signed out. " +
                        "This phone stays signed in.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRevokeDialog = false
                        viewModel.revokeOtherSessions()
                    },
                ) { Text("Sign out others") }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showSignOutDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDeviceDialog = false },
            title = { Text("Sign out this phone?") },
            text = {
                Text(
                    "This phone's device token is invalidated and tracking " +
                        "stops. The phone must be added again before it can " +
                        "be tracked or receive commands. Your account stays " +
                        "signed in.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDeviceDialog = false
                        viewModel.signOutThisDevice()
                    },
                ) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDeviceDialog = false }) { Text("Cancel") }
            },
        )
    }
}
