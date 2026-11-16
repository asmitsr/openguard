@file:OptIn(ExperimentalLayoutApi::class)

package com.openguard.feature.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openguard.R
import com.openguard.core.common.Result
import com.openguard.core.common.SecurityConfig
import com.openguard.core.common.relativeTime
import com.openguard.core.location.UploadWorker
import com.openguard.core.network.CommandDto
import com.openguard.core.network.CommandVerifyResponse
import com.openguard.core.security.DecoyLock
import com.openguard.core.security.DeviceAdminManager
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardCard
import com.openguard.ui.GuardOutlinedButton
import com.openguard.ui.GuardTopBar
import com.openguard.ui.SectionHeader
import com.openguard.ui.TintedIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Control Center for one device: device card, Remote Actions grid,
// Remote Controls, Device Information, Erase, command history.
// Navigation callbacks default to no-ops so the screen compiles
// before the health/timeline destinations land; the coordinator
// wires them in MainActivity:
//   onLocateClick  -> "map/{deviceId}/{deviceName}"
//   onAlertsClick  -> "timeline/{deviceId}/{deviceName}"
//   onHealthClick  -> "health/{deviceId}/{deviceName}"
//   onHistoryClick -> "history/{deviceId}/{deviceName}"
@Composable
fun CommandCenterScreen(
    deviceId: String,
    deviceName: String,
    viewModel: CommandCenterViewModel,
    onBack: () -> Unit,
    onLocateClick: () -> Unit = {},
    onAlertsClick: () -> Unit = {},
    onHealthClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()
    var lostMessage by remember { mutableStateOf("") }
    var showLostPanel by remember { mutableStateOf(false) }
    var showErasePanel by remember { mutableStateOf(false) }
    var eraseConfirm by remember { mutableStateOf("") }
    var erasePassword by remember { mutableStateOf("") }
    var verifyTarget by remember { mutableStateOf<CommandDto?>(null) }
    var verifyResult by remember { mutableStateOf<Result<CommandVerifyResponse>?>(null) }
    var verifyLoading by remember { mutableStateOf(false) }
    val isThisDevice = deviceId == viewModel.thisDeviceId
    // Admin state is only knowable for this phone; other devices are
    // unknown, so their Lock button stays enabled with a hint instead.
    val adminActive = remember { DeviceAdminManager.isAdminActive(context) }
    val lockDisabled = isThisDevice && !adminActive
    val overlayAllowed = remember { DecoyLock.hasPermission(context) }
    // The latest lost_mode_on ack says plainly whether the phone itself
    // got locked — surface it here, not buried in the history list.
    val lockSkipped = state.commands
        .filter { it.action == "lost_mode_on" }
        .maxByOrNull { it.issuedAt ?: "" }
        ?.result?.contains("lock skipped") == true
    val busy = state.sending != null

    // Load, then keep the card + history fresh while the screen is open.
    LaunchedEffect(deviceId) {
        viewModel.load(deviceId)
        while (true) {
            delay(25_000L)
            viewModel.poll(deviceId)
        }
    }

    fun send(action: String, payload: Map<String, String>? = null) {
        viewModel.send(deviceId, action, payload)
        // Commands to this phone: wake the worker now instead of waiting
        // up to 15 minutes for the periodic run.
        if (isThisDevice) UploadWorker.enqueueOneTime(context)
    }

    Scaffold(
        topBar = { GuardTopBar(title = deviceName, onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // ---- Device card: online state, battery, last sync ----
            val device = state.device
            GuardCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TintedIcon(R.drawable.ic_smartphone)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(deviceName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (state.isOnline) "Online" else "Offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.isOnline) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        if (device?.batteryLevel != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TintedIcon(
                                    R.drawable.ic_battery,
                                    size = 32.dp,
                                    iconSize = 16.dp,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${device.batteryLevel}%" +
                                        if (device.batteryCharging == true) " · Charging" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Last sync ${relativeTime(device?.lastSeenAt)}" +
                            if (!state.isOnline) " — commands queue for up to 24 hours" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (device?.model != null || device?.osVersion != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOfNotNull(device.model, device.osVersion).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Commands reach the phone within about 15 minutes " +
                    "(faster for this phone).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Remote Actions grid ----
            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_shield, title = "Remote actions")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(
                    iconRes = R.drawable.ic_map_pin,
                    label = "Locate",
                    onClick = onLocateClick,
                )
                ActionTile(
                    iconRes = R.drawable.ic_bell,
                    label = "Ring",
                    onClick = { send("ring") },
                    loading = state.sending == "ring",
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(
                    iconRes = R.drawable.ic_lock,
                    label = "Lock",
                    onClick = { send("lock") },
                    enabled = !lockDisabled,
                    loading = state.sending == "lock",
                )
                ActionTile(
                    iconRes = R.drawable.ic_shield,
                    label = "Lost Mode",
                    badge = if (state.lostMode) "ON" else null,
                    onClick = {
                        if (state.lostMode) {
                            send("lost_mode_off")
                        } else {
                            showLostPanel = true
                        }
                    },
                    loading = state.sending == "lost_mode_on" ||
                        state.sending == "lost_mode_off",
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(
                    iconRes = R.drawable.ic_triangle_alert,
                    label = "Alerts",
                    onClick = onAlertsClick,
                )
                ActionTile(
                    iconRes = R.drawable.ic_heart,
                    label = "Health",
                    onClick = onHealthClick,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(
                    iconRes = R.drawable.ic_history,
                    label = "History",
                    onClick = onHistoryClick,
                )
                ActionTile(
                    iconRes = R.drawable.ic_lock,
                    label = "Vault",
                    badge = "Coming soon",
                    enabled = false,
                    onClick = {},
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(
                    iconRes = R.drawable.ic_refresh,
                    label = "Restart *",
                    enabled = false,
                    onClick = {},
                )
                ActionTile(
                    iconRes = R.drawable.ic_flashlight,
                    label = "Torch on",
                    onClick = { send("torch_on") },
                    loading = state.sending == "torch_on",
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(
                    iconRes = R.drawable.ic_flashlight,
                    label = "Torch off",
                    onClick = { send("torch_off") },
                    loading = state.sending == "torch_off",
                )
                ActionTile(
                    iconRes = R.drawable.ic_power,
                    label = "Wake screen",
                    onClick = { send("wake_screen") },
                    loading = state.sending == "wake_screen",
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(
                    iconRes = R.drawable.ic_camera,
                    label = "Capture evidence",
                    onClick = { send("evidence_capture") },
                    loading = state.sending == "evidence_capture",
                )
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "* Restart needs a device-owner app. OpenGuard is not a " +
                    "device owner, so restart is unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lockDisabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Enable device admin in Settings to use Lock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!isThisDevice) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lock needs device admin enabled on that phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Lost Mode panel ----
            if (state.lostMode) {
                Spacer(Modifier.height(24.dp))
                SectionHeader(iconRes = R.drawable.ic_shield, title = "Lost Mode is ON")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lost Mode is ON for this phone." +
                        (state.lostMessage?.let { " Message: “$it”" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (lockSkipped) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The phone itself is NOT locked — device admin " +
                            "is not enabled on it. Enable device admin " +
                            "in that phone's Settings so Lost Mode can " +
                            "lock it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = { send("lost_mode_off") },
                    text = "Turn off Lost Mode",
                    iconRes = R.drawable.ic_shield_check,
                    enabled = state.sending != "lost_mode_off",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (showLostPanel) {
                Spacer(Modifier.height(24.dp))
                SectionHeader(iconRes = R.drawable.ic_shield, title = "Turn on Lost Mode")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lostMessage,
                    onValueChange = {
                        if (it.length <= SecurityConfig.LOST_MESSAGE_MAX_LENGTH) lostMessage = it
                    },
                    label = { Text("Message for whoever finds it (optional)") },
                    singleLine = false,
                    maxLines = 3,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                GuardButton(
                    onClick = {
                        send(
                            "lost_mode_on",
                            lostMessage.takeIf { it.isNotBlank() }
                                ?.let { mapOf("message" to it) },
                        )
                        showLostPanel = false
                    },
                    text = "Turn on Lost Mode",
                    iconRes = R.drawable.ic_shield,
                    loading = state.sending == "lost_mode_on",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Locks the phone, disables the camera (and the " +
                        "flashlight), and shows your message on its screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isThisDevice && !adminActive) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Device admin is not enabled on this phone, so " +
                            "Lost Mode will show on screen but the phone " +
                            "itself will not lock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (isThisDevice && !overlayAllowed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tip: allow “Display over other apps” in " +
                            "Settings so the Lost Mode screen covers the " +
                            "whole phone right away.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- Remote controls ----
            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_settings, title = "Remote controls")
            Spacer(Modifier.height(8.dp))
            Text(
                "Brightness, screen timeout and auto-rotate need “Modify " +
                    "system settings” on that phone; Do Not Disturb needs " +
                    "DND access; the torch needs camera permission. Each " +
                    "command reports the exact missing grant.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            VolumeControl(busy = busy, onSend = ::send)
            Spacer(Modifier.height(16.dp))
            BrightnessControl(busy = busy, onSend = ::send)
            Spacer(Modifier.height(16.dp))
            ControlLabel("Ringer mode")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlOption("Normal", busy) { send("set_ringer_mode", mapOf("mode" to "normal")) }
                ControlOption("Vibrate", busy) { send("set_ringer_mode", mapOf("mode" to "vibrate")) }
                ControlOption("Silent", busy) { send("set_ringer_mode", mapOf("mode" to "silent")) }
            }
            Spacer(Modifier.height(16.dp))
            ControlLabel("Do Not Disturb")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlOption("Off", busy) { send("set_dnd", mapOf("mode" to "off")) }
                ControlOption("Alarms only", busy) { send("set_dnd", mapOf("mode" to "alarms")) }
                ControlOption("Priority only", busy) { send("set_dnd", mapOf("mode" to "priority")) }
                ControlOption("Total silence", busy) { send("set_dnd", mapOf("mode" to "none")) }
            }
            Spacer(Modifier.height(16.dp))
            ControlLabel("Screen timeout")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlOption("30 sec", busy) { send("set_screen_timeout", mapOf("seconds" to "30")) }
                ControlOption("1 min", busy) { send("set_screen_timeout", mapOf("seconds" to "60")) }
                ControlOption("2 min", busy) { send("set_screen_timeout", mapOf("seconds" to "120")) }
                ControlOption("5 min", busy) { send("set_screen_timeout", mapOf("seconds" to "300")) }
                ControlOption("10 min", busy) { send("set_screen_timeout", mapOf("seconds" to "600")) }
                ControlOption("30 min", busy) { send("set_screen_timeout", mapOf("seconds" to "1800")) }
            }
            Spacer(Modifier.height(16.dp))
            ControlLabel("Auto-rotate")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlOption("On", busy) { send("set_auto_rotate", mapOf("enabled" to "true")) }
                ControlOption("Off", busy) { send("set_auto_rotate", mapOf("enabled" to "false")) }
            }
            Spacer(Modifier.height(16.dp))
            TtsControl(busy = busy, onSend = ::send)
            Spacer(Modifier.height(16.dp))
            WallpaperControl(busy = busy, onSend = ::send)
            Spacer(Modifier.height(16.dp))
            ControlLabel("Bluetooth")
            Spacer(Modifier.height(8.dp))
            GuardOutlinedButton(
                onClick = { send("bluetooth_on") },
                text = "Turn on Bluetooth",
                iconRes = R.drawable.ic_bluetooth,
                enabled = !busy,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Best-effort: Android 13+ does not let apps turn " +
                    "Bluetooth on silently. The phone shows a prompt " +
                    "the person holding it must accept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Device information ----
            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_smartphone, title = "Device information")
            Spacer(Modifier.height(12.dp))
            InfoLine("Model", device?.model ?: "—")
            InfoLine("Android version", device?.osVersion ?: "—")
            InfoLine(
                "Battery",
                device?.batteryLevel?.let {
                    "$it%" + if (device.batteryCharging == true) " · Charging" else ""
                } ?: "—",
            )
            InfoLine("Network", device?.networkType ?: "—")
            InfoLine("Location permission", device?.locationPermission ?: "—")
            InfoLine(
                "Location enabled",
                device?.locationEnabled?.let { if (it) "Yes" else "No" } ?: "—",
            )
            InfoLine("Last seen", relativeTime(device?.lastSeenAt))

            // ---- Erase ----
            Spacer(Modifier.height(24.dp))
            SectionHeader(
                iconRes = R.drawable.ic_triangle_alert,
                title = "Erase this phone",
                tint = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Permanently erases everything on the phone: all apps, " +
                    "photos, files and accounts, including the SD card. " +
                    "The phone stays locked to your Google account. The " +
                    "phone gets a 60-second countdown to abort, and erase " +
                    "needs device admin enabled on it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (!showErasePanel) {
                GuardOutlinedButton(
                    onClick = { showErasePanel = true },
                    text = "Erase this phone",
                    iconRes = R.drawable.ic_triangle_alert,
                    destructive = true,
                    enabled = state.isOnline && !lockDisabled && state.sending != "erase",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!state.isOnline) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Erase needs the phone online — it cannot queue " +
                            "like other commands.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (lockDisabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Enable device admin in Settings to use Erase.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                OutlinedTextField(
                    value = eraseConfirm,
                    onValueChange = { eraseConfirm = it },
                    label = { Text("Type ERASE to confirm") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = erasePassword,
                    onValueChange = { erasePassword = it },
                    label = { Text("Confirm with your password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = {
                        viewModel.sendErase(deviceId, erasePassword, eraseConfirm)
                        if (isThisDevice) UploadWorker.enqueueOneTime(context)
                        erasePassword = ""
                    },
                    text = "Erase now",
                    iconRes = R.drawable.ic_triangle_alert,
                    destructive = true,
                    enabled = eraseConfirm == "ERASE" && erasePassword.isNotEmpty() &&
                        state.sending != "erase",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showErasePanel = false },
                ) { Text("Cancel") }
            }

            state.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                ErrorText(error)
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_history, title = "Command history")
            Spacer(Modifier.height(12.dp))
            if (state.isLoading && state.commands.isEmpty()) {
                CircularProgressIndicator()
            } else if (state.commands.isEmpty()) {
                Text(
                    "No commands sent yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Fixed-height list inside the scrollable column.
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.commands, key = { it.id }) { command ->
                        CommandRow(command) {
                            verifyTarget = command
                            verifyResult = null
                            verifyLoading = true
                            scope.launch {
                                verifyResult = viewModel.verifyCommand(command.id)
                                verifyLoading = false
                            }
                        }
                    }
                }
            }
        }
    }

    verifyTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { verifyTarget = null },
            title = { Text("Verify command") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (verifyLoading) {
                        CircularProgressIndicator()
                    } else when (val result = verifyResult) {
                        is Result.Error -> ErrorText(result.message)
                        is Result.Success -> {
                            val verified = result.value.command
                            VerifyLine("Action", verified.action)
                            VerifyLine("Status", verified.status ?: "—")
                            VerifyLine("Issued", verified.issuedAt?.formatTime() ?: "—")
                            VerifyLine("Expires", verified.expiresAt?.formatTime() ?: "—")
                            VerifyLine(
                                "Nonce",
                                verified.nonceTruncated ?: verified.nonce ?: "—",
                            )
                            verified.result?.let { VerifyLine("Result", it) }
                            if (result.value.audit.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Text("Audit", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(4.dp))
                                result.value.audit.forEach { event ->
                                    Text(
                                        event.event +
                                            (event.createdAt?.let { " · ${it.formatTime()}" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    event.detail?.let { detail ->
                                        Text(
                                            detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        null -> { }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { verifyTarget = null }) { Text("Close") }
            },
        )
    }
}

// One tile of the Remote Actions grid: tinted icon + label, centred.
// Disabled tiles (Restart, Vault) render dimmed and do not respond.
@Composable
private fun RowScope.ActionTile(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    badge: String? = null,
) {
    GuardCard(
        modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f),
        onClick = if (enabled) onClick else null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                TintedIcon(iconRes, size = 40.dp, iconSize = 20.dp)
            }
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
            badge?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ControlLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

// One immediate-send option button inside a FlowRow of choices.
@Composable
private fun ControlOption(text: String, busy: Boolean, onClick: () -> Unit) {
    GuardOutlinedButton(
        onClick = onClick,
        text = text,
        enabled = !busy,
    )
}

@Composable
private fun VolumeControl(
    busy: Boolean,
    onSend: (String, Map<String, String>?) -> Unit,
) {
    var stream by remember { mutableStateOf("all") }
    var level by remember { mutableFloatStateOf(100f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TintedIcon(R.drawable.ic_volume, size = 32.dp, iconSize = 16.dp)
        Spacer(Modifier.width(10.dp))
        ControlLabel("Volume — ${level.toInt()}%")
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("all" to "All", "media" to "Media", "ring" to "Ring", "alarm" to "Alarm", "notification" to "Notification").forEach { (value, label) ->
            FilterChip(
                selected = stream == value,
                onClick = { stream = value },
                label = { Text(label) },
            )
        }
    }
    Slider(
        value = level,
        onValueChange = { level = it },
        valueRange = 0f..100f,
        steps = 19,
    )
    GuardOutlinedButton(
        onClick = {
            onSend("set_volume", mapOf("stream" to stream, "level" to level.toInt().toString()))
        },
        text = "Set volume",
        iconRes = R.drawable.ic_volume,
        enabled = !busy,
    )
}

@Composable
private fun BrightnessControl(
    busy: Boolean,
    onSend: (String, Map<String, String>?) -> Unit,
) {
    var level by remember { mutableFloatStateOf(100f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TintedIcon(R.drawable.ic_sun, size = 32.dp, iconSize = 16.dp)
        Spacer(Modifier.width(10.dp))
        ControlLabel("Brightness — ${level.toInt()}%")
    }
    Spacer(Modifier.height(8.dp))
    Slider(
        value = level,
        onValueChange = { level = it },
        valueRange = 0f..100f,
        steps = 19,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GuardOutlinedButton(
            onClick = {
                onSend("set_brightness", mapOf("level" to level.toInt().toString()))
            },
            text = "Set brightness",
            iconRes = R.drawable.ic_sun,
            enabled = !busy,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Auto-brightness",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ControlOption("On", busy) { onSend("set_auto_brightness", mapOf("enabled" to "true")) }
        ControlOption("Off", busy) { onSend("set_auto_brightness", mapOf("enabled" to "false")) }
    }
}

@Composable
private fun TtsControl(
    busy: Boolean,
    onSend: (String, Map<String, String>?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    ControlLabel("Spoken lost message")
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = text,
        onValueChange = {
            if (it.length <= SecurityConfig.LOST_MESSAGE_MAX_LENGTH) text = it
        },
        label = { Text("Spoken aloud at full volume (optional)") },
        singleLine = false,
        maxLines = 3,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    GuardOutlinedButton(
        onClick = {
            onSend(
                "speak_message",
                text.takeIf { it.isNotBlank() }?.let { mapOf("message" to it) },
            )
        },
        text = "Speak on phone",
        iconRes = R.drawable.ic_speak,
        enabled = !busy,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Without a message the phone speaks “This phone is lost. " +
            "Please contact the owner.” Needs a text-to-speech voice " +
            "on the phone.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WallpaperControl(
    busy: Boolean,
    onSend: (String, Map<String, String>?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    ControlLabel("Lost-message wallpaper")
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = text,
        onValueChange = {
            if (it.length <= SecurityConfig.LOST_MESSAGE_MAX_LENGTH) text = it
        },
        label = { Text("Message on the lock-screen wallpaper (optional)") },
        singleLine = false,
        maxLines = 3,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    GuardOutlinedButton(
        onClick = {
            onSend(
                "set_wallpaper",
                buildMap {
                    text.takeIf { it.isNotBlank() }?.let { put("message", it) }
                    put("which", "lock")
                },
            )
        },
        text = "Set lock-screen wallpaper",
        iconRes = R.drawable.ic_image,
        enabled = !busy,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Replaces the lock-screen wallpaper with the message. The " +
            "previous wallpaper cannot be restored afterwards.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CommandRow(command: CommandDto, onClick: () -> Unit) {
    GuardCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedIcon(
                when (command.action) {
                    "ring" -> R.drawable.ic_bell
                    "lock" -> R.drawable.ic_lock
                    "erase" -> R.drawable.ic_triangle_alert
                    "set_volume" -> R.drawable.ic_volume
                    "set_brightness", "set_auto_brightness" -> R.drawable.ic_sun
                    "torch_on", "torch_off" -> R.drawable.ic_flashlight
                    "set_ringer_mode" -> R.drawable.ic_bell
                    "set_dnd" -> R.drawable.ic_dnd
                    "set_screen_timeout" -> R.drawable.ic_clock
                    "set_auto_rotate" -> R.drawable.ic_rotate
                    "speak_message" -> R.drawable.ic_speak
                    "wake_screen" -> R.drawable.ic_power
                    "set_wallpaper" -> R.drawable.ic_image
                    "bluetooth_on" -> R.drawable.ic_bluetooth
                    "evidence_capture" -> R.drawable.ic_camera
                    else -> R.drawable.ic_shield
                },
                size = 36.dp,
                iconSize = 18.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (command.action) {
                        "ring" -> "Ring"
                        "lock" -> "Lock"
                        "lost_mode_on" -> "Lost Mode on"
                        "lost_mode_off" -> "Lost Mode off"
                        "erase" -> "Erase"
                        "set_volume" -> "Volume"
                        "set_brightness" -> "Brightness"
                        "set_auto_brightness" -> "Auto-brightness"
                        "torch_on" -> "Torch on"
                        "torch_off" -> "Torch off"
                        "set_ringer_mode" -> "Ringer mode"
                        "set_dnd" -> "Do Not Disturb"
                        "set_screen_timeout" -> "Screen timeout"
                        "set_auto_rotate" -> "Auto-rotate"
                        "speak_message" -> "Spoken message"
                        "wake_screen" -> "Wake screen"
                        "set_wallpaper" -> "Wallpaper"
                        "bluetooth_on" -> "Bluetooth on"
                        "evidence_capture" -> "Evidence capture"
                        else -> command.action
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    listOfNotNull(
                        command.status,
                        command.issuedAt?.replace("T", " ")?.take(16),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                command.result?.let { result ->
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VerifyLine(label: String, value: String) {
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** "2026-11-16T23:00:00.000Z" -> "2026-11-16 23:00:00". */
private fun String.formatTime(): String = replace("T", " ").take(19)
