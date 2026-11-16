package com.openguard.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openguard.core.common.Result
import com.openguard.feature.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isBusy: Boolean = false,
    val loggedOut: Boolean = false,
    val accountDeleted: Boolean = false,
    val error: String? = null,
)

class SettingsViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _state.value = SettingsUiState(loggedOut = true)
        }
    }

    fun deleteAccount(password: String) {
        viewModelScope.launch {
            _state.value = SettingsUiState(isBusy = true)
            _state.value = when (val result = repository.deleteAccount(password)) {
                is Result.Success -> SettingsUiState(accountDeleted = true)
                is Result.Error -> SettingsUiState(error = result.message)
            }
        }
    }
}
