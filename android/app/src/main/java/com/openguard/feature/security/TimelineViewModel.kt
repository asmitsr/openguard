package com.openguard.feature.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openguard.core.common.Result
import com.openguard.core.network.SecurityEventDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TimelineUiState(
    val events: List<SecurityEventDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * Security timeline: the device's security events, newest first.
 * Reuses CommandRepository.listSecurityEvents — the endpoint and the
 * repository already existed; only the dashboard showed events before.
 */
class TimelineViewModel(private val repository: CommandRepository) : ViewModel() {

    private val _state = MutableStateFlow(TimelineUiState())
    val state: StateFlow<TimelineUiState> = _state.asStateFlow()

    fun load(deviceId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            _state.value = when (val result = repository.listSecurityEvents(deviceId)) {
                is Result.Success -> TimelineUiState(events = result.value)
                is Result.Error -> _state.value.copy(isLoading = false, error = result.message)
            }
        }
    }
}
