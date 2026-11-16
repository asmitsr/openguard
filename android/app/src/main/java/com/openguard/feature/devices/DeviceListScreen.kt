package com.openguard.feature.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openguard.core.location.TrackingState
import com.openguard.core.network.DeviceDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: DeviceViewModel,
    onDeviceClick: (DeviceDto) -> Unit,
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
            TopAppBar(
                title = { Text("My devices") },
                actions = { TextButton(onClick = onSettingsClick) { Text("Settings") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            // Tracking control for THIS phone (only when it is registered).
            if (viewModel.thisDeviceId != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (isTracking) "Tracking is ON for this phone" else "Tracking is OFF",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "While tracking is on, a notification stays visible " +
                                "and this phone's location is recorded.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (isTracking) {
                            OutlinedButton(onClick = onStopTracking) { Text("Stop tracking") }
                        } else {
                            Button(onClick = onStartTracking) { Text("Start tracking") }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (state.isLoading && state.devices.isEmpty()) {
                CircularProgressIndicator()
            }
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            if (!state.isLoading && state.devices.isEmpty()) {
                Text("No devices yet. Add this phone to start tracking it.")
                Spacer(Modifier.height(16.dp))
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.devices, key = { it.id }) { device ->
                    DeviceRow(
                        device = device,
                        isThisDevice = device.id == viewModel.thisDeviceId,
                        onClick = { onDeviceClick(device) },
                        onDeleteClick = { deviceToDelete = device },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAddDeviceClick, modifier = Modifier.fillMaxWidth()) {
                Text("Add this phone")
            }
        }
    }

    deviceToDelete?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToDelete = null },
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
    onDeleteClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    device.name + if (isThisDevice) " (this phone)" else "",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(device.model ?: "Unknown model", style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        device.batteryLevel != null ->
                            "Battery ${device.batteryLevel}%" +
                                if (device.batteryCharging == true) " (charging)" else ""
                        else -> "Battery unknown"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Last seen: ${device.lastSeenAt ?: "never"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDeleteClick) { Text("✕") }
        }
    }
}
