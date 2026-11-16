package com.openguard.core.network

import com.google.gson.JsonElement
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
    // Phase 2: lost mode + location state reported via heartbeat.
    @SerializedName("lost_mode") val lostMode: Boolean = false,
    @SerializedName("lost_message") val lostMessage: String? = null,
    @SerializedName("location_permission") val locationPermission: String? = null,
    @SerializedName("location_enabled") val locationEnabled: Boolean? = null,
)

data class RegisterDeviceRequest(
    @SerializedName("name") val name: String,
    @SerializedName("model") val model: String?,
    @SerializedName("os_version") val osVersion: String?,
    // Stable per app + device (ANDROID_ID). The server re-links a
    // reinstall to its existing device row instead of duplicating it.
    @SerializedName("hardware_id") val hardwareId: String? = null,
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
    // Phase 2, optional: "all" | "foreground" | "none".
    @SerializedName("location_permission") val locationPermission: String? = null,
    @SerializedName("location_enabled") val locationEnabled: Boolean? = null,
)

data class DeleteAccountRequest(
    @SerializedName("derived_key") val derivedKey: String,
)

// ---- Fast updates: instant push ----

/** Registers/rotates the device's random ntfy topic (see NtfyClient). */
data class PushTopicRequest(
    @SerializedName("push_topic") val pushTopic: String,
)

// ---- Phase 2: remote commands + security events ----

/**
 * One remote command. The user list adds acked_at + result; the pending
 * (device) list omits device_id + status — both shapes map onto this one
 * DTO via nullable fields.
 */
data class CommandDto(
    @SerializedName("id") val id: String,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("action") val action: String,
    // JsonElement (not JsonObject): Gson throws on an explicit JSON null
    // for a JsonObject-typed field even when the Kotlin type is nullable,
    // and the server returns payload: null for ring/lock commands.
    @SerializedName("payload") val payload: JsonElement? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("nonce") val nonce: String? = null,
    // Phase 3: the verify endpoint returns the truncated nonce instead.
    @SerializedName("nonce_truncated") val nonceTruncated: String? = null,
    @SerializedName("issued_at") val issuedAt: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
    @SerializedName("acked_at") val ackedAt: String? = null,
    @SerializedName("result") val result: String? = null,
)

data class CreateCommandRequest(
    @SerializedName("action") val action: String,
    @SerializedName("payload") val payload: Map<String, String>? = null,
    @SerializedName("idempotency_key") val idempotencyKey: String? = null,
    // Phase 3: required for the erase action (single-use step-up token).
    @SerializedName("step_up_token") val stepUpToken: String? = null,
)

data class CommandsResponse(
    @SerializedName("commands") val commands: List<CommandDto>,
)

data class PendingCommandsResponse(
    @SerializedName("commands") val commands: List<CommandDto>,
)

data class AckCommandRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("status") val status: String,
    @SerializedName("result") val result: String? = null,
)

/** One event in the device -> server upload batch. */
data class SecurityEventRequest(
    @SerializedName("event_type") val eventType: String,
    // JsonElement, not JsonObject: same explicit-null parse crash as payload.
    @SerializedName("details") val details: JsonElement? = null,
    @SerializedName("confirmed") val confirmed: Boolean = false,
    @SerializedName("created_at") val createdAt: String? = null,
)

data class SecurityEventsUploadRequest(
    @SerializedName("events") val events: List<SecurityEventRequest>,
)

data class SecurityEventsUploadResponse(
    @SerializedName("accepted") val accepted: Int,
)

data class SecurityEventDto(
    @SerializedName("id") val id: String,
    @SerializedName("event_type") val eventType: String,
    // JsonElement, not JsonObject: same explicit-null parse crash as payload.
    @SerializedName("details") val details: JsonElement? = null,
    @SerializedName("confirmed") val confirmed: Boolean = false,
    @SerializedName("created_at") val createdAt: String? = null,
)

data class SecurityEventsResponse(
    @SerializedName("events") val events: List<SecurityEventDto>,
)

// ---- Phase 3: step-up auth, recovery codes, command verify ----

data class StepUpRequest(
    @SerializedName("derived_key") val derivedKey: String,
)

data class StepUpResponse(
    @SerializedName("step_up_token") val stepUpToken: String,
    @SerializedName("expires_in") val expiresIn: Int,
)

data class StepUpTokenRequest(
    @SerializedName("step_up_token") val stepUpToken: String,
)

data class RecoveryCodesResponse(
    @SerializedName("codes") val codes: List<String>,
)

data class RecoveryCodesStatusResponse(
    @SerializedName("remaining") val remaining: Int,
)

data class RecoverRequest(
    @SerializedName("email") val email: String,
    @SerializedName("code") val code: String,
    @SerializedName("salt") val salt: String,
    @SerializedName("derived_key") val derivedKey: String,
)

data class RevokeOthersResponse(
    @SerializedName("revoked") val revoked: Int,
)

data class AuditDto(
    @SerializedName("id") val id: String,
    @SerializedName("event") val event: String,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("detail") val detail: String?,
)

data class CommandVerifyResponse(
    @SerializedName("command") val command: CommandDto,
    @SerializedName("audit") val audit: List<AuditDto>,
)
