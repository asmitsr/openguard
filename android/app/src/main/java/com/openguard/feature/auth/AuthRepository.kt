package com.openguard.feature.auth

import com.openguard.core.common.Result
import com.openguard.core.network.ApiService
import com.openguard.core.network.DeleteAccountRequest
import com.openguard.core.network.LoginRequest
import com.openguard.core.network.RegisterRequest
import com.openguard.core.network.TokenStore
import com.openguard.core.network.apiCall
import com.openguard.core.network.apiUnitCall
import com.openguard.core.security.PasswordKdf

class AuthRepository(
    private val api: ApiService,
    private val tokenStore: TokenStore,
) {
    /**
     * Login: fetch the salt, derive the key client-side, send only the
     * derived key. The raw password never leaves the phone.
     */
    suspend fun login(email: String, password: String): Result<Unit> =
        when (val salt = apiCall { api.getSalt(email) }) {
            is Result.Error -> salt
            is Result.Success -> {
                val derivedKey = PasswordKdf.deriveKeyHex(password, salt.value.salt)
                when (val result = apiCall { api.login(LoginRequest(email, derivedKey)) }) {
                    is Result.Success -> {
                        tokenStore.saveTokens(result.value.accessToken, result.value.refreshToken)
                        tokenStore.userEmail = result.value.user?.email ?: email
                        Result.Success(Unit)
                    }
                    is Result.Error -> result
                }
            }
        }

    /** Register: fresh random salt, derive client-side, send salt + key. */
    suspend fun register(email: String, password: String): Result<Unit> {
        if (password.length < 8) {
            return Result.Error("Password must be at least 8 characters.")
        }
        val saltHex = PasswordKdf.toHex(PasswordKdf.randomSalt())
        val derivedKey = PasswordKdf.deriveKeyHex(password, saltHex)
        return when (val result = apiCall { api.register(RegisterRequest(email, saltHex, derivedKey)) }) {
            is Result.Success -> {
                tokenStore.saveTokens(result.value.accessToken, result.value.refreshToken)
                tokenStore.userEmail = result.value.user?.email ?: email
                Result.Success(Unit)
            }
            is Result.Error -> result
        }
    }

    /**
     * Best-effort server logout; local tokens are always cleared, even
     * when the server call fails (a stale local token must never survive).
     */
    suspend fun logout(): Result<Unit> {
        try {
            api.logout()
        } catch (e: Exception) {
            // Ignored: local logout still proceeds.
        }
        tokenStore.clearAll()
        return Result.Success(Unit)
    }

    /** Account deletion: same derivation as login, via the stored email. */
    suspend fun deleteAccount(password: String): Result<Unit> {
        val email = tokenStore.userEmail ?: return Result.Error("Not signed in.")
        return when (val salt = apiCall { api.getSalt(email) }) {
            is Result.Error -> salt
            is Result.Success -> {
                val derivedKey = PasswordKdf.deriveKeyHex(password, salt.value.salt)
                when (val result = apiUnitCall { api.deleteAccount(DeleteAccountRequest(derivedKey)) }) {
                    is Result.Success -> {
                        tokenStore.clearAll()
                        Result.Success(Unit)
                    }
                    is Result.Error -> result
                }
            }
        }
    }
}
