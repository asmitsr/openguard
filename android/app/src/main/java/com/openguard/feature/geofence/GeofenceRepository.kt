package com.openguard.feature.geofence

import com.openguard.core.common.Result
import com.openguard.core.network.ApiService
import com.openguard.core.network.CreateGeofenceRequest
import com.openguard.core.network.GeofenceDto
import com.openguard.core.network.GeofenceEventDto
import com.openguard.core.network.UpdateGeofenceRequest
import com.openguard.core.network.apiCall
import com.openguard.core.network.apiUnitCall

/** User-token API for geofences + their enter/exit events. */
class GeofenceRepository(private val api: ApiService) {

    suspend fun listGeofences(deviceId: String): Result<List<GeofenceDto>> =
        when (val result = apiCall { api.listGeofences(deviceId) }) {
            is Result.Success -> Result.Success(result.value.geofences)
            is Result.Error -> result
        }

    suspend fun createGeofence(
        deviceId: String,
        request: CreateGeofenceRequest,
    ): Result<GeofenceDto> =
        when (val result = apiCall { api.createGeofence(deviceId, request) }) {
            is Result.Success -> Result.Success(result.value.geofence)
            is Result.Error -> result
        }

    suspend fun updateGeofence(
        deviceId: String,
        geofenceId: String,
        request: UpdateGeofenceRequest,
    ): Result<GeofenceDto> =
        when (val result = apiCall { api.updateGeofence(deviceId, geofenceId, request) }) {
            is Result.Success -> Result.Success(result.value.geofence)
            is Result.Error -> result
        }

    suspend fun deleteGeofence(deviceId: String, geofenceId: String): Result<Unit> =
        apiUnitCall { api.deleteGeofence(deviceId, geofenceId) }

    suspend fun listEvents(deviceId: String, limit: Int = 50): Result<List<GeofenceEventDto>> =
        when (val result = apiCall { api.listGeofenceEvents(deviceId, limit) }) {
            is Result.Success -> Result.Success(result.value.events)
            is Result.Error -> result
        }
}
