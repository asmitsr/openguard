package com.openguard.feature.devices

import android.os.Build
import com.openguard.core.common.Result
import com.openguard.core.network.ApiService
import com.openguard.core.network.DeviceDto
import com.openguard.core.network.LocationDto
import com.openguard.core.network.LocationHistoryResponse
import com.openguard.core.network.RegisterDeviceRequest
import com.openguard.core.network.TokenStore
import com.openguard.core.network.apiCall
import com.openguard.core.network.apiCallNullable
import com.openguard.core.network.apiUnitCall

class DeviceRepository(
    private val api: ApiService,
    private val tokenStore: TokenStore,
) {
    /** Id of this phone's device record, if it has been registered. */
    val thisDeviceId: String? get() = tokenStore.deviceId

    suspend fun listDevices(): Result<List<DeviceDto>> = apiCall { api.listDevices() }

    /**
     * Registers THIS phone. The device token is returned once by the
     * backend, so it is stored in encrypted storage immediately.
     */
    suspend fun registerThisDevice(name: String): Result<DeviceDto> =
        when (val result = apiCall {
            api.registerDevice(
                RegisterDeviceRequest(
                    name = name.trim(),
                    model = Build.MODEL,
                    osVersion = "Android ${Build.VERSION.RELEASE}",
                ),
            )
        }) {
            is Result.Success -> {
                tokenStore.saveDevice(result.value.device.id, result.value.deviceToken)
                Result.Success(result.value.device)
            }
            is Result.Error -> result
        }

    suspend fun deleteDevice(id: String): Result<Unit> =
        when (val result = apiUnitCall { api.deleteDevice(id) }) {
            is Result.Success -> {
                if (tokenStore.deviceId == id) tokenStore.clearDevice()
                Result.Success(Unit)
            }
            is Result.Error -> result
        }

    /**
     * Signs a device out: the server rotates its device token (and revokes
     * other user sessions). If it is this phone, the local device record
     * is cleared too — tracking must be stopped by the caller.
     */
    suspend fun signOutDevice(id: String): Result<Unit> =
        when (val result = apiUnitCall { api.signOutDevice(id) }) {
            is Result.Success -> {
                if (tokenStore.deviceId == id) tokenStore.clearDevice()
                Result.Success(Unit)
            }
            is Result.Error -> result
        }

    suspend fun latestLocation(deviceId: String): Result<LocationDto?> =
        apiCallNullable { api.latestLocation(deviceId) }

    suspend fun listLocations(
        deviceId: String,
        limit: Int = 50,
        before: String? = null,
    ): Result<LocationHistoryResponse> = apiCall { api.listLocations(deviceId, limit, before) }
}
