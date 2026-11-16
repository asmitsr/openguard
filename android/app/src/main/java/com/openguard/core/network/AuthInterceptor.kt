package com.openguard.core.network

import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Adds `Authorization: Bearer <access token>` to requests that do not
 * already carry an Authorization header (device-token calls pass their own).
 */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)
        val token = tokenStore.accessToken ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build(),
        )
    }
}

/**
 * Refreshes the user access token on a 401 and retries once.
 *
 * [refreshCall] is a plain synchronous refresh (no interceptor/authenticator
 * on its client, so it cannot recurse). Only retries requests that failed
 * with the *user* token: device-token calls (upload/heartbeat) must never
 * be retried with a user token, so they return null here.
 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val refreshCall: (refreshToken: String) -> AuthResponse?,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null // never loop
        val requestToken = response.request.header("Authorization")
        val userToken = tokenStore.accessToken
        if (userToken == null || requestToken != "Bearer $userToken") return null
        val refreshToken = tokenStore.refreshToken ?: return null
        val fresh = try {
            refreshCall(refreshToken)
        } catch (e: Exception) {
            null
        } ?: return null
        tokenStore.saveTokens(fresh.accessToken, fresh.refreshToken)
        return response.request.newBuilder()
            .header("Authorization", "Bearer ${fresh.accessToken}")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
