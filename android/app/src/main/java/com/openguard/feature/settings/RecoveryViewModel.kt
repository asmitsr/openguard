package com.openguard.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openguard.core.common.Result
import com.openguard.feature.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecoveryUiState(
    val remaining: Int? = null,
    val codes: List<String>? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class RecoveryViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(RecoveryUiState(isLoading = true))
    val state: StateFlow<RecoveryUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            _state.value = when (val result = repository.recoveryCodesRemaining()) {
                is Result.Success -> _state.value.copy(isLoading = false, remaining = result.value)
                is Result.Error -> _state.value.copy(isLoading = false, error = result.message)
            }
        }
    }

    /**
     * Generates a fresh set of codes (the old ones stop working). The
     * codes live only in this state until Done dismisses them — they are
     * shown once and never stored on the phone.
     */
    fun generate(password: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            _state.value = when (val result = repository.generateRecoveryCodes(password)) {
                is Result.Success -> _state.value.copy(
                    isLoading = false,
                    codes = result.value,
                    remaining = result.value.size,
                )
                is Result.Error -> _state.value.copy(isLoading = false, error = result.message)
            }
        }
    }

    fun dismissCodes() {
        _state.value = _state.value.copy(codes = null)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
