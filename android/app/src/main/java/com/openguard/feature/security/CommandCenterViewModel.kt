package com.openguard.feature.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openguard.core.common.Result
import com.openguard.core.network.CommandDto
import com.openguard.core.network.CommandVerifyResponse
import com.openguard.core.network.DeviceDto
import com.openguard.feature.auth.AuthRepository
import com.openguard.feature.devices.DeviceRepository
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CommandCenterUiState(
    val commands: List<CommandDto> = emptyList(),
    // The device row feeds the device card + Device Information
    // section (battery, model, OS, network, location state).
    val device: DeviceDto? = null,
    val lostMode: Boolean = false,
    val lostMessage: String? = null,
    val isOnline: Boolean = false,
    val isLoading: Boolean = false,
    val sending: String? = null,
    val error: String? = null,
)

class CommandCenterViewModel(
    private val commands: CommandRepository,
    private val devices: DeviceRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CommandCenterUiState(isLoading = true))
    val state: StateFlow<CommandCenterUiState> = _state.asStateFlow()

    /** Id of this phone's device record, if registered. */
    val thisDeviceId: String? get() = devices.thisDeviceId

    /** Loads the command history + the device's current lost-mode and online state. */
    fun load(deviceId: String) {
        refresh(deviceId, showLoading = true)
    }

    /** Silent refresh while the screen stays open (polling loop). */
    fun poll(deviceId: String) {
        refresh(deviceId, showLoading = false)
    }

    private fun refresh(deviceId: String, showLoading: Boolean) {
        viewModelScope.launch {
            if (showLoading) _state.value = _state.value.copy(isLoading = true, error = null)
            val commandsResult = commands.listCommands(deviceId)
            val devicesResult = devices.listDevices()
            val device = (devicesResult as? Result.Success)?.value?.find { it.id == deviceId }
            _state.value = when (commandsResult) {
                is Result.Success -> _state.value.copy(
                    commands = commandsResult.value,
                    device = device,
                    lostMode = device?.lostMode == true,
                    lostMessage = device?.lostMessage,
                    isOnline = device?.lastSeenAt?.let { isOnline(it) } == true,
                    isLoading = false,
                    sending = null,
                    error = null,
                )
                is Result.Error -> _state.value.copy(
                    isLoading = false,
                    sending = null,
                    error = friendlyError(commandsResult.message),
                )
            }
        }
    }

    /** Sends one command; payload values are strings (see CommandRepository). */
    fun send(deviceId: String, action: String, payload: Map<String, String>? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = action, error = null)
            when (val result = commands.createCommand(deviceId, action, payload)) {
                is Result.Success -> load(deviceId)
                is Result.Error -> _state.value = _state.value.copy(
                    sending = null,
                    error = friendlyError(result.message),
                )
            }
        }
    }

    /**
     * Erase: the typed confirmation must be exactly ERASE, then the
     * password is stepped up and the erase command is issued with the
     * single-use step-up token. The server enforces the remaining rules
     * (online, rate limit) and its error is shown as-is.
     */
    fun sendErase(deviceId: String, password: String, typedConfirm: String) {
        if (typedConfirm != "ERASE") {
            _state.value = _state.value.copy(error = "Type ERASE exactly to confirm.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = "erase", error = null)
            when (val stepUp = auth.stepUp(password)) {
                is Result.Error -> _state.value = _state.value.copy(
                    sending = null,
                    error = friendlyError(stepUp.message),
                )
                is Result.Success -> when (
                    val result = commands.createCommand(
                        deviceId, "erase", stepUpToken = stepUp.value,
                    )
                ) {
                    is Result.Success -> load(deviceId)
                    is Result.Error -> _state.value = _state.value.copy(
                        sending = null,
                        error = friendlyError(result.message),
                    )
                }
            }
        }
    }

    /** One command plus its audit timeline, for the verify dialog. */
    suspend fun verifyCommand(commandId: String): Result<CommandVerifyResponse> =
        commands.verifyCommand(commandId)

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    companion object {
        /** Online = last seen within the last 5 minutes. */
        private const val ONLINE_WINDOW_MS = 5 * 60 * 1000L

        private fun isOnline(lastSeenAt: String): Boolean {
            val at = runCatching { Instant.parse(lastSeenAt) }.getOrNull() ?: return false
            // A slightly future timestamp (clock skew) still means seen.
            return System.currentTimeMillis() - at.toEpochMilli() <= ONLINE_WINDOW_MS
        }

        /**
         * Never show a raw parser exception to the user: a response the
         * app could not read becomes one short friendly line. Other
         * server error messages pass through as-is.
         */
        private fun friendlyError(message: String): String {
            return if ("JsonObject" in message || "JsonNull" in message ||
                "JsonSyntaxException" in message || "MalformedJsonException" in message
            ) {
                "Could not read the server response — please try again."
            } else {
                message
            }
        }
    }
}
