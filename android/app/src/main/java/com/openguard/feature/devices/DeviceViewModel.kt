package com.openguard.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openguard.core.common.Result
import com.openguard.core.network.DeviceDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DeviceUiState(
    val devices: List<DeviceDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class RegisterUiState(
    val device: DeviceDto? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class DeviceViewModel(private val repository: DeviceRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceUiState(isLoading = true))
    val uiState: StateFlow<DeviceUiState> = _uiState.asStateFlow()

    private val _registerState = MutableStateFlow(RegisterUiState())
    val registerState: StateFlow<RegisterUiState> = _registerState.asStateFlow()

    /** Id of this phone's device record, if registered. */
    val thisDeviceId: String? get() = repository.thisDeviceId

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            _uiState.value = when (val result = repository.listDevices()) {
                is Result.Success -> DeviceUiState(devices = result.value)
                is Result.Error -> _uiState.value.copy(isLoading = false, error = result.message)
            }
        }
    }

    fun registerThisDevice(name: String) {
        viewModelScope.launch {
            _registerState.value = RegisterUiState(isLoading = true)
            _registerState.value = when (val result = repository.registerThisDevice(name)) {
                is Result.Success -> RegisterUiState(device = result.value)
                is Result.Error -> RegisterUiState(error = result.message)
            }
        }
    }

    fun deleteDevice(id: String) {
        viewModelScope.launch {
            when (repository.deleteDevice(id)) {
                is Result.Success -> refresh()
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(error = "Could not remove the device.")
                }
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
