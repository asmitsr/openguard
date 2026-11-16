package com.openguard.feature.auth

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onRegisterSuccess: () -> Unit,
    onLoginClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) onRegisterSuccess()
    }
    AuthForm(
        title = "Create your account",
        submitLabel = "Register",
        state = state,
        onSubmit = viewModel::register,
        onClearError = viewModel::clearError,
    ) {
        TextButton(onClick = onLoginClick) {
            Text("Already have an account? Log in")
        }
    }
}
