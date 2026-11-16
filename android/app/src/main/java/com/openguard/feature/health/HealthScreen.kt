package com.openguard.feature.health

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openguard.R
import com.openguard.core.common.relativeTime
import com.openguard.core.network.DeviceDto
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardCard
import com.openguard.ui.GuardTopBar
import com.openguard.ui.SectionHeader

/**
 * Device health, as last reported by the heartbeat: battery, storage,
 * memory, last boot, last sync, and permission health. "Not reported
 * yet" means the device has not sent that field (older app version, or
 * no heartbeat since the health fields landed).
 */
@Composable
fun HealthScreen(
    deviceId: String,
    deviceName: String,
    viewModel: HealthViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(deviceId) { viewModel.load(deviceId) }

    Scaffold(
        topBar = { GuardTopBar(title = "Health — $deviceName", onBack = onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.isLoading && state.device == null) {
                CircularProgressIndicator()
            }
            state.error?.let { ErrorText(it) }
            val device = state.device
            if (device != null) {
                HealthCard(
                    iconRes = R.drawable.ic_battery,
                    title = "Battery",
                ) {
                    HealthRow(
                        "Level",
                        device.batteryLevel?.let { "$it%" },
                    )
                    HealthRow(
                        "Charging",
                        device.batteryCharging?.let { if (it) "Yes" else "No" },
                    )
                    HealthRow(
                        "Temperature",
                        device.batteryTemperatureC?.let { "%.1f °C".format(it) },
                    )
                }
                HealthCard(
                    iconRes = R.drawable.ic_smartphone,
                    title = "Storage & memory",
                ) {
                    HealthRow(
                        "Storage free",
                        storageText(device.storageFreeBytes, device.storageTotalBytes),
                        ok = storageOk(device.storageFreeBytes, device.storageTotalBytes),
                    )
                    HealthRow(
                        "Memory free",
                        memoryText(device.ramFreeBytes, device.ramTotalBytes),
                    )
                }
                HealthCard(
                    iconRes = R.drawable.ic_settings,
                    title = "System",
                ) {
                    HealthRow("Last boot", device.bootedAt?.let { relativeTime(it) })
                    HealthRow("Last sync", relativeTime(device.lastSeenAt))
                }
                HealthCard(
                    iconRes = R.drawable.ic_shield_check,
                    title = "Permissions",
                ) {
                    HealthRow(
                        "Location permission",
                        locationPermissionText(device.locationPermission),
                        ok = device.locationPermission?.let { it == "all" || it == "foreground" },
                    )
                    HealthRow(
                        "Location services",
                        device.locationEnabled?.let { if (it) "On" else "Off" },
                        ok = device.locationEnabled,
                    )
                    HealthRow(
                        "Notifications",
                        device.notificationsEnabled?.let { if (it) "Allowed" else "Blocked" },
                        ok = device.notificationsEnabled,
                    )
                }
                GuardButton(
                    onClick = { viewModel.load(deviceId) },
                    text = "Refresh",
                    loading = state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun HealthCard(
    iconRes: Int,
    title: String,
    content: @Composable () -> Unit,
) {
    GuardCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(iconRes = iconRes, title = title)
            content()
        }
    }
}

/**
 * One label/value row. Null value shows "Not reported yet". The ok flag
 * tints the value: green when healthy, red when not, default otherwise.
 */
@Composable
private fun HealthRow(label: String, value: String?, ok: Boolean? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value ?: "Not reported yet",
            style = MaterialTheme.typography.bodyMedium,
            color = when (ok) {
                true -> MaterialTheme.colorScheme.tertiary
                false -> MaterialTheme.colorScheme.error
                null -> Color.Unspecified
            },
        )
    }
}

private fun storageText(free: Long?, total: Long?): String? {
    if (free == null || total == null) return null
    return "${formatBytes(free)} free of ${formatBytes(total)}"
}

private fun memoryText(free: Long?, total: Long?): String? {
    if (free == null || total == null) return null
    return "${formatBytes(free)} free of ${formatBytes(total)}"
}

/** Storage is "ok" while at least 10% is free. Null when unreported. */
private fun storageOk(free: Long?, total: Long?): Boolean? {
    if (free == null || total == null || total <= 0) return null
    return free * 10 >= total
}

private fun locationPermissionText(value: String?): String? = when (value) {
    "all" -> "Always allowed"
    "foreground" -> "Only while using the app"
    "none" -> "Not granted"
    null -> null
    else -> value
}

/** Bytes as "12.4 GB" / "850 MB" / "42 KB", one decimal, no trailing ".0". */
private fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = "%.1f".format(value).removeSuffix(".0")
    return "$rounded ${units[unit]}"
}
