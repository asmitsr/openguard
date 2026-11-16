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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openguard.R
import com.openguard.core.common.SecurityConfig
import com.openguard.core.location.UploadWorker
import com.openguard.core.network.CommandDto
import com.openguard.core.security.DeviceAdminManager
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardCard
import com.openguard.ui.GuardOutlinedButton
import com.openguard.ui.GuardTopBar
import com.openguard.ui.SectionHeader
import com.openguard.ui.TintedIcon

@Composable
fun CommandCenterScreen(
    deviceId: String,
    deviceName: String,
    viewModel: CommandCenterViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var lostMessage by remember { mutableStateOf("") }
    val isThisDevice = deviceId == viewModel.thisDeviceId
    // Admin state is only knowable for this phone; other devices are
    // unknown, so their Lock button stays enabled with a hint instead.
    val adminActive = remember { DeviceAdminManager.isAdminActive(context) }
    val lockDisabled = isThisDevice && !adminActive

    LaunchedEffect(deviceId) { viewModel.load(deviceId) }

    fun send(action: String, message: String? = null) {
        viewModel.send(deviceId, action, message)
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
            SectionHeader(iconRes = R.drawable.ic_shield, title = "Remote commands")
            Spacer(Modifier.height(8.dp))
            Text(
                "Commands reach the phone within about 15 minutes " +
                    "(faster for this phone).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            GuardButton(
                onClick = { send("ring") },
                text = "Ring this phone",
                iconRes = R.drawable.ic_bell,
                loading = state.sending == "ring",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Rings at full volume for up to 2 minutes, even in silent mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            GuardButton(
                onClick = { send("lock") },
                text = "Lock this phone",
                iconRes = R.drawable.ic_lock,
                enabled = !lockDisabled,
                loading = state.sending == "lock",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (lockDisabled) {
                    "Enable device admin in Settings to use Lock."
                } else if (!isThisDevice) {
                    "Needs device admin enabled on that phone."
                } else {
                    "Locks the screen immediately."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (state.lostMode) {
                Text(
                    "Lost Mode is ON for this phone." +
                        (state.lostMessage?.let { " Message: “$it”" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = { send("lost_mode_off") },
                    text = "Turn off Lost Mode",
                    iconRes = R.drawable.ic_shield_check,
                    enabled = state.sending != "lost_mode_off",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
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
                    onClick = { send("lost_mode_on", lostMessage) },
                    text = "Turn on Lost Mode",
                    iconRes = R.drawable.ic_shield,
                    loading = state.sending == "lost_mode_on",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Locks the phone and shows your message on its screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                        CommandRow(command)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandRow(command: CommandDto) {
    GuardCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedIcon(
                when (command.action) {
                    "ring" -> R.drawable.ic_bell
                    "lock" -> R.drawable.ic_lock
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
