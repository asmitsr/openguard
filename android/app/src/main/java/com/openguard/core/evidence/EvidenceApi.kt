package com.openguard.core.evidence

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the evidence endpoints (see
 * backend/src/routes/evidence.ts). Built from the same Retrofit
 * instance as ApiService (see ApiClient), so it shares the base URL,
 * OkHttp client and auth interceptor.
 *
 * Auth: metadata upload uses the per-device token, passed explicitly
 * via the `Authorization` header (AuthInterceptor never overrides an
 * existing header); listing uses the user token from the interceptor.
 */
interface EvidenceApi {

    /** Device token. 201 with the created clip. Metadata only, no bytes. */
    @POST("api/v1/devices/{id}/evidence")
    suspend fun uploadMetadata(
        @Header("Authorization") deviceAuth: String,
        @Path("id") id: String,
        @Body request: EvidenceMetadataRequest,
    ): Response<EvidenceMetadataResponse>

    /** User token. Newest first. */
    @GET("api/v1/devices/{id}/evidence")
    suspend fun listEvidence(
        @Path("id") id: String,
        @Query("limit") limit: Int,
    ): Response<EvidenceListResponse>
}
