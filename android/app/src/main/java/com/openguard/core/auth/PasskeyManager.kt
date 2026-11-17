package com.openguard.core.auth

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialProviderException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderException
import androidx.credentials.exceptions.NoCredentialException
import com.openguard.core.common.Result

/**
 * Thin wrapper around Credential Manager for passkeys (WebAuthn).
 *
 * The request JSON comes from the server verbatim, and the response JSON
 * goes back to the server verbatim — the library already returns it in
 * the standard WebAuthn JSON shape, so nothing is hand-built here.
 * Callers treat [CANCELLED] as "user changed their mind", not an error.
 */
class PasskeyManager(private val activity: Activity) {

    companion object {
        const val CANCELLED = "Cancelled"
        const val NO_PROVIDER =
            "No passkey provider on this phone — Google Password Manager " +
                "or another passkey provider is required."
    }

    private val manager = CredentialManager.create(activity)

    /** Creates a passkey; returns the registration response JSON. */
    suspend fun createPasskey(requestJson: String): Result<String> = try {
        val response = manager.createCredential(
            activity,
            CreatePublicKeyCredentialRequest(requestJson),
        )
        val publicKey = response as? CreatePublicKeyCredentialResponse
            ?: return Result.Error("Unexpected credential response.")
        Result.Success(publicKey.registrationResponseJson)
    } catch (e: CreateCredentialCancellationException) {
        Result.Error(CANCELLED, e)
    } catch (e: CreateCredentialProviderException) {
        Result.Error(NO_PROVIDER, e)
    } catch (e: Exception) {
        Result.Error(e.message ?: "Could not create a passkey.", e)
    }

    /** Signs in with a passkey; returns the authentication response JSON. */
    suspend fun getPasskey(requestJson: String): Result<String> = try {
        val response = manager.getCredential(
            activity,
            GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson))),
        )
        val credential = response.credential as? PublicKeyCredential
            ?: return Result.Error("Unexpected credential response.")
        Result.Success(credential.authenticationResponseJson)
    } catch (e: GetCredentialCancellationException) {
        Result.Error(CANCELLED, e)
    } catch (e: GetCredentialProviderException) {
        Result.Error(NO_PROVIDER, e)
    } catch (e: NoCredentialException) {
        Result.Error("No passkey found for this account on this phone.", e)
    } catch (e: Exception) {
        Result.Error(e.message ?: "Could not sign in with a passkey.", e)
    }
}
