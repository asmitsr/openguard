package com.openguard.feature.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.openguard.R
import com.openguard.core.location.TrackingState
import com.openguard.core.network.DeviceDto
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardCard
import com.openguard.ui.GuardOutlinedButton
import com.openguard.ui.GuardTopBar
import com.openguard.ui.TintedIcon

@Composable
fun DeviceListScreen(
    viewModel: DeviceViewModel,
    onDeviceClick: (DeviceDto) -> Unit,
    onCommandsClick: (DeviceDto) -> Unit,
    onAddDeviceClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val isTracking by TrackingState.isTracking.collectAsState()
    var deviceToDelete by remember { mutableStateOf<DeviceDto?>(null) }

    // Refresh whenever this screen is (re-)entered, e.g. after registering
    // a device on the consent screen (which uses its own ViewModel).
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            GuardTopBar(
                title = "My devices",
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            painterResource(R.drawable.ic_settings),
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
        ) {
            // Tracking control for THIS phone (only when it is registered).
            if (viewModel.thisDeviceId != null) {
                GuardCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TintedIcon(R.drawable.ic_radar)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (isTracking) "Tracking is ON for this phone" else "Tracking is OFF",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "While tracking is on, a notification stays visible " +
                                "and this phone's location is recorded.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (isTracking) {
                            GuardOutlinedButton(
                                onClick = onStopTracking,
                                text = "Stop tracking",
                            )
                        } else {
                            GuardButton(
                                onClick = onStartTracking,
                                text = "Start tracking",
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (state.isLoading && state.devices.isEmpty()) {
                CircularProgressIndicator()
            }
            state.error?.let { error ->
                ErrorText(error)
                Spacer(Modifier.height(8.dp))
            }
            if (!state.isLoading && state.devices.isEmpty()) {
                Text("No devices yet. Add this phone to start tracking it.")
                Spacer(Modifier.height(16.dp))
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.devices, key = { it.id }) { device ->
                    DeviceRow(
                        device = device,
                        isThisDevice = device.id == viewModel.thisDeviceId,
                        onClick = { onDeviceClick(device) },
                        onCommandsClick = { onCommandsClick(device) },
                        onDeleteClick = { deviceToDelete = device },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            GuardButton(
                onClick = onAddDeviceClick,
                text = "Add this phone",
                iconRes = R.drawable.ic_plus,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    deviceToDelete?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToDelete = null },
            icon = {
                Icon(
                    painterResource(R.drawable.ic_trash_2),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Remove device?") },
            text = { Text("“${device.name}” will be removed from your account.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDevice(device.id)
                        deviceToDelete = null
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deviceToDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DeviceRow(
    device: DeviceDto,
    isThisDevice: Boolean,
    onClick: () -> Unit,
    onCommandsClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    GuardCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedIcon(R.drawable.ic_smartphone)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name + if (isThisDevice) " (this phone)" else "",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    device.model ?: "Unknown model",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        device.batteryLevel != null ->
                            "Battery ${device.batteryLevel}%" +
                                if (device.batteryCharging == true) " (charging)" else ""
                        else -> "Battery unknown"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Last seen: ${device.lastSeenAt ?: "never"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (device.lostMode) {
                    Text(
                        "Lost mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = onCommandsClick) {
                Icon(
                    painterResource(R.drawable.ic_shield),
                    contentDescription = "Commands",
                )
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    painterResource(R.drawable.ic_trash_2),
                    contentDescription = "Remove device",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
