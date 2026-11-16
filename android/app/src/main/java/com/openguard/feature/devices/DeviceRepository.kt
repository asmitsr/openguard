package com.openguard.feature.devices

import android.content.Context
import android.os.Build
import android.provider.Settings
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
    private val context: Context,
) {
    /** Id of this phone's device record, if it has been registered. */
    val thisDeviceId: String? get() = tokenStore.deviceId

    suspend fun listDevices(): Result<List<DeviceDto>> = apiCall { api.listDevices() }

    /**
     * Registers THIS phone. The device token is returned once by the
     * backend, so it is stored in encrypted storage immediately.
     * The hardware id (ANDROID_ID, stable across reinstalls for this
     * app) lets the server re-link a reinstall to the existing device
     * row instead of creating a duplicate.
     */
    suspend fun registerThisDevice(name: String): Result<DeviceDto> =
        when (val result = apiCall {
            api.registerDevice(
                RegisterDeviceRequest(
                    name = name.trim(),
                    model = Build.MODEL,
                    osVersion = "Android ${Build.VERSION.RELEASE}",
                    hardwareId = hardwareId(),
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

    /**
     * ANDROID_ID: a 64-bit id, stable for this app on this device across
     * reinstalls (it changes only on factory reset). Null in rare cases
     * (no value provisioned) — the server then registers normally.
     */
    private fun hardwareId(): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    suspend fun latestLocation(deviceId: String): Result<LocationDto?> =
        apiCallNullable { api.latestLocation(deviceId) }

    suspend fun listLocations(
        deviceId: String,
        limit: Int = 50,
        before: String? = null,
    ): Result<LocationHistoryResponse> = apiCall { api.listLocations(deviceId, limit, before) }
}
