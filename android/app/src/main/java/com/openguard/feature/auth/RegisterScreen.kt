package com.openguard.feature.auth

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onRegisterSuccess: () -> Unit,
    onLoginClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var email by remember { mutableStateOf("") }
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) onRegisterSuccess()
    }
    AuthForm(
        title = "Create your account",
        submitLabel = "Register",
        state = state,
        email = email,
        onEmailChange = { email = it },
        onSubmit = viewModel::register,
        onClearError = viewModel::clearError,
    ) {
        TextButton(onClick = onLoginClick) {
            Text("Already have an account? Log in")
        }
    }
}
