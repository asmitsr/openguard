package com.openguard.feature.devices

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openguard.R
import com.openguard.core.location.TrackingPermissions
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardCard
import com.openguard.ui.GuardTopBar
import com.openguard.ui.SectionHeader
import com.openguard.ui.TintedIcon

/**
 * VISIBLE consent screen. Nothing here is subtle: the user reads exactly
 * what tracking does, grants each permission in its own step, and only
 * then can register the phone.
 *
 * Permission order follows the Android 11+ rule: foreground location
 * first, background location only afterwards in a separate step.
 * Requesting both at once makes the system ignore both.
 */
@Composable
fun ConsentScreen(
    viewModel: DeviceViewModel,
    onRegistered: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    // Bump on every resume so grants made in system Settings are picked up.
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationsGranted = remember(tick) { TrackingPermissions.hasNotifications(context) }
    val fineGranted = remember(tick) { TrackingPermissions.hasFineLocation(context) }
    val backgroundGranted = remember(tick) { TrackingPermissions.hasBackgroundLocation(context) }
    val phoneStateGranted = remember(tick) { TrackingPermissions.hasPhoneState(context) }
    val allGranted = notificationsGranted && fineGranted && backgroundGranted && phoneStateGranted

    var notificationsAsked by remember { mutableStateOf(false) }
    var fineAsked by remember { mutableStateOf(false) }
    var backgroundAsked by remember { mutableStateOf(false) }
    var phoneStateAsked by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }
    val fineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }
    val phoneStateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }

    val registerState by viewModel.registerState.collectAsState()
    LaunchedEffect(registerState.device) {
        if (registerState.device != null) onRegistered()
    }

    var deviceName by remember { mutableStateOf(android.os.Build.MODEL ?: "My phone") }

    Scaffold(
        topBar = { GuardTopBar(title = "Add this phone", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Before you start tracking", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(
                "OpenGuard will record this phone's location about once a minute, " +
                    "even when the app is closed, and send it to your own OpenGuard " +
                    "account. Nobody else can see it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "A notification saying “OpenGuard is tracking this device” stays " +
                    "visible whenever tracking is on. You can stop tracking anytime " +
                    "from that notification or from this app.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "If the SIM card changes, OpenGuard records a security alert " +
                    "so you can check whether it was you.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Only track a phone you own. Tracking someone else's phone " +
                    "without their consent is not what this app is for.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_shield_check, title = "Permissions")
            Spacer(Modifier.height(12.dp))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionStep(
                    iconRes = R.drawable.ic_bell,
                    title = "1. Notifications",
                    description = "So the tracking notification can stay visible.",
                    granted = notificationsGranted,
                    permanentlyDenied = notificationsAsked && !notificationsGranted &&
                        activity?.let {
                            !ActivityCompat.shouldShowRequestPermissionRationale(
                                it, Manifest.permission.POST_NOTIFICATIONS,
                            )
                        } == true,
                    onGrant = {
                        notificationsAsked = true
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onOpenSettings = { TrackingPermissions.openAppSettings(context) },
                )
            }
            PermissionStep(
                iconRes = R.drawable.ic_map_pin,
                title = "2. Location while using the app",
                description = "Precise location, so your position on the map is accurate.",
                granted = fineGranted,
                permanentlyDenied = fineAsked && !fineGranted &&
                    activity?.let {
                        !ActivityCompat.shouldShowRequestPermissionRationale(
                            it, Manifest.permission.ACCESS_FINE_LOCATION,
                        )
                    } == true,
                onGrant = {
                    fineAsked = true
                    fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                },
                onOpenSettings = { TrackingPermissions.openAppSettings(context) },
            )
            PermissionStep(
                iconRes = R.drawable.ic_radar,
                title = "3. Location all the time",
                description = "Needed to keep recording when the app is closed. " +
                    "Tap Grant, then choose “Allow all the time” in Settings.",
                granted = backgroundGranted,
                enabled = fineGranted,
                permanentlyDenied = backgroundAsked && !backgroundGranted &&
                    activity?.let {
                        !ActivityCompat.shouldShowRequestPermissionRationale(
                            it, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                        )
                    } == true,
                onGrant = {
                    backgroundAsked = true
                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                },
                onOpenSettings = { TrackingPermissions.openAppSettings(context) },
            )
            PermissionStep(
                iconRes = R.drawable.ic_smartphone,
                title = "4. Phone state",
                description = "Lets OpenGuard notice if the SIM card changes " +
                    "and alert you about it.",
                granted = phoneStateGranted,
                permanentlyDenied = phoneStateAsked && !phoneStateGranted &&
                    activity?.let {
                        !ActivityCompat.shouldShowRequestPermissionRationale(
                            it, Manifest.permission.READ_PHONE_STATE,
                        )
                    } == true,
                onGrant = {
                    phoneStateAsked = true
                    phoneStateLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                },
                onOpenSettings = { TrackingPermissions.openAppSettings(context) },
            )

            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("Name for this phone") },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_smartphone), contentDescription = null)
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            registerState.error?.let { error ->
                Spacer(Modifier.height(8.dp))
                ErrorText(error)
            }
            Spacer(Modifier.height(16.dp))
            GuardButton(
                onClick = { viewModel.registerThisDevice(deviceName) },
                text = "I understand — register & start tracking",
                enabled = allGranted && deviceName.isNotBlank() && !registerState.isLoading,
                loading = registerState.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!allGranted) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Grant all permissions above to continue.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PermissionStep(
    iconRes: Int,
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    enabled: Boolean = true,
    permanentlyDenied: Boolean = false,
) {
    GuardCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedIcon(iconRes)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                granted -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Granted", color = MaterialTheme.colorScheme.tertiary)
                }
                permanentlyDenied -> OutlinedButton(
                    onClick = onOpenSettings,
                    shape = MaterialTheme.shapes.small,
                ) { Text("Settings") }
                else -> Button(
                    onClick = onGrant,
                    enabled = enabled,
                    shape = MaterialTheme.shapes.small,
                ) { Text("Grant") }
            }
        }
    }
}
