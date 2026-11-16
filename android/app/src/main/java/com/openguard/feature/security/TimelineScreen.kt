package com.openguard.feature.security

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openguard.R
import com.openguard.core.common.relativeTime
import com.openguard.core.network.SecurityEventDto
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardCard
import com.openguard.ui.GuardTopBar
import com.openguard.ui.TintedIcon

// Labels mirror the dashboard AlertsPanel EVENT_LABELS exactly, so the
// app and the web UI describe the same event the same way. Keep both
// in sync when a new event type is added.
private val EVENT_LABELS = mapOf(
    "sim_changed" to "SIM changed — check if this was you",
    "admin_disabled" to "Device admin disabled",
    "location_permission_revoked" to "Location permission revoked",
    "location_disabled" to "Location disabled",
    "low_battery" to "Low battery",
    "sync_gap" to "Sync gap",
    "sms_command" to "SMS command accepted",
    "chaos_started" to "SIM removed during Lost Mode — chaos mode started",
)

/**
 * Security timeline: SIM changes, offline gaps, location events, SMS
 * commands, chaos starts — newest first. "Indication" means the event
 * is a signal, not proof (e.g. a SIM change); "Confirmed" means the
 * app verified the fact.
 */
@Composable
fun TimelineScreen(
    deviceId: String,
    deviceName: String,
    viewModel: TimelineViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(deviceId) { viewModel.load(deviceId) }

    Scaffold(
        topBar = { GuardTopBar(title = "Timeline — $deviceName", onBack = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            if (state.isLoading && state.events.isEmpty()) {
                CircularProgressIndicator()
            }
            state.error?.let { error ->
                ErrorText(error)
            }
            if (!state.isLoading && state.events.isEmpty() && state.error == null) {
                Text("No security events for this device yet.")
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.events, key = { it.id }) { event ->
                    TimelineRow(event)
                }
            }
            Spacer(Modifier.height(12.dp))
            GuardButton(
                onClick = { viewModel.load(deviceId) },
                text = "Refresh",
                loading = state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TimelineRow(event: SecurityEventDto) {
    GuardCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedIcon(eventIcon(event.eventType), size = 36.dp, iconSize = 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    EVENT_LABELS[event.eventType] ?: event.eventType,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    relativeTime(event.createdAt) +
                        " · " + if (event.confirmed) "Confirmed" else "Indication",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                detailLine(event)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun eventIcon(eventType: String): Int = when (eventType) {
    "sim_changed", "admin_disabled", "chaos_started" -> R.drawable.ic_triangle_alert
    "location_permission_revoked", "location_disabled" -> R.drawable.ic_map_pin
    "low_battery" -> R.drawable.ic_battery
    "sync_gap" -> R.drawable.ic_history
    "sms_command" -> R.drawable.ic_mail
    else -> R.drawable.ic_shield
}

/**
 * One extra line from the event details, where the type carries any:
 * the SMS action, the offline gap length or reason, whether the new
 * SIM is trusted. Unknown or missing details show nothing.
 */
private fun detailLine(event: SecurityEventDto): String? {
    val details = event.details?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    fun string(key: String): String? =
        details.get(key)?.takeIf { it.isJsonPrimitive }?.asString
    return when (event.eventType) {
        "sms_command" -> string("action")?.let { "Action: $it" }
        "sync_gap" -> when {
            string("gap_hours") != null -> "Offline for about ${string("gap_hours")} hours"
            string("reason") == "connectivity_lost" -> "Connection lost"
            else -> null
        }
        "sim_changed" -> {
            val trusted = details.get("trusted")?.takeIf { it.isJsonPrimitive }?.asBoolean
            when (trusted) {
                true -> "New SIM is trusted"
                false -> "New SIM is not in your trusted list"
                null -> null
            }
        }
        else -> null
    }
}
