package com.openguard.feature.passkeys

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openguard.core.auth.PasskeyManager
import com.openguard.core.common.Result
import com.openguard.core.network.ApiService
import com.openguard.core.network.PasskeyDto
import com.openguard.core.network.apiCall
import com.openguard.core.network.apiUnitCall
import com.openguard.feature.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PasskeyUiState(
    val passkeys: List<PasskeyDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** Passkey list + create + revoke. Register retries once (see [create]). */
class PasskeyViewModel(
    private val api: ApiService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PasskeyUiState())
    val state: StateFlow<PasskeyUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            _state.value = when (val result = apiCall { api.listPasskeys() }) {
                is Result.Success -> PasskeyUiState(passkeys = result.value)
                is Result.Error -> _state.value.copy(isLoading = false, error = result.message)
            }
        }
    }

    /**
     * Creates a passkey on this phone. Retries once with a fresh
     * challenge: the first challenge may have been issued by another
     * server isolate. Never retries a cancellation or a missing
     * provider — re-prompting helps no one.
     */
    fun create(name: String, activity: Activity) {
        val trimmed = name.trim().ifBlank { "This phone" }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            var result = authRepository.passkeyRegister(trimmed, activity)
            if (result is Result.Error && result.message != PasskeyManager.CANCELLED &&
                result.message != PasskeyManager.NO_PROVIDER
            ) {
                result = authRepository.passkeyRegister(trimmed, activity)
            }
            when (result) {
                is Result.Success -> load()
                is Result.Error -> _state.value = _state.value.copy(
                    isLoading = false,
                    // A cancellation is not an error — just stop.
                    error = result.message.takeIf { it != PasskeyManager.CANCELLED },
                )
            }
        }
    }

    fun revoke(id: String) {
        viewModelScope.launch {
            when (val result = apiUnitCall { api.deletePasskey(id) }) {
                is Result.Success -> load()
                is Result.Error -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
