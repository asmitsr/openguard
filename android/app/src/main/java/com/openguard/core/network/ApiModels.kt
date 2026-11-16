package com.openguard.core.network

import com.google.gson.annotations.SerializedName

// DTOs mirror the backend API contract exactly (see backend/src/routes).
// Field names use @SerializedName because the API speaks snake_case.

data class UserDto(
    @SerializedName("id") val id: String,
    @SerializedName("email") val email: String,
)

// Auth requests carry the client-derived key, never the raw password:
// the client derives a 32-byte key (PBKDF2-SHA256, 600k iterations) from
// password + salt and sends only its hex form (see PasswordKdf).
data class RegisterRequest(
    @SerializedName("email") val email: String,
    @SerializedName("salt") val salt: String,
    @SerializedName("derived_key") val derivedKey: String,
)

data class LoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("derived_key") val derivedKey: String,
)

data class SaltResponse(
    @SerializedName("salt") val salt: String,
)

data class RefreshRequest(
    @SerializedName("refresh_token") val refreshToken: String,
)

/** Covers login + register (both return `user`) and refresh (no `user`). */
data class AuthResponse(
    @SerializedName("user") val user: UserDto?,
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
)

data class DeviceDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("model") val model: String?,
    @SerializedName("os_version") val osVersion: String?,
    @SerializedName("last_seen_at") val lastSeenAt: String?,
    @SerializedName("battery_level") val batteryLevel: Int?,
    @SerializedName("battery_charging") val batteryCharging: Boolean?,
    @SerializedName("network_type") val networkType: String?,
    @SerializedName("created_at") val createdAt: String?,
)

data class RegisterDeviceRequest(
    @SerializedName("name") val name: String,
    @SerializedName("model") val model: String?,
    @SerializedName("os_version") val osVersion: String?,
)

/** `device_token` is shown once by the backend — store it immediately. */
data class RegisterDeviceResponse(
    @SerializedName("device") val device: DeviceDto,
    @SerializedName("device_token") val deviceToken: String,
)

data class LocationPointDto(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy") val accuracy: Float,
    @SerializedName("recorded_at") val recordedAt: String,
    @SerializedName("provider") val provider: String?,
)

data class LocationDto(
    @SerializedName("id") val id: String,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy") val accuracy: Float,
    @SerializedName("recorded_at") val recordedAt: String,
    @SerializedName("provider") val provider: String?,
)

data class UploadLocationsRequest(
    @SerializedName("locations") val locations: List<LocationPointDto>,
)

data class UploadLocationsResponse(
    @SerializedName("accepted") val accepted: Int,
)

data class LocationHistoryResponse(
    @SerializedName("locations") val locations: List<LocationDto>,
    @SerializedName("next_before") val nextBefore: String?,
)

data class HeartbeatRequest(
    @SerializedName("battery_level") val batteryLevel: Int,
    @SerializedName("battery_charging") val batteryCharging: Boolean,
    @SerializedName("network_type") val networkType: String?,
)

data class DeleteAccountRequest(
    @SerializedName("derived_key") val derivedKey: String,
)
