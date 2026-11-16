package com.openguard.core.evidence

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Google Drive authorization for evidence uploads, via AppAuth
 * (RFC 8252 OAuth 2.0 for Native Apps): authorization code + PKCE in
 * the system browser (Custom Tabs), no client secret — a public client
 * cannot keep one. The refresh token is stored ONLY on this device, in
 * EncryptedSharedPreferences; it is never logged and never sent to our
 * server. Background uploads refresh the access token directly against
 * Google's token endpoint, with no Play Services involvement.
 *
 * Why AppAuth and not Google Sign-In: Google Sign-In (Credential
 * Manager / Play Services) only proves identity — it cannot grant the
 * Drive scope, and its tokens are managed by Play Services, not by us.
 * AppAuth gives us the refresh token, so uploads keep working in the
 * background while Lost Mode is on.
 *
 * SETUP (user action, see the workstream report for the full guide):
 * create a Google Cloud OAuth client of type "Desktop app", enable the
 * Drive API, and paste the client ID into GOOGLE_OAUTH_CLIENT_ID below.
 * Until then [isConfigured] is false and Drive stays disconnected —
 * clips simply stay on the device.
 */
object DriveAuth {

    /**
     * PLACEHOLDER — NOT A REAL CLIENT ID. Paste the OAuth client ID
     * from Google Cloud Console (Credentials, OAuth client of type
     * "Desktop app", e.g. "123456789012-abc...xyz.apps.googleusercontent.com").
     * A client ID is a PUBLIC identifier, not a secret: it is safe in
     * source. The client SECRET is never needed (PKCE public client) —
     * do not paste it anywhere in this app.
     */
    const val GOOGLE_OAUTH_CLIENT_ID = "REPLACE_ME.apps.googleusercontent.com"

    /** Redirect URI: custom scheme = package name, per Google's native-app docs. */
    private const val REDIRECT_URI = "com.openguard:/oauth2redirect"

    /** Least privilege: only files this app creates. Never the full drive scope. */
    private const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    private const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"

    private const val PREFS_FILE = "openguard_drive_auth"
    private const val KEY_AUTH_STATE = "auth_state_json"

    /** True once the real OAuth client ID has been pasted in. */
    fun isConfigured(): Boolean =
        GOOGLE_OAUTH_CLIENT_ID != "REPLACE_ME.apps.googleusercontent.com"

    /** True when a refresh token is stored (Drive is connected). */
    fun isConnected(context: Context): Boolean =
        loadState(context)?.refreshToken != null

    /**
     * Builds the sign-in intent for Settings to launch (via an
     * ActivityResultLauncher). Null when the client ID is not
     * configured yet. The result intent goes to [handleSignInResponse].
     */
    fun createSignInIntent(context: Context): Intent? {
        if (!isConfigured()) return null
        val config = AuthorizationServiceConfiguration(
            Uri.parse(AUTH_ENDPOINT),
            Uri.parse(TOKEN_ENDPOINT),
        )
        val request = AuthorizationRequest.Builder(
            config,
            GOOGLE_OAUTH_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI),
        )
            .setScopes("openid", "email", "profile", SCOPE_DRIVE_FILE)
            .setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
            // access_type=offline: Google returns a refresh token, so
            // uploads keep working in the background. prompt=consent:
            // Google re-issues the refresh token even if the account
            // already granted this app before.
            .setAdditionalParameters(
                mapOf("access_type" to "offline", "prompt" to "consent"),
            )
            .build()
        val service = AuthorizationService(context.applicationContext)
        return try {
            service.getAuthorizationRequestIntent(request)
        } finally {
            service.dispose()
        }
    }

    /**
     * Completes sign-in: exchanges the authorization code for tokens
     * (PKCE verifier included) and persists the AuthState encrypted.
     * True on success.
     */
    suspend fun handleSignInResponse(context: Context, data: Intent?): Boolean {
        if (data == null) return false
        if (AuthorizationException.fromIntent(data) != null) return false
        val response = AuthorizationResponse.fromIntent(data) ?: return false
        val service = AuthorizationService(context.applicationContext)
        return try {
            val tokenResponse = suspendCancellableCoroutine<TokenResponse?> { cont ->
                service.performTokenRequest(response.createTokenExchangeRequest()) { resp, _ ->
                    cont.resume(resp)
                }
            } ?: return false
            saveState(context, AuthState(response, tokenResponse, null))
            true
        } finally {
            service.dispose()
        }
    }

    /**
     * A fresh access token for Drive calls, refreshing in the
     * background when needed. The refreshed AuthState is persisted, so
     * token rotation survives process death.
     */
    suspend fun accessToken(context: Context): DriveToken {
        val state = loadState(context) ?: return DriveToken.NotConnected
        if (state.refreshToken == null && state.accessToken == null) {
            return DriveToken.NotConnected
        }
        val service = AuthorizationService(context.applicationContext)
        return try {
            suspendCancellableCoroutine { cont ->
                state.performActionWithFreshTokens(service) { accessToken, _, ex ->
                    when {
                        accessToken != null -> {
                            saveState(context, state)
                            cont.resume(DriveToken.Ok(accessToken))
                        }
                        ex?.error == "invalid_grant" -> {
                            // Grant revoked (or expired): truly disconnected.
                            clearState(context)
                            EvidencePrefs.clearDriveFolderId(context)
                            cont.resume(DriveToken.NotConnected)
                        }
                        else -> cont.resume(DriveToken.RefreshFailed)
                    }
                }
            }
        } finally {
            service.dispose()
        }
    }

    /**
     * Disconnects Drive: revokes the grant at Google (best-effort) and
     * deletes the stored tokens and folder id. Clips already in Drive
     * stay there — revoking only stops future uploads.
     */
    suspend fun disconnect(context: Context) {
        val state = loadState(context)
        val token = state?.refreshToken ?: state?.accessToken
        clearState(context)
        EvidencePrefs.clearDriveFolderId(context)
        if (token != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val client = OkHttpClient.Builder()
                        .callTimeout(15, TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder()
                        .url(REVOKE_ENDPOINT)
                        .post(FormBody.Builder().add("token", token).build())
                        .build()
                    client.newCall(request).execute().close()
                }
            }
        }
    }

    private fun encryptedPrefs(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            PREFS_FILE,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    /** Null when never connected, or when the Keystore is unavailable (before first unlock). */
    private fun loadState(context: Context): AuthState? = try {
        encryptedPrefs(context).getString(KEY_AUTH_STATE, null)?.let {
            AuthState.jsonDeserialize(it)
        }
    } catch (e: Exception) {
        null
    }

    private fun saveState(context: Context, state: AuthState) {
        encryptedPrefs(context).edit { putString(KEY_AUTH_STATE, state.jsonSerializeString()) }
    }

    private fun clearState(context: Context) {
        encryptedPrefs(context).edit { remove(KEY_AUTH_STATE) }
    }
}

/** Result of [DriveAuth.accessToken]. */
sealed interface DriveToken {
    /** A fresh access token, ready for Drive calls. */
    data class Ok(val token: String) : DriveToken

    /** No stored grant: Drive was never connected, or was revoked. */
    object NotConnected : DriveToken

    /** A grant exists but the refresh failed (transient): retry later. */
    object RefreshFailed : DriveToken
}
