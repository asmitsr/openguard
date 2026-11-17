package com.openguard.feature.auth

import android.app.Activity
import com.google.gson.JsonParser
import com.openguard.core.auth.PasskeyManager
import com.openguard.core.common.Result
import com.openguard.core.network.ApiService
import com.openguard.core.network.DeleteAccountRequest
import com.openguard.core.network.LoginRequest
import com.openguard.core.network.PasskeyLoginChallengeRequest
import com.openguard.core.network.PasskeyLoginVerifyRequest
import com.openguard.core.network.PasskeyRegisterResponse
import com.openguard.core.network.PasskeyRegisterVerifyRequest
import com.openguard.core.network.RecoverRequest
import com.openguard.core.network.RegisterRequest
import com.openguard.core.network.StepUpRequest
import com.openguard.core.network.StepUpTokenRequest
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

    /**
     * Step-up auth: re-proves the password for a sensitive action (erase,
     * recovery codes). Returns the single-use step-up token (10 minutes).
     */
    suspend fun stepUp(password: String): Result<String> {
        val email = tokenStore.userEmail ?: return Result.Error("Not signed in.")
        return when (val salt = apiCall { api.getSalt(email) }) {
            is Result.Error -> salt
            is Result.Success -> {
                val derivedKey = PasswordKdf.deriveKeyHex(password, salt.value.salt)
                when (val result = apiCall { api.stepUp(StepUpRequest(derivedKey)) }) {
                    is Result.Success -> Result.Success(result.value.stepUpToken)
                    is Result.Error -> result
                }
            }
        }
    }

    /** Generates a fresh set of recovery codes (invalidates the old ones). */
    suspend fun generateRecoveryCodes(password: String): Result<List<String>> =
        when (val stepUp = stepUp(password)) {
            is Result.Error -> stepUp
            is Result.Success -> {
                when (val result = apiCall { api.generateRecoveryCodes(StepUpTokenRequest(stepUp.value)) }) {
                    is Result.Success -> Result.Success(result.value.codes)
                    is Result.Error -> result
                }
            }
        }

    /** How many unused recovery codes are left. */
    suspend fun recoveryCodesRemaining(): Result<Int> =
        when (val result = apiCall { api.recoveryCodesStatus() }) {
            is Result.Success -> Result.Success(result.value.remaining)
            is Result.Error -> result
        }

    /**
     * Password reset with a recovery code (no auth). Fresh random salt,
     * key derived from the NEW password — same 8+ check as register.
     */
    suspend fun recover(email: String, code: String, newPassword: String): Result<Unit> {
        if (newPassword.length < 8) {
            return Result.Error("Password must be at least 8 characters.")
        }
        val saltHex = PasswordKdf.toHex(PasswordKdf.randomSalt())
        val derivedKey = PasswordKdf.deriveKeyHex(newPassword, saltHex)
        return apiUnitCall { api.recover(RecoverRequest(email, code, saltHex, derivedKey)) }
    }

    /** Signs out every other session. Returns how many were revoked. */
    suspend fun revokeOtherSessions(): Result<Int> =
        when (val result = apiCall { api.revokeOtherSessions() }) {
            is Result.Success -> Result.Success(result.value.revoked)
            is Result.Error -> result
        }

    // ---- Passkeys (#9) ----

    /**
     * Passkey sign-in: challenge, Credential Manager prompt, verify.
     * Retries ONCE with a fresh challenge when verify fails — the
     * challenge may have been issued by another server isolate. A
     * cancellation or a missing provider is returned at once, never
     * retried. Tokens are saved exactly like password login.
     */
    suspend fun passkeyLogin(email: String, activity: Activity): Result<Unit> {
        var lastError: Result.Error? = null
        repeat(2) {
            when (val challenge = apiCall { api.passkeyLoginChallenge(PasskeyLoginChallengeRequest(email)) }) {
                is Result.Error -> return challenge
                is Result.Success -> {
                    val responseJson = when (val credential =
                        PasskeyManager(activity).getPasskey(challenge.value.toString())) {
                        is Result.Error -> return credential
                        is Result.Success -> credential.value
                    }
                    when (val verify = apiCall {
                        api.passkeyLoginVerify(
                            PasskeyLoginVerifyRequest(email, JsonParser.parseString(responseJson)),
                        )
                    }) {
                        is Result.Success -> {
                            tokenStore.saveTokens(verify.value.accessToken, verify.value.refreshToken)
                            tokenStore.userEmail = verify.value.user?.email ?: email
                            return Result.Success(Unit)
                        }
                        is Result.Error -> lastError = verify
                    }
                }
            }
        }
        return lastError ?: Result.Error("Passkey sign-in failed.")
    }

    /**
     * Passkey registration: one attempt only (challenge, Credential
     * Manager prompt, verify). The retry-once lives in
     * PasskeyViewModel, which owns the create flow's UI state.
     */
    suspend fun passkeyRegister(name: String, activity: Activity): Result<PasskeyRegisterResponse> {
        val challenge = apiCall { api.passkeyRegisterChallenge() }
        if (challenge is Result.Error) return challenge
        val responseJson = when (val credential =
            PasskeyManager(activity).createPasskey(challenge.value.toString())) {
            is Result.Error -> return credential
            is Result.Success -> credential.value
        }
        return apiCall {
            api.passkeyRegisterVerify(
                PasskeyRegisterVerifyRequest(name, JsonParser.parseString(responseJson)),
            )
        }
    }
}
