package com.openguard.core.network

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Tokens + account state in EncryptedSharedPreferences (AES256-SIV keys,
 * AES256-GCM values, master key in Android Keystore).
 *
 * Holds: user access/refresh tokens, this device's id + device token,
 * and the signed-in email for display. Nothing else is persisted here.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            FILE_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        private set(value) = prefs.edit { putString(KEY_ACCESS_TOKEN, value) }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        private set(value) = prefs.edit { putString(KEY_REFRESH_TOKEN, value) }

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        private set(value) = prefs.edit { putString(KEY_DEVICE_ID, value) }

    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        private set(value) = prefs.edit { putString(KEY_DEVICE_TOKEN, value) }

    var userEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)
        set(value) = prefs.edit { putString(KEY_USER_EMAIL, value) }

    val isLoggedIn: Boolean get() = accessToken != null

    /** True when this phone has been registered as a device. */
    val hasDevice: Boolean get() = deviceId != null && deviceToken != null

    fun saveTokens(access: String, refresh: String) {
        prefs.edit {
            putString(KEY_ACCESS_TOKEN, access)
            putString(KEY_REFRESH_TOKEN, refresh)
        }
    }

    fun saveDevice(id: String, token: String) {
        prefs.edit {
            putString(KEY_DEVICE_ID, id)
            putString(KEY_DEVICE_TOKEN, token)
        }
    }

    fun clearDevice() {
        prefs.edit {
            remove(KEY_DEVICE_ID)
            remove(KEY_DEVICE_TOKEN)
        }
    }

    /** Clears everything: used on logout and account deletion. */
    fun clearAll() {
        prefs.edit { clear() }
    }

    companion object {
        private const val FILE_NAME = "openguard_tokens"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_USER_EMAIL = "user_email"
    }
}
