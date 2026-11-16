package com.openguard.core.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the OpenGuard API (see backend/src/routes).
 *
 * Auth: user endpoints use the Bearer access token added by AuthInterceptor.
 * Device endpoints (uploadLocations, heartbeat) authenticate with the
 * per-device token instead, passed explicitly via the `Authorization`
 * header — AuthInterceptor never overrides an existing header.
 */
interface ApiService {

    @POST("api/v1/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    /** Returns the user's salt (or a deterministic fake for unknown emails). */
    @GET("api/v1/auth/salt")
    suspend fun getSalt(@Query("email") email: String): Response<SaltResponse>

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<AuthResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("api/v1/devices")
    suspend fun listDevices(): Response<List<DeviceDto>>

    @POST("api/v1/devices")
    suspend fun registerDevice(@Body request: RegisterDeviceRequest): Response<RegisterDeviceResponse>

    @DELETE("api/v1/devices/{id}")
    suspend fun deleteDevice(@Path("id") id: String): Response<Unit>

    @POST("api/v1/devices/{id}/locations")
    suspend fun uploadLocations(
        @Header("Authorization") deviceAuth: String,
        @Path("id") id: String,
        @Body request: UploadLocationsRequest,
    ): Response<UploadLocationsResponse>

    @GET("api/v1/devices/{id}/locations")
    suspend fun listLocations(
        @Path("id") id: String,
        @Query("limit") limit: Int,
        @Query("before") before: String?,
    ): Response<LocationHistoryResponse>

    /** Returns the latest location object, or JSON null when there is none. */
    @GET("api/v1/devices/{id}/latest")
    suspend fun latestLocation(@Path("id") id: String): Response<LocationDto>

    @POST("api/v1/devices/{id}/heartbeat")
    suspend fun heartbeat(
        @Header("Authorization") deviceAuth: String,
        @Path("id") id: String,
        @Body request: HeartbeatRequest,
    ): Response<Unit>

    // ---- Phase 2: remote commands + security events ----

    /** User token. 201 on first issue, 200 with the existing command on replay. */
    @POST("api/v1/devices/{id}/commands")
    suspend fun createCommand(
        @Path("id") id: String,
        @Body request: CreateCommandRequest,
    ): Response<CommandDto>

    /** User token. Newest first. */
    @GET("api/v1/devices/{id}/commands")
    suspend fun listCommands(
        @Path("id") id: String,
        @Query("limit") limit: Int,
    ): Response<CommandsResponse>

    /**
     * Device token. Oldest first. Fetching marks the commands delivered
     * server-side, so callers must ack each one after executing it.
     */
    @GET("api/v1/devices/{id}/commands/pending")
    suspend fun pendingCommands(
        @Header("Authorization") deviceAuth: String,
        @Path("id") id: String,
    ): Response<PendingCommandsResponse>

    /** Device token. 204. */
    @POST("api/v1/commands/{id}/ack")
    suspend fun ackCommand(
        @Header("Authorization") deviceAuth: String,
        @Path("id") id: String,
        @Body request: AckCommandRequest,
    ): Response<Unit>

    /** Device token. 201 with the accepted count. */
    @POST("api/v1/devices/{id}/security-events")
    suspend fun uploadSecurityEvents(
        @Header("Authorization") deviceAuth: String,
        @Path("id") id: String,
        @Body request: SecurityEventsUploadRequest,
    ): Response<SecurityEventsUploadResponse>

    /** User token. Newest first. */
    @GET("api/v1/devices/{id}/security-events")
    suspend fun listSecurityEvents(
        @Path("id") id: String,
        @Query("limit") limit: Int,
    ): Response<SecurityEventsResponse>

    @DELETE("api/v1/auth/account")
    suspend fun deleteAccount(@Body request: DeleteAccountRequest): Response<Unit>

    // ---- Phase 3: step-up auth, recovery codes, erase verify, sign-out ----

    /** User token. Returns a single-use step-up token (10 minutes). */
    @POST("api/v1/auth/step-up")
    suspend fun stepUp(@Body request: StepUpRequest): Response<StepUpResponse>

    /** User token. Revokes all other sessions for this user. */
    @POST("api/v1/auth/sessions/revoke-others")
    suspend fun revokeOtherSessions(): Response<RevokeOthersResponse>

    /** User token + step-up token. Codes are shown once. */
    @POST("api/v1/auth/recovery-codes/generate")
    suspend fun generateRecoveryCodes(@Body request: StepUpTokenRequest): Response<RecoveryCodesResponse>

    /** User token. */
    @GET("api/v1/auth/recovery-codes")
    suspend fun recoveryCodesStatus(): Response<RecoveryCodesStatusResponse>

    /** NO auth. Resets the password with a recovery code. 204. */
    @POST("api/v1/auth/recover")
    suspend fun recover(@Body request: RecoverRequest): Response<Unit>

    /** User token. The command plus its audit timeline. */
    @GET("api/v1/commands/{id}")
    suspend fun verifyCommand(@Path("id") id: String): Response<CommandVerifyResponse>

    /** User token. Rotates the device token; also revokes other sessions. 204. */
    @POST("api/v1/devices/{id}/sign-out")
    suspend fun signOutDevice(@Path("id") id: String): Response<Unit>
}
