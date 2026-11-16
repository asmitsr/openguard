package com.openguard.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openguard.core.common.Result
import com.openguard.feature.auth.AuthRepository
import com.openguard.feature.devices.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isBusy: Boolean = false,
    val loggedOut: Boolean = false,
    val accountDeleted: Boolean = false,
    val deviceSignedOut: Boolean = false,
    val info: String? = null,
    val error: String? = null,
)

class SettingsViewModel(
    private val repository: AuthRepository,
    private val devices: DeviceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Id of this phone's device record, if registered. */
    val thisDeviceId: String? get() = devices.thisDeviceId

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

    /** Signs out every other session; reports how many were revoked. */
    fun revokeOtherSessions() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, error = null, info = null)
            _state.value = when (val result = repository.revokeOtherSessions()) {
                is Result.Success -> _state.value.copy(
                    isBusy = false,
                    info = "${result.value} other session" +
                        (if (result.value == 1) "" else "s") + " signed out",
                )
                is Result.Error -> _state.value.copy(isBusy = false, error = result.message)
            }
        }
    }

    /**
     * Signs THIS phone out: the server rotates its device token, so
     * tracking and commands stop working for it until it is re-added.
     * The caller stops LocationService + periodic work on deviceSignedOut.
     */
    fun signOutThisDevice() {
        val id = devices.thisDeviceId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, error = null)
            _state.value = when (val result = devices.signOutDevice(id)) {
                is Result.Success -> SettingsUiState(deviceSignedOut = true)
                is Result.Error -> _state.value.copy(isBusy = false, error = result.message)
            }
        }
    }
}
