package com.openguard.feature.geofence

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openguard.R
import com.openguard.core.common.Result
import com.openguard.core.location.GeofenceManager
import com.openguard.core.network.CreateGeofenceRequest
import com.openguard.core.network.GeofenceDto
import com.openguard.core.network.GeofenceEventDto
import com.openguard.core.network.LocationDto
import com.openguard.core.network.UpdateGeofenceRequest
import com.openguard.feature.devices.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GeofenceUiState(
    val geofences: List<GeofenceDto> = emptyList(),
    val events: List<GeofenceEventDto> = emptyList(),
    // Geofence id -> inside (true) / outside (false) / unknown (null),
    // from the engine's cached state. Absent key means unknown too.
    val inside: Map<String, Boolean?> = emptyMap(),
    val lastKnown: LocationDto? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * Geofence screen state. New geofences are centred on the last known
 * location — there is no map-tap picker. After every successful fetch
 * the engine's cached copy is refreshed via GeofenceManager.saveCache,
 * so the on-device engine always watches the latest list.
 */
class GeofenceViewModel(
    private val repository: GeofenceRepository,
    private val deviceRepository: DeviceRepository,
    private val context: Context,
) : ViewModel() {

    companion object {
        const val ACTION_NOTIFY = "notify"
        const val ACTION_LOCK = "lock"
        const val ACTION_LOST_MODE = "lost_mode"
        const val NAME_MAX_LENGTH = 100
        const val RADIUS_MIN_M = 50
        const val RADIUS_MAX_M = 5000
    }

    private val _state = MutableStateFlow(GeofenceUiState())
    val state: StateFlow<GeofenceUiState> = _state.asStateFlow()

    private var deviceId: String? = null

    fun load(deviceId: String) {
        this.deviceId = deviceId
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val geofencesResult = repository.listGeofences(deviceId)
            val eventsResult = repository.listEvents(deviceId)
            val locationResult = deviceRepository.latestLocation(deviceId)
            val lastKnown = (locationResult as? Result.Success)?.value
            _state.value = when (geofencesResult) {
                is Result.Success -> {
                    GeofenceManager.saveCache(context, geofencesResult.value)
                    GeofenceUiState(
                        geofences = geofencesResult.value,
                        events = (eventsResult as? Result.Success)?.value.orEmpty(),
                        inside = geofencesResult.value.associate { geofence ->
                            geofence.id to GeofenceManager.currentState(context, geofence.id)
                        },
                        lastKnown = lastKnown,
                        error = (eventsResult as? Result.Error)?.message,
                    )
                }
                is Result.Error -> _state.value.copy(
                    isLoading = false,
                    lastKnown = lastKnown,
                    error = geofencesResult.message,
                )
            }
        }
    }

    fun refresh() {
        deviceId?.let { load(it) }
    }

    /**
     * Creates a geofence centred on the last known location. Returns
     * true on success; on failure the error lands in state for the
     * screen to show and the form stays open.
     */
    suspend fun create(name: String, radiusM: Int, action: String): Boolean {
        val id = deviceId ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > NAME_MAX_LENGTH) {
            setError(context.getString(R.string.geofence_error_name))
            return false
        }
        if (radiusM !in RADIUS_MIN_M..RADIUS_MAX_M) {
            setError(context.getString(R.string.geofence_error_radius))
            return false
        }
        val center = _state.value.lastKnown
            ?: run {
                val fresh = deviceRepository.latestLocation(id)
                (fresh as? Result.Success)?.value
            }
        if (center == null) {
            setError(context.getString(R.string.geofence_no_location))
            return false
        }
        return when (val result = repository.createGeofence(
            id,
            CreateGeofenceRequest(
                name = trimmed,
                latitude = center.latitude,
                longitude = center.longitude,
                radiusM = radiusM,
                action = action,
            ),
        )) {
            is Result.Success -> {
                load(id)
                true
            }
            is Result.Error -> {
                setError(result.message)
                false
            }
        }
    }

    /** Edits name, radius and action; the centre never moves. */
    suspend fun update(geofenceId: String, name: String, radiusM: Int, action: String): Boolean {
        val id = deviceId ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > NAME_MAX_LENGTH) {
            setError(context.getString(R.string.geofence_error_name))
            return false
        }
        if (radiusM !in RADIUS_MIN_M..RADIUS_MAX_M) {
            setError(context.getString(R.string.geofence_error_radius))
            return false
        }
        return when (val result = repository.updateGeofence(
            id,
            geofenceId,
            UpdateGeofenceRequest(name = trimmed, radiusM = radiusM, action = action),
        )) {
            is Result.Success -> {
                load(id)
                true
            }
            is Result.Error -> {
                setError(result.message)
                false
            }
        }
    }

    fun delete(geofenceId: String) {
        val id = deviceId ?: return
        viewModelScope.launch {
            when (val result = repository.deleteGeofence(id, geofenceId)) {
                is Result.Success -> load(id)
                is Result.Error -> setError(result.message)
            }
        }
    }

    private fun setError(message: String) {
        _state.value = _state.value.copy(error = message)
    }
}
