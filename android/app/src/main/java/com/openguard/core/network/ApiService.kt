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

    @DELETE("api/v1/auth/account")
    suspend fun deleteAccount(@Body request: DeleteAccountRequest): Response<Unit>
}
