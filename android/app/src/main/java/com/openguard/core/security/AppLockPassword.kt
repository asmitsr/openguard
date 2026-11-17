package com.openguard.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.MessageDigest

/**
 * App lock password storage: Pattern, PIN, or Mixed (pattern + PIN).
 *
 * Only salted SHA-256 hashes are stored, never plaintext. Each secret
 * gets its own 16-byte random salt (see PasswordKdf.randomSalt); the
 * hash input is the salt bytes followed by the secret's UTF-8 bytes.
 * A pattern's secret string is its cells joined by commas, e.g.
 * "0,3,6,7"; a PIN's secret string is the digit string. Mixed stores
 * one salt+hash pair per secret. Verification compares hashes with
 * MessageDigest.isEqual (constant time). Secrets, hashes and salts
 * are never logged.
 *
 * Attempt state (failure count + lockout-until timestamp) lives in a
 * separate plain SharedPreferences file: counts and timestamps are
 * not secrets, and plain prefs survive the Keystore being unavailable
 * before first unlock after reboot, so lockout still works then.
 *
 * verifyPin / verifyPattern do NOT touch attempt state: callers call
 * recordFailure on a wrong attempt and resetFailures on a correct one.
 */
object AppLockPassword {

    const val MIN_PATTERN_CELLS = 4
    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 8
    const val MAX_ATTEMPTS = 5
    const val BASE_LOCKOUT_MS = 30_000L
    const val MAX_LOCKOUT_MS = 300_000L

    fun isSet(context: Context): Boolean = getType(context) != null

    fun getType(context: Context): AppLockType? =
        when (prefs(context).getString(KEY_TYPE, null)) {
            TYPE_PATTERN -> AppLockType.PATTERN
            TYPE_PIN -> AppLockType.PIN
            TYPE_MIXED -> AppLockType.MIXED
            else -> null
        }

    /** 4-8; default 4 when unset. */
    fun pinLength(context: Context): Int =
        prefs(context).getInt(KEY_PIN_LENGTH, MIN_PIN_LENGTH)
            .coerceIn(MIN_PIN_LENGTH, MAX_PIN_LENGTH)

    /** Validates 4-8 ASCII digits; false if invalid. Replaces any pattern. */
    fun setPin(context: Context, pin: String): Boolean {
        if (!isValidPin(pin)) return false
        prefs(context).edit {
            putString(KEY_TYPE, TYPE_PIN)
            putInt(KEY_PIN_LENGTH, pin.length)
            putSecret(KEY_PIN_SALT, KEY_PIN_HASH, pin)
            remove(KEY_PATTERN_SALT)
            remove(KEY_PATTERN_HASH)
            // A new password invalidates the duress PIN (it must match
            // the app PIN's length): the owner sets it again.
            remove(KEY_DURESS_SALT)
            remove(KEY_DURESS_HASH)
        }
        return true
    }

    /** Validates >= 4 distinct cells, each 0-8; false if invalid. Replaces any PIN. */
    fun setPattern(context: Context, cells: List<Int>): Boolean {
        if (!isValidPattern(cells)) return false
        prefs(context).edit {
            putString(KEY_TYPE, TYPE_PATTERN)
            putSecret(KEY_PATTERN_SALT, KEY_PATTERN_HASH, patternSecret(cells))
            remove(KEY_PIN_SALT)
            remove(KEY_PIN_HASH)
            remove(KEY_DURESS_SALT)
            remove(KEY_DURESS_HASH)
        }
        return true
    }

    /** Validates both secrets; false if either is invalid. */
    fun setMixed(context: Context, cells: List<Int>, pin: String): Boolean {
        if (!isValidPattern(cells) || !isValidPin(pin)) return false
        prefs(context).edit {
            putString(KEY_TYPE, TYPE_MIXED)
            putInt(KEY_PIN_LENGTH, pin.length)
            putSecret(KEY_PATTERN_SALT, KEY_PATTERN_HASH, patternSecret(cells))
            putSecret(KEY_PIN_SALT, KEY_PIN_HASH, pin)
            remove(KEY_DURESS_SALT)
            remove(KEY_DURESS_HASH)
        }
        return true
    }

    /**
     * Duress PIN: a second PIN the owner enters when coerced (someone
     * forces them to unlock the phone). Stored the same way as the app
     * lock PIN (own salt+hash in this file). Rules, enforced here:
     * the app lock type must be PIN or MIXED (the duress PIN is
     * entered on the PIN pad, so a pattern-only lock has nowhere to
     * enter it); it must be the SAME LENGTH as the app lock PIN (the
     * decoy pad submits at the app PIN's length); and it must DIFFER
     * from the app lock PIN. Changing the app lock password clears it
     * (see setPin/setPattern/setMixed); remove() clears the whole
     * file, duress included. The duress PIN NEVER unlocks the phone —
     * entering it triggers the silent duress response instead.
     */
    fun isDuressSet(context: Context): Boolean =
        prefs(context).getString(KEY_DURESS_HASH, null) != null

