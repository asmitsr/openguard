package com.openguard.core.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client-side password KDF (PBKDF2-SHA256, 600,000 iterations, 32-byte key).
 *
 * Workers Free allows only 10 ms CPU per request, so the server cannot run
 * PBKDF2 — the client derives the key and sends only the hex-encoded derived
 * key. The server stores SHA-256 of the derived key and never sees the
 * password. Derivation runs on Dispatchers.Default: ~150 ms of CPU, never
 * on the main thread. Uses javax.crypto (built into Android), no new
 * dependency.
 */
object PasswordKdf {

    const val ITERATIONS = 600_000
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16

    fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16))
                .toByte()
        }
    }

    /** Derives the key for [password] + hex [saltHex]; returns the hex key. */
    suspend fun deriveKeyHex(password: String, saltHex: String): String =
        withContext(Dispatchers.Default) {
            val spec = PBEKeySpec(
                password.toCharArray(),
                fromHex(saltHex),
                ITERATIONS,
                KEY_BYTES * 8,
            )
            try {
                val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).encoded
                toHex(key)
            } finally {
                spec.clearPassword()
            }
        }
}
