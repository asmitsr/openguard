package com.openguard.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openguard.core.common.Result
import com.openguard.core.network.DeviceDto
import com.openguard.feature.devices.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HealthUiState(
    val device: DeviceDto? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * Health screen state. The health fields ride on the device row
 * (reported by each heartbeat), so this reuses the device list —
 * no extra endpoint. Null health fields mean the device has not
 * reported them yet (older app version, or no heartbeat since the
 * health columns landed).
 */
class HealthViewModel(private val repository: DeviceRepository) : ViewModel() {

    private val _state = MutableStateFlow(HealthUiState())
    val state: StateFlow<HealthUiState> = _state.asStateFlow()

    fun load(deviceId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            _state.value = when (val result = repository.listDevices()) {
                is Result.Success -> {
                    val device = result.value.firstOrNull { it.id == deviceId }
                    if (device == null) {
                        HealthUiState(error = "Device not found.")
                    } else {
                        HealthUiState(device = device)
                    }
                }
                is Result.Error -> HealthUiState(error = result.message)
            }
        }
    }
}
