package com.openguard.feature.protection

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.openguard.R
import com.openguard.core.common.relativeTime
import com.openguard.core.theft.SnatchSensitivity
import com.openguard.ui.GuardCard
import com.openguard.ui.GuardOutlinedButton
import com.openguard.ui.GuardTopBar
import com.openguard.ui.SectionHeader
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * Offline protection for THIS phone only: offline auto-lock and the
 * low-battery last-location burst. Both are local settings — they are
 * not sent to your other devices, so each phone is configured on
 * itself. When opened from the auto-lock warning notification
 * (showCancel), the cancel dialog appears while the warning is active.
 */
@Composable
fun OfflineProtectionScreen(
    viewModel: OfflineProtectionViewModel,
    onBack: () -> Unit,
    showCancel: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    var cancelDismissed by remember { mutableStateOf(false) }
    // Derived, not an effect: the dialog shows while the warning is
    // active and the owner has not dismissed it; cancelling clears the
    // warning, which hides the dialog by itself.
    val showCancelDialog = showCancel && state.warningActive && !cancelDismissed

    LaunchedEffect(Unit) {
        viewModel.refresh()
        while (true) {
            delay(30_000L)
            viewModel.refresh()
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { cancelDismissed = true },
            title = { Text("Cancel auto-lock?") },
            text = {
                Text(
                    "Lost Mode is about to turn on because this phone has " +
                        "been offline. Cancel it and restart the offline timer?",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelAutoLock() }) {
                    Text("Cancel auto-lock")
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelDismissed = true }) {
                    Text("Keep it")
                }
            },
        )
    }

    Scaffold(
        topBar = { GuardTopBar(title = "Offline protection", onBack = onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "These protections run on this phone only — they are not " +
                    "sent to your other devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Offline auto-lock ----
            GuardCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionHeader(iconRes = R.drawable.ic_shield_check, title = "Offline auto-lock")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Auto-lock when offline", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.offlineEnabled,
                            onCheckedChange = { viewModel.setOfflineEnabled(it) },
                        )
                    }
                    Text(
                        "If this phone has no network for the chosen time and " +
                            "is locked, Lost Mode turns on by itself — decoy " +
                            "lock, evidence recording, and theft response.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(6, 12, 24).forEach { hours ->
                            FilterChip(
                                selected = state.thresholdHours == hours,
                                onClick = { viewModel.setThreshold(hours) },
                                label = { Text("$hours hours") },
                                enabled = state.offlineEnabled,
                            )
                        }
                    }
                    Text(
                        offlineStatusText(state),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.warningActive) {
                        GuardOutlinedButton(
                            onClick = { viewModel.cancelAutoLock() },
                            text = "Cancel auto-lock",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        "Airplane mode looks the same as no network: if you " +
                            "turn airplane mode on and lock the phone, " +
                            "auto-lock can fire. A warning appears 1 hour " +
                            "before, and tapping it lets you cancel. Needs a " +
                            "screen lock (PIN, pattern, or password) — " +
                            "without one, the phone is never locked, so " +
                            "auto-lock cannot fire.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- Low-battery last location ----
            GuardCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionHeader(iconRes = R.drawable.ic_battery, title = "Low-battery last location")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Last-location burst", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.burstEnabled,
                            onCheckedChange = { viewModel.setBurstEnabled(it) },
                        )
                    }
                    Text(
                        "When the battery drops to 5%, OpenGuard sends one " +
                            "last location and then stops — no continuous " +
                            "tracking, so the last battery is not wasted. " +
                            "Works even if tracking is off. Needs location " +
                            "permission for a fresh fix.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        burstStatusText(state),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // ---- Snatch detection ----
            GuardCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionHeader(iconRes = R.drawable.ic_triangle_alert, title = "Snatch detection")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Lock on snatch", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.snatchEnabled,
                            onCheckedChange = { viewModel.setSnatchEnabled(it) },
                        )
                    }
                    Text(
                        "If someone grabs this phone out of your hand and " +
                            "runs, it locks itself with Lost Mode — decoy " +
                            "lock, evidence recording, and theft response. " +
                            "Only works while tracking is on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            SnatchSensitivity.LOW to "Low",
                            SnatchSensitivity.MEDIUM to "Medium",
                            SnatchSensitivity.HIGH to "High",
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = state.snatchSensitivity == value,
                                onClick = { viewModel.setSnatchSensitivity(value) },
                                label = { Text(label) },
                                enabled = state.snatchEnabled,
                            )
                        }
                    }
                    Text(
                        snatchStatusText(state),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "This is a motion heuristic, not a perfect " +
                            "detector: a violent shake can lock the phone " +
                            "by mistake, and a gentle grab with no yank " +
                            "can be missed. A drop does not trigger it — " +
                            "the phone has to keep moving after the grab. " +
                            "After firing once, it rests for 5 minutes. " +
                            "If it locks by mistake, unlock with your " +
                            "device PIN, pattern, or password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- Dead-man's switch ----
            GuardCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionHeader(iconRes = R.drawable.ic_clock, title = "Dead-man's switch")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Lock, then erase, when never unlocked", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.deadmanEnabled,
                            onCheckedChange = { viewModel.setDeadmanEnabled(it) },
                        )
                    }
                    Text(
                        "If this phone is never unlocked for the lock time, " +
                            "Lost Mode turns on by itself. If it stays locked " +
                            "for the wipe time, the phone erases itself — so a " +
                            "stolen phone the thief can never unlock eventually " +
                            "destroys its own data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Lock after", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 7, 14, 30).forEach { days ->
                            FilterChip(
                                selected = state.lockDays == days,
                                onClick = { viewModel.setLockDays(days) },
                                label = { Text("$days days") },
                                enabled = state.deadmanEnabled,
                            )
                        }
                    }
                    Text("Erase after", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 14, 30, 60).forEach { days ->
                            FilterChip(
                                selected = state.wipeDays == days,
                                onClick = { viewModel.setWipeDays(days) },
                                label = { Text("$days days") },
                                enabled = state.deadmanEnabled && days > state.lockDays,
                            )
                        }
                    }
                    Text(
                        deadmanStatusText(state),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    GuardOutlinedButton(
                        onClick = { viewModel.testDeadmanLock() },
                        text = "Test lock firing",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.deadmanEnabled,
                    )
                    Text(
                        "Test moves the clock just past the lock time — lock " +
                            "the phone right after tapping and Lost Mode fires " +
                            "within a minute. It can never trigger the wipe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Wiping is IRREVERSIBLE — everything on the phone is " +
                            "permanently erased. A phone left at home for weeks " +
                            "WILL lock, then wipe: that is the feature working " +
                            "as designed. If you cannot reach your phone, the " +
                            "switch still fires. Needs a screen lock — without " +
                            "one, the phone is never locked, so the switch " +
                            "cannot fire. A warning appears 24 hours before the " +
                            "wipe; unlocking the phone cancels it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- Failed-attempt wipe ----
            GuardCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionHeader(iconRes = R.drawable.ic_trash_2, title = "Failed-attempt wipe")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Wipe after failed unlocks", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.wipecapEnabled,
                            onCheckedChange = { viewModel.setWipecapEnabled(it) },
                        )
                    }
                    Text(
                        "After this many failed unlock attempts on the real " +
                            "lock screen, the phone erases itself through the " +
                            "60-second countdown — a returning owner can still " +
                            "abort it with the device PIN, pattern, or password.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15, 20).forEach { cap ->
                            FilterChip(
                                selected = state.attemptCap == cap,
                                onClick = { viewModel.setAttemptCap(cap) },
                                label = { Text("$cap attempts") },
                                enabled = state.wipecapEnabled,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Also wipe eSIM profiles", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.wipeEsim,
                            onCheckedChange = { viewModel.setWipeEsim(it) },
                            enabled = state.wipecapEnabled,
                        )
                    }
                    Text(
                        "Wiping eSIM destroys your cellular identity — the " +
                            "eSIM profiles cannot be restored from the phone. " +
                            "Only turn this on if you understand that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        wipecapStatusText(state),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Only the REAL lock screen counts — wrong PINs on the " +
                            "decoy lock screen are never counted. Wiping is " +
                            "IRREVERSIBLE. Never test this on your only phone " +
                            "without a backup: after the chosen number of wrong " +
                            "unlocks the phone erases itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun offlineStatusText(state: OfflineProtectionUiState): String {
    if (!state.offlineEnabled) return "Off"
    if (state.hasNetwork) return "Online now — the timer resets while connected."
    val thresholdMs = state.thresholdHours * 3_600_000L
    val offline = durationText(state.offlineDurationMs)
    return when {
        state.offlineDurationMs >= thresholdMs ->
            "Offline for $offline — Lost Mode turns on as soon as the phone locks."
        state.warningActive ->
            "Offline for $offline — Lost Mode turns on in about " +
                durationText(thresholdMs - state.offlineDurationMs) + "."
        else ->
            "Offline for $offline — Lost Mode turns on after " +
                "${state.thresholdHours} hours if the phone stays locked."
    }
}

