package com.openguard.feature.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardTopBar

@Composable
fun RecoverAccountScreen(
    viewModel: AuthViewModel,
    onBack: () -> Unit,
    onRecovered: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { GuardTopBar(title = "Recover account", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
        ) {
            if (state.recovered) {
                Text(
                    "Password changed — log in with your new password",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(24.dp))
                GuardButton(
                    onClick = onRecovered,
                    text = "Back to login",
                    modifier = Modifier.fillMaxWidth(),
                )
                return@Column
            }
            Text(
                "Use one of your saved recovery codes to set a new " +
                    "password. The code works once.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; localError = null; viewModel.clearError() },
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it; localError = null; viewModel.clearError() },
                label = { Text("Recovery code") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it; localError = null; viewModel.clearError() },
                label = { Text("New password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            val error = localError ?: state.error
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                ErrorText(error)
            }
            Spacer(Modifier.height(24.dp))
            GuardButton(
                onClick = {
                    when {
                        "@" !in email -> localError = "Enter a valid email address."
                        code.isBlank() -> localError = "Enter a recovery code."
                        newPassword.length < 8 ->
                            localError = "Password must be at least 8 characters."
                        else -> viewModel.recover(email.trim(), code.trim(), newPassword)
                    }
                },
                text = "Change password",
                enabled = !state.isLoading,
                loading = state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
