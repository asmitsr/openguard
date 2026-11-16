package com.openguard.feature.settings

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openguard.R
import com.openguard.core.location.TrackingPermissions
import com.openguard.core.security.DeviceAdminManager
import com.openguard.core.security.SecurityPrefs
import com.openguard.core.security.SimMonitor
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardOutlinedButton
import com.openguard.ui.GuardTopBar
import com.openguard.ui.SectionHeader

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    userEmail: String?,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state.loggedOut, state.accountDeleted) {
        if (state.loggedOut || state.accountDeleted) onLoggedOut()
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }

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
                        "this phone. OpenGuard never sets or changes your password."
                } else {
                    "Device admin is not enabled. Lock and Lost Mode locking " +
                        "need it — without it, Lost Mode still shows on screen " +
                        "but the phone is not locked."
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
}