private fun burstStatusText(state: OfflineProtectionUiState): String {
    if (!state.burstEnabled) return "Off"
    val last = if (state.lastBurstAt == 0L) {
        "never"
    } else {
        relativeTime(Instant.ofEpochMilli(state.lastBurstAt).toString())
    }
    return if (state.burstSentThisCycle) {
        "Last burst: $last — sent this cycle, it will fire again after the next charge."
    } else {
        "Last burst: $last."
    }
}

private fun snatchStatusText(state: OfflineProtectionUiState): String {
    if (!state.snatchEnabled) return "Off"
    val last = if (state.lastSnatchAt == 0L) {
        "never"
    } else {
        relativeTime(Instant.ofEpochMilli(state.lastSnatchAt).toString())
    }
    val active = if (state.snatchActive) {
        "Listening now — tracking is on."
    } else {
        "On — starts listening when tracking starts."
    }
    return "$active Last detection: $last."
}

private fun deadmanStatusText(state: OfflineProtectionUiState): String {
    if (!state.deadmanEnabled) return "Off"
    val days = state.daysSinceUnlock
    val lockIn = (state.lockDays - days).coerceAtLeast(0)
    val wipeIn = (state.wipeDays - days).coerceAtLeast(0)
    val last = if (days == 0L) "today" else "$days days ago"
    return when {
        days >= state.wipeDays ->
            "No unlock for $days days — past the wipe time. Unlock to reset the timer."
        days >= state.lockDays ->
            "Last unlock $last — Lost Mode is on. The phone erases itself in " +
                "$wipeIn days unless unlocked."
        state.deadmanWarningActive ->
            "Last unlock $last — the phone erases itself in $wipeIn days. " +
                "Unlock to cancel."
        else ->
            "Last unlock $last — locks in $lockIn days, erases in $wipeIn days " +
                "without an unlock."
    }
}

private fun wipecapStatusText(state: OfflineProtectionUiState): String {
    if (!state.wipecapEnabled) return "Off"
    val esim = if (state.wipeEsim) " eSIM profiles will be wiped too." else ""
    return "Failed attempts: ${state.failedCount} / ${state.attemptCap}.$esim"
}

/** Millis as "just now", "5 min", "3 h" or "3 h 12 min". */
private fun durationText(ms: Long): String {
    val minutes = ms / 60_000L
    if (minutes < 1) return "just now"
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0L) "$hours h" else "$hours h $rest min"
}
