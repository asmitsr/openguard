package com.openguard.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openguard.R
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardOutlinedButton
import com.openguard.ui.GuardTopBar
import com.openguard.ui.SectionHeader

@Composable
fun RecoveryScreen(
    viewModel: RecoveryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var savedConfirmed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { GuardTopBar(title = "Recovery codes", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(iconRes = R.drawable.ic_shield_check, title = "Recovery codes")
            Spacer(Modifier.height(8.dp))
            Text(
                "Recovery codes let you reset your password if you forget " +
                    "it. Each code works once. Keep them somewhere safe — " +
                    "anyone with a code can reset your password.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (state.isLoading && state.remaining == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    when (val remaining = state.remaining) {
                        null -> "Could not load the remaining count."
                        0 -> "No unused recovery codes left."
                        1 -> "1 unused recovery code left."
                        else -> "$remaining unused recovery codes left."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            val codes = state.codes
            if (codes == null) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Generating new codes invalidates the old ones.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; viewModel.clearError() },
                    label = { Text("Confirm with your password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                GuardButton(
                    onClick = {
                        viewModel.generate(password)
                        password = ""
                        savedConfirmed = false
                        copied = false
                    },
                    text = "Generate new codes",
                    iconRes = R.drawable.ic_shield_check,
                    enabled = password.isNotEmpty(),
                    loading = state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Save these codes now — they are shown only once.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            MaterialTheme.shapes.medium,
                        )
                        .padding(12.dp),
                ) {
                    codes.forEach { code ->
                        Text(
                            code,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                GuardOutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "OpenGuard recovery codes",
                                codes.joinToString("\n"),
                            ),
                        )
                        copied = true
                    },
                    text = if (copied) "Copied" else "Copy all",
                    iconRes = R.drawable.ic_check,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = savedConfirmed,
                        onCheckedChange = { savedConfirmed = it },
                    )
                    Text(
                        "I have saved these codes",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(12.dp))
                GuardButton(
                    onClick = viewModel::dismissCodes,
                    text = "Done",
                    enabled = savedConfirmed,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                ErrorText(error)
            }
        }
    }
}
