package com.openguard.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openguard.core.common.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val recovered: Boolean = false,
    val error: String? = null,
)

/** One ViewModel for the login + register screens (same state shape). */
class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState(isLoading = true)
            _uiState.value = when (val result = repository.login(email, password)) {
                is Result.Success -> AuthUiState(isLoggedIn = true)
                is Result.Error -> AuthUiState(error = result.message)
            }
        }
    }

    fun register(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState(isLoading = true)
            _uiState.value = when (val result = repository.register(email, password)) {
                is Result.Success -> AuthUiState(isLoggedIn = true)
                is Result.Error -> AuthUiState(error = result.message)
            }
        }
    }

    /** Password reset with a recovery code (no auth). */
    fun recover(email: String, code: String, newPassword: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState(isLoading = true)
            _uiState.value = when (val result = repository.recover(email, code, newPassword)) {
                is Result.Success -> AuthUiState(recovered = true)
                is Result.Error -> AuthUiState(error = result.message)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
