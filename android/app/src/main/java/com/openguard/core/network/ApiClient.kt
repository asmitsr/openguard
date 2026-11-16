package com.openguard.core.network

import com.openguard.core.common.AppConfig
import com.openguard.core.common.Result
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

/** Builds the Retrofit [ApiService] with auth interceptor + token refresh. */
class ApiClient(tokenStore: TokenStore) {

    val api: ApiService

    init {
        val baseUrl = AppConfig.API_BASE_URL
        require(!baseUrl.contains("<") && !baseUrl.contains(">")) {
            "Set AppConfig.API_BASE_URL to the real deployed Worker URL " +
                "(e.g. https://openguard-api.<subdomain>.workers.dev/) before running."
        }
        // Plain client for token refresh: no interceptor, no authenticator,
        // so a refresh can never trigger another refresh.
        val refreshApi: RefreshApi = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(plainClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RefreshApi::class.java)

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenStore))
            .authenticator(
                TokenAuthenticator(tokenStore) { refreshToken ->
                    refreshApi.refresh(RefreshRequest(refreshToken)).execute()
                        .takeIf { it.isSuccessful }?.body()
                },
            )
            .build()

        api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    private fun plainClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Non-suspend refresh: Authenticator runs on an OkHttp thread. */
    private interface RefreshApi {
        @POST("api/v1/auth/refresh")
        fun refresh(@Body request: RefreshRequest): Call<AuthResponse>
    }
}

// ---- Response -> Result helpers (shared by all repositories) ----

/** Backend errors are `{"error": "message"}`. */
fun parseErrorBody(json: String?, fallback: String = "Request failed"): String {
    if (json.isNullOrBlank()) return fallback
    return try {
        JSONObject(json).optString("error").ifBlank { fallback }
    } catch (e: JSONException) {
        fallback
    }
}

private fun <T> Response<T>.asError(): Result.Error =
    Result.Error(parseErrorBody(errorBody()?.string(), "Request failed (${code()})"))

/** Call expecting a JSON body; a null body on success is an error. */
suspend fun <T> apiCall(block: suspend () -> Response<T>): Result<T> = try {
    val response = block()
    if (response.isSuccessful) {
        val body = response.body()
        if (body != null) Result.Success(body) else Result.Error("Empty response from server")
    } else {
        response.asError()
    }
} catch (e: IOException) {
    Result.Error("Could not reach the server. Check your connection.", e)
} catch (e: Exception) {
    Result.Error(e.message ?: "Unexpected error", e)
}

/** Call whose success body may legitimately be JSON null (GET latest). */
suspend fun <T> apiCallNullable(block: suspend () -> Response<T>): Result<T?> = try {
    val response = block()
    if (response.isSuccessful) Result.Success(response.body()) else response.asError()
} catch (e: IOException) {
    Result.Error("Could not reach the server. Check your connection.", e)
} catch (e: Exception) {
    Result.Error(e.message ?: "Unexpected error", e)
}

/** Call expecting 204 No Content (logout, delete, heartbeat). */
suspend fun apiUnitCall(block: suspend () -> Response<Unit>): Result<Unit> = try {
    val response = block()
    if (response.isSuccessful) Result.Success(Unit) else response.asError()
} catch (e: IOException) {
    Result.Error("Could not reach the server. Check your connection.", e)
} catch (e: Exception) {
    Result.Error(e.message ?: "Unexpected error", e)
}
