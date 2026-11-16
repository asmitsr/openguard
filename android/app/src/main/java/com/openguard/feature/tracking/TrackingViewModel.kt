package com.openguard.feature.tracking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openguard.core.common.Result
import com.openguard.core.network.LocationDto
import com.openguard.feature.devices.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MapUiState(
    val latest: LocationDto? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class HistoryUiState(
    val locations: List<LocationDto> = emptyList(),
    val nextBefore: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** One ViewModel for the map + history screens (same repository). */
class TrackingViewModel(private val repository: DeviceRepository) : ViewModel() {

    private val _mapState = MutableStateFlow(MapUiState())
    val mapState: StateFlow<MapUiState> = _mapState.asStateFlow()

    private val _historyState = MutableStateFlow(HistoryUiState())
    val historyState: StateFlow<HistoryUiState> = _historyState.asStateFlow()

    fun loadLatest(deviceId: String) {
        viewModelScope.launch {
            _mapState.value = MapUiState(isLoading = true)
            _mapState.value = when (val result = repository.latestLocation(deviceId)) {
                is Result.Success -> MapUiState(latest = result.value)
                is Result.Error -> MapUiState(error = result.message)
            }
        }
    }

    fun loadHistory(deviceId: String, loadMore: Boolean = false) {
        viewModelScope.launch {
            val before = if (loadMore) _historyState.value.nextBefore else null
            if (loadMore && before == null) return@launch
            _historyState.value = _historyState.value.copy(isLoading = true, error = null)
            when (val result = repository.listLocations(deviceId, before = before)) {
                is Result.Success -> {
                    val existing =
                        if (loadMore) _historyState.value.locations else emptyList()
                    _historyState.value = HistoryUiState(
                        locations = existing + result.value.locations,
                        nextBefore = result.value.nextBefore,
                    )
                }
                is Result.Error -> {
                    _historyState.value =
                        _historyState.value.copy(isLoading = false, error = result.message)
                }
            }
        }
    }
}
