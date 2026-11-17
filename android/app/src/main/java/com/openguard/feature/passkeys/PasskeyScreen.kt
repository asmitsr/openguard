package com.openguard.feature.passkeys

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openguard.R
import com.openguard.core.common.relativeTime
import com.openguard.core.network.PasskeyDto
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardCard
import com.openguard.ui.GuardTopBar
import com.openguard.ui.SectionHeader
import com.openguard.ui.TintedIcon

@Composable
fun PasskeyScreen(
    viewModel: PasskeyViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var confirmRevokeId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = { GuardTopBar(title = stringResource(R.string.passkey_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.passkey_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_shield_check, title = stringResource(R.string.passkey_create_title))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; viewModel.clearError() },
                label = { Text(stringResource(R.string.passkey_name_label)) },
                placeholder = { Text(stringResource(R.string.passkey_name_default)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            GuardButton(
                onClick = {
                    (context as? Activity)?.let { viewModel.create(name, it) }
                },
                text = stringResource(R.string.passkey_create),
                iconRes = R.drawable.ic_shield_check,
                enabled = !state.isLoading,
                loading = state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.passkey_create_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                ErrorText(error)
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(iconRes = R.drawable.ic_lock, title = stringResource(R.string.passkey_title))
            Spacer(Modifier.height(12.dp))
            when {
                state.isLoading && state.passkeys.isEmpty() -> CircularProgressIndicator()
                state.passkeys.isEmpty() -> Text(
                    stringResource(R.string.passkey_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.passkeys.forEach { passkey ->
                        PasskeyRow(
                            passkey = passkey,
                            confirming = confirmRevokeId == passkey.id,
                            onRevokeClick = {
                                if (confirmRevokeId == passkey.id) {
                                    confirmRevokeId = null
                                    viewModel.revoke(passkey.id)
                                } else {
                                    confirmRevokeId = passkey.id
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PasskeyRow(
    passkey: PasskeyDto,
    confirming: Boolean,
    onRevokeClick: () -> Unit,
) {
    GuardCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedIcon(R.drawable.ic_shield_check, size = 36.dp, iconSize = 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(passkey.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.passkey_created_at, relativeTime(passkey.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    passkey.lastUsedAt?.let {
                        stringResource(R.string.passkey_last_used, relativeTime(it))
                    } ?: stringResource(R.string.passkey_never_used),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRevokeClick) {
                Text(
                    stringResource(
                        if (confirming) R.string.passkey_revoke_confirm else R.string.passkey_revoke,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
