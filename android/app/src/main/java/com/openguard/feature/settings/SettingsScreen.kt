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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.viewinterop.AndroidView
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
import com.openguard.core.security.AppLockPassword
import com.openguard.core.security.AppLockType
import com.openguard.core.security.DecoyLock
import com.openguard.core.security.DeviceAdminManager
import com.openguard.core.security.FakeShutdownLock
import com.openguard.core.security.IntruderSelfie
import com.openguard.core.security.PatternGridView
import com.openguard.core.security.PowerMenuGuard
import com.openguard.core.security.SecurityPrefs
import com.openguard.core.security.SimMonitor
import com.openguard.core.security.SmsSecretStore
import com.openguard.core.security.TrustedAlerts
import com.openguard.core.theft.AutoCaller
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardOutlinedButton
import com.openguard.ui.GuardTopBar
import com.openguard.ui.SectionHeader
import java.security.SecureRandom
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
    var fakeShutdownEnabled by remember(tick) {
        mutableStateOf(FakeShutdownLock.isEnabled(context))
    }
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

    // App lock password: the stored type refreshes on every tick; the
    // set/change flow state below is local to the section. Step 0 is the
    // first pattern draw, step 1 the confirm draw, step 2 the PIN stage
    // of the mixed flow. The grid nonce recreates the pattern view, which
    // clears a drawn pattern after a short or mismatched attempt.
    val appLockType = remember(tick) { AppLockPassword.getType(context) }
    var appLockFlow by remember { mutableStateOf<AppLockType?>(null) }
    var appLockStep by remember { mutableIntStateOf(0) }
    var appLockGridNonce by remember { mutableIntStateOf(0) }
    var appLockPin by remember { mutableStateOf("") }
    var appLockPinConfirm by remember { mutableStateOf("") }
    var appLockPinGenerated by remember { mutableStateOf<String?>(null) }
    var appLockFirstPattern by remember { mutableStateOf<List<Int>?>(null) }
    var appLockPattern by remember { mutableStateOf<List<Int>?>(null) }
    var appLockError by remember { mutableStateOf<String?>(null) }
    var appLockResult by remember { mutableStateOf<String?>(null) }
    var appLockRemoveArmed by remember { mutableStateOf(false) }
    val resetAppLockFlow = {
        appLockFlow = null
        appLockStep = 0
        appLockPin = ""
        appLockPinConfirm = ""
        appLockPinGenerated = null
        appLockFirstPattern = null
        appLockPattern = null
        appLockError = null
    }

    // Duress PIN: a second PIN that silently triggers Lost Mode + an
    // SMS alert when entered under coercion. Requires a PIN or Mixed
    // app lock password, same length as the app PIN, different from it.
    val duressSet = remember(tick) { AppLockPassword.isDuressSet(context) }
    var duressPin by remember { mutableStateOf("") }
    var duressPinConfirm by remember { mutableStateOf("") }
    var duressError by remember { mutableStateOf<String?>(null) }
    var duressResult by remember { mutableStateOf<String?>(null) }
    var duressRemoveArmed by remember { mutableStateOf(false) }
    var duressSmsEnabled by remember(tick) {
        mutableStateOf(TrustedAlerts.isDuressAlertEnabled(context))
    }

    // Intruder Selfie: one front-camera photo on the 2nd failed
    // unlock within 5 minutes, or when the 5th failed app lock
    // attempt triggers the lockout. Default off.
    var intruderSelfieEnabled by remember(tick) {
        mutableStateOf(IntruderSelfie.isEnabled(context))
    }

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
            SectionHeader(iconRes = R.drawable.ic_lock, title = "App lock password")
            Spacer(Modifier.height(8.dp))
            Text(
                "Your phone's own screen lock stays the first layer. " +
                    "This password opens the Lost Mode screen when the " +
                    "phone is lost — enter it on the Lost Mode screen's " +
                    "PIN pad, or draw your pattern there. Keep it secret, " +
                    "because anyone who knows it can open the phone. It " +
                    "is stored only on this phone, never sent to the server.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (appLockType) {
                    AppLockType.PATTERN -> "App lock password is set (Pattern)."
                    AppLockType.PIN -> "App lock password is set (PIN)."
                    AppLockType.MIXED -> "App lock password is set (Pattern + PIN)."
                    null -> "No app lock password set — the Lost Mode screen never unlocks from the phone."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppLockType.entries.forEach { type ->
                    val label = when (type) {
                        AppLockType.PATTERN -> "Pattern"
                        AppLockType.PIN -> "PIN"
                        AppLockType.MIXED -> "Pattern + PIN"
                    }
                    val selected = (appLockFlow ?: appLockType) == type
                    val onSelect = {
                        resetAppLockFlow()
                        appLockFlow = type
                        appLockResult = null
                        appLockRemoveArmed = false
                    }
                    if (selected) {
                        GuardButton(
                            onClick = onSelect,
                            text = label,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        GuardOutlinedButton(
                            onClick = onSelect,
                            text = label,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            appLockFlow?.let { flow ->
                Spacer(Modifier.height(12.dp))
                val showPinStage = flow == AppLockType.PIN ||
                    (flow == AppLockType.MIXED && appLockStep >= 2)
                if (showPinStage) {
                    if (flow == AppLockType.MIXED) {
                        Text(
                            "Pattern confirmed — now set a PIN. Both are " +
                                "saved together.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = appLockPin,
                        onValueChange = {
                            appLockPin = it.filter { c -> c in '0'..'9' }
                                .take(AppLockPassword.MAX_PIN_LENGTH)
                            appLockPinGenerated = null
                            appLockError = null
                        },
                        label = { Text("PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                        ),
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = appLockPinConfirm,
                        onValueChange = {
                            appLockPinConfirm = it.filter { c -> c in '0'..'9' }
                                .take(AppLockPassword.MAX_PIN_LENGTH)
                            appLockPinGenerated = null
                            appLockError = null
                        },
                        label = { Text("PIN again") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                        ),
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    GuardOutlinedButton(
                        onClick = {
                            val generated =
                                (100000 + SecureRandom().nextInt(900000)).toString()
                            appLockPin = generated
                            appLockPinConfirm = generated
                            appLockPinGenerated = generated
                            appLockError = null
                        },
                        text = "Generate random PIN",
                    )
                    appLockPinGenerated?.let { generated ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Your PIN is $generated — write this PIN down, " +
                                "it is shown only here.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    GuardOutlinedButton(
                        onClick = {
                            when {
                                appLockPin.length < AppLockPassword.MIN_PIN_LENGTH ->
                                    appLockError = "PIN must be 4-8 digits."
                                appLockPin != appLockPinConfirm ->
                                    appLockError = "PINs do not match."
                                flow == AppLockType.PIN -> {
                                    if (AppLockPassword.setPin(context, appLockPin)) {
                                        appLockResult = "App lock password saved."
                                        resetAppLockFlow()
                                        tick++
                                    } else {
                                        appLockError = "Could not save the PIN."
                                    }
                                }
                                else -> {
                                    val pattern = appLockPattern
                                    if (pattern == null) {
                                        appLockError = "Set a pattern first."
                                    } else {
                                        val saved = AppLockPassword.setMixed(
                                            context, pattern, appLockPin,
                                        )
                                        if (saved) {
                                            appLockResult = "App lock password saved."
                                            resetAppLockFlow()
                                            tick++
                                        } else {
                                            appLockError = "Could not save."
                                        }
                                    }
                                }
                            }
                        },
                        text = if (flow == AppLockType.MIXED) {
                            "Save pattern + PIN"
                        } else {
                            "Save PIN"
                        },
                    )
                } else {
                    Text(
                        if (appLockStep == 0) {
                            "Draw your pattern"
                        } else {
                            "Draw it again to confirm"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        key(appLockFlow, appLockStep, appLockGridNonce) {
                            AndroidView(
                                factory = { viewContext ->
                                    PatternGridView(viewContext).apply {
                                        onPatternComplete = { cells ->
                                            if (cells.size < AppLockPassword.MIN_PATTERN_CELLS) {
                                                appLockError = "Use at least 4 dots."
                                                appLockGridNonce++
                                            } else if (appLockStep == 0) {
                                                appLockFirstPattern = cells
                                                appLockStep = 1
                                                appLockError = null
                                            } else if (cells == appLockFirstPattern) {
                                                if (flow == AppLockType.PATTERN) {
                                                    val saved = AppLockPassword.setPattern(
                                                        context, cells,
                                                    )
                                                    if (saved) {
                                                        appLockResult = "App lock password saved."
                                                        resetAppLockFlow()
                                                        tick++
                                                    } else {
                                                        appLockError = "Could not save the pattern."
                                                    }
                                                } else {
                                                    appLockPattern = cells
                                                    appLockStep = 2
                                                    appLockError = null
                                                }
                                            } else {
                                                appLockError = "Patterns do not match — try again."
                                                appLockFirstPattern = null
                                                appLockStep = 0
                                                appLockGridNonce++
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.size(280.dp),
                            )
                        }
                    }
                }
                appLockError?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    ErrorText(error)
                }
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = { resetAppLockFlow() },
                    text = "Cancel",
                )
            }
            if (appLockType != null) {
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = {
                        if (appLockRemoveArmed) {
                            AppLockPassword.remove(context)
                            appLockResult = "App lock password removed."
                            appLockRemoveArmed = false
                            resetAppLockFlow()
                            tick++
                        } else {
                            appLockRemoveArmed = true
                        }
                    },
                    text = if (appLockRemoveArmed) {
                        "Tap again to remove"
                    } else {
                        "Remove app lock password"
                    },
                    destructive = true,
                )
            }
            appLockResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(result, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_lock, title = "Duress PIN")
            Spacer(Modifier.height(8.dp))
            Text(
                "A second PIN for when someone forces you to unlock " +
                    "the phone. Enter it on the Lost Mode screen's PIN " +
                    "pad and it shows the ordinary \"Wrong PIN\" — " +
                    "while silently turning Lost Mode on, recording " +
                    "evidence, and texting your trusted number. It " +
                    "never unlocks the phone. To cancel, enter your " +
                    "real app lock password within 60 seconds. It " +
                    "only works on OpenGuard's own screens — the " +
                    "system keyguard cannot trigger it. Changing your " +
                    "app lock password removes the duress PIN.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (appLockType == null || appLockType == AppLockType.PATTERN) {
                Text(
                    "Set a PIN or Pattern + PIN app lock password " +
                        "first — the duress PIN is entered on the PIN " +
                        "pad, so it needs a PIN to match.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    if (duressSet) {
                        "Duress PIN is set."
                    } else {
                        "No duress PIN set."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = duressPin,
                    onValueChange = {
                        duressPin = it.filter { c -> c in '0'..'9' }
                            .take(AppLockPassword.MAX_PIN_LENGTH)
                        duressError = null
                    },
                    label = { Text("Duress PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                    ),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = duressPinConfirm,
                    onValueChange = {
                        duressPinConfirm = it.filter { c -> c in '0'..'9' }
                            .take(AppLockPassword.MAX_PIN_LENGTH)
                        duressError = null
                    },
                    label = { Text("Duress PIN again") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                    ),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                GuardButton(
                    onClick = {
                        val appPinLength = AppLockPassword.pinLength(context)
                        duressError = when {
                            duressPin.length < AppLockPassword.MIN_PIN_LENGTH ->
                                "Enter at least 4 digits."
                            duressPin != duressPinConfirm ->
                                "PINs do not match."
                            duressPin.length != appPinLength ->
                                "Must be $appPinLength digits — the same " +
                                    "length as your app lock PIN."
                            AppLockPassword.verifyPin(context, duressPin) ->
                                "Must be different from your app lock PIN."
                            !AppLockPassword.setDuressPin(context, duressPin) ->
                                "Could not save the duress PIN."
                            else -> null
                        }
                        if (duressError == null) {
                            duressResult = "Duress PIN saved."
                            duressPin = ""
                            duressPinConfirm = ""
                            duressRemoveArmed = false
                            tick++
                        }
                    },
                    text = if (duressSet) "Change duress PIN" else "Save duress PIN",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (duressSet) {
                    Spacer(Modifier.height(12.dp))
                    GuardOutlinedButton(
                        onClick = {
                            if (duressRemoveArmed) {
                                AppLockPassword.removeDuressPin(context)
                                duressResult = "Duress PIN removed."
                                duressRemoveArmed = false
                                duressPin = ""
                                duressPinConfirm = ""
                                tick++
                            } else {
                                duressRemoveArmed = true
                            }
                        },
                        text = if (duressRemoveArmed) {
                            "Tap again to remove"
                        } else {
                            "Remove duress PIN"
                        },
                        destructive = true,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Text my trusted number when the duress PIN is used",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = duressSmsEnabled,
                        onCheckedChange = {
                            duressSmsEnabled = it
                            TrustedAlerts.setDuressAlertEnabled(context, it)
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Needs a trusted number (set it in Theft response " +
                        "below). At most one text per hour.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            duressError?.let { error ->
                Spacer(Modifier.height(8.dp))
                ErrorText(error)
            }
            duressResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(result, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_camera, title = "Intruder Selfie")
            Spacer(Modifier.height(8.dp))
            Text(
                "When someone fails to unlock this phone, take one " +
                    "front-camera photo of them as evidence for you, " +
                    "the owner. It fires on the 2nd failed unlock " +
                    "within 5 minutes (a single typo never takes a " +
                    "photo), or when 5 wrong app lock passwords " +
                    "trigger the lockout. At most one photo per hour. " +
                    "Photos go to your Google Drive like evidence " +
                    "clips — or stay on this phone if Drive is not " +
                    "connected. The green camera dot always shows " +
                    "during the capture; nothing is hidden.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Take an intruder photo",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = intruderSelfieEnabled,
                    onCheckedChange = {
                        intruderSelfieEnabled = it
                        IntruderSelfie.setEnabled(context, it)
                    },
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
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Fake shutdown screen instead of the decoy",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = fakeShutdownEnabled,
                    onCheckedChange = {
                        fakeShutdownEnabled = it
                        FakeShutdownLock.setEnabled(context, it)
                    },
                    enabled = guardServiceOn,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "When Lost Mode is on and someone long-presses power, " +
                    "the phone shows a black screen and looks switched " +
                    "off — while it keeps tracking. To get back: " +
                    "long-press the top-left corner of the black " +
                    "screen, then unlock from the decoy as normal. " +
                    "Only in Lost Mode; a reboot clears the fake screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