    /** False if invalid, the type is not PIN/MIXED, the length differs, or it matches the app PIN. */
    fun setDuressPin(context: Context, pin: String): Boolean {
        if (!isValidPin(pin)) return false
        if (getType(context) !in setOf(AppLockType.PIN, AppLockType.MIXED)) return false
        if (pin.length != pinLength(context)) return false
        if (verifyPin(context, pin)) return false
        prefs(context).edit { putSecret(KEY_DURESS_SALT, KEY_DURESS_HASH, pin) }
        return true
    }

    /** False if not set. Does not touch attempt state. */
    fun verifyDuressPin(context: Context, pin: String): Boolean {
        if (!isDuressSet(context)) return false
        return matches(context, KEY_DURESS_SALT, KEY_DURESS_HASH, pin)
    }

    fun removeDuressPin(context: Context) {
        prefs(context).edit {
            remove(KEY_DURESS_SALT)
            remove(KEY_DURESS_HASH)
        }
    }

    /** False if not set or the type is PATTERN. Does not touch attempt state. */
    fun verifyPin(context: Context, pin: String): Boolean {
        if (getType(context) !in setOf(AppLockType.PIN, AppLockType.MIXED)) return false
        return matches(context, KEY_PIN_SALT, KEY_PIN_HASH, pin)
    }

    /** False if not set or the type is PIN. Does not touch attempt state. */
    fun verifyPattern(context: Context, cells: List<Int>): Boolean {
        if (getType(context) !in setOf(AppLockType.PATTERN, AppLockType.MIXED)) return false
        return matches(context, KEY_PATTERN_SALT, KEY_PATTERN_HASH, patternSecret(cells))
    }

    /** Clears the password AND the attempt state. */
    fun remove(context: Context) {
        prefs(context).edit { clear() }
        resetFailures(context)
    }

    fun consecutiveFailures(context: Context): Int =
        attemptPrefs(context).getInt(KEY_FAILURES, 0)

    /** 0 when not locked out. */
    fun lockoutRemainingMs(context: Context): Long =
        (attemptPrefs(context).getLong(KEY_LOCKOUT_UNTIL, 0) - System.currentTimeMillis())
            .coerceAtLeast(0)

    /**
     * failures++; at >= 5 sets a lockout of 30s, doubling per further
     * failure, capped at 5 minutes.
     */
    fun recordFailure(context: Context) {
        val failures = consecutiveFailures(context) + 1
        attemptPrefs(context).edit {
            putInt(KEY_FAILURES, failures)
            if (failures >= MAX_ATTEMPTS) {
                var duration = BASE_LOCKOUT_MS
                repeat(failures - MAX_ATTEMPTS) {
                    duration = minOf(duration * 2, MAX_LOCKOUT_MS)
                }
                putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + duration)
            }
        }
    }

    /** Call on correct verification. */
    fun resetFailures(context: Context) {
        attemptPrefs(context).edit {
            remove(KEY_FAILURES)
            remove(KEY_LOCKOUT_UNTIL)
        }
    }

    private fun isValidPin(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH &&
            pin.all { it in '0'..'9' }

    private fun isValidPattern(cells: List<Int>): Boolean =
        cells.size >= MIN_PATTERN_CELLS &&
            cells.all { it in 0..8 } &&
            cells.toSet().size == cells.size

    private fun patternSecret(cells: List<Int>): String = cells.joinToString(",")

    private fun SharedPreferences.Editor.putSecret(
        saltKey: String,
        hashKey: String,
        secret: String,
    ) {
        val salt = PasswordKdf.randomSalt()
        putString(saltKey, PasswordKdf.toHex(salt))
        putString(hashKey, PasswordKdf.toHex(hash(salt, secret)))
    }

    private fun matches(
        context: Context,
        saltKey: String,
        hashKey: String,
        secret: String,
    ): Boolean {
        val prefs = prefs(context)
        val saltHex = prefs.getString(saltKey, null) ?: return false
        val hashHex = prefs.getString(hashKey, null) ?: return false
        return try {
            val expected = PasswordKdf.fromHex(hashHex)
            val actual = hash(PasswordKdf.fromHex(saltHex), secret)
            MessageDigest.isEqual(expected, actual)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun hash(salt: ByteArray, secret: String): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(salt)
            digest(secret.toByteArray(Charsets.UTF_8))
        }

    private fun prefs(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            FILE_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private fun attemptPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(
            ATTEMPT_FILE_NAME,
            Context.MODE_PRIVATE,
        )

    private const val FILE_NAME = "openguard_applock"
    private const val ATTEMPT_FILE_NAME = "openguard_applock_attempts"
    private const val KEY_TYPE = "type"
    private const val KEY_PIN_LENGTH = "pin_length"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PATTERN_SALT = "pattern_salt"
    private const val KEY_PATTERN_HASH = "pattern_hash"
    private const val KEY_DURESS_SALT = "duress_salt"
    private const val KEY_DURESS_HASH = "duress_hash"
    private const val KEY_FAILURES = "failures"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until"
    private const val TYPE_PATTERN = "pattern"
    private const val TYPE_PIN = "pin"
    private const val TYPE_MIXED = "mixed"
}

enum class AppLockType { PATTERN, PIN, MIXED }
