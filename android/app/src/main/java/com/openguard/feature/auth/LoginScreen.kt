package com.openguard.feature.auth

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openguard.R
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardOutlinedButton
import com.openguard.ui.TintedIcon

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoginSuccess: () -> Unit,
    onRegisterClick: () -> Unit,
    onRecoverClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    // Email is hoisted: the passkey button below the form needs it too.
    var email by remember { mutableStateOf("") }
    val context = LocalContext.current
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) onLoginSuccess()
    }
    AuthForm(
        title = "Welcome back",
        submitLabel = "Log in",
        state = state,
        email = email,
        onEmailChange = { email = it },
        onSubmit = viewModel::login,
        onClearError = viewModel::clearError,
    ) {
        Column {
            // Passkey sign-in sits BELOW the password form and never
            // replaces it — the password is the fallback if the phone
            // (and its passkey) is lost.
            GuardOutlinedButton(
                onClick = {
                    (context as? Activity)?.let { viewModel.passkeyLogin(email, it) }
                },
                text = stringResource(R.string.passkey_sign_in),
                iconRes = R.drawable.ic_shield_check,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRegisterClick) {
                Text("No account yet? Register")
            }
            TextButton(onClick = onRecoverClick) {
                Text("Forgot password? Use a recovery code")
            }
        }
    }
}

/** Shared login/register form (same fields, same validation). */
@Composable
internal fun AuthForm(
    title: String,
    submitLabel: String,
    state: AuthUiState,
    email: String,
    onEmailChange: (String) -> Unit,
    onSubmit: (email: String, password: String) -> Unit,
    onClearError: () -> Unit,
    footer: @Composable () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        TintedIcon(R.drawable.ic_shield_check, size = 56.dp, iconSize = 28.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "OpenGuard tracks only your own phones, with your consent.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { onEmailChange(it); localError = null; onClearError() },
            label = { Text("Email") },
            leadingIcon = {
                Icon(painterResource(R.drawable.ic_mail), contentDescription = null)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; localError = null; onClearError() },
            label = { Text("Password") },
            leadingIcon = {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null)
            },
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
                    password.length < 8 -> localError = "Password must be at least 8 characters."
                    else -> onSubmit(email.trim(), password)
                }
            },
            text = submitLabel,
            enabled = !state.isLoading,
            loading = state.isLoading,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        footer()
    }
}
