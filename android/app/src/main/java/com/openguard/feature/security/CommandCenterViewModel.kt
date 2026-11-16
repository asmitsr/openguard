package com.openguard.feature.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openguard.core.common.Result
import com.openguard.core.network.CommandDto
import com.openguard.feature.devices.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CommandCenterUiState(
    val commands: List<CommandDto> = emptyList(),
    val lostMode: Boolean = false,
    val lostMessage: String? = null,
    val isLoading: Boolean = false,
    val sending: String? = null,
    val error: String? = null,
)

class CommandCenterViewModel(
    private val commands: CommandRepository,
    private val devices: DeviceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CommandCenterUiState(isLoading = true))
    val state: StateFlow<CommandCenterUiState> = _state.asStateFlow()

    /** Id of this phone's device record, if registered. */
    val thisDeviceId: String? get() = devices.thisDeviceId

    /** Loads the command history + the device's current lost-mode state. */
    fun load(deviceId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val commandsResult = commands.listCommands(deviceId)
            val devicesResult = devices.listDevices()
            val device = (devicesResult as? Result.Success)?.value?.find { it.id == deviceId }
            _state.value = when (commandsResult) {
                is Result.Success -> CommandCenterUiState(
                    commands = commandsResult.value,
                    lostMode = device?.lostMode == true,
                    lostMessage = device?.lostMessage,
                )
                is Result.Error -> _state.value.copy(
                    isLoading = false,
                    sending = null,
                    error = commandsResult.message,
                )
            }
        }
    }

    fun send(deviceId: String, action: String, message: String? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = action, error = null)
            when (val result = commands.createCommand(deviceId, action, message)) {
                is Result.Success -> load(deviceId)
                is Result.Error -> _state.value = _state.value.copy(
                    sending = null,
                    error = result.message,
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
