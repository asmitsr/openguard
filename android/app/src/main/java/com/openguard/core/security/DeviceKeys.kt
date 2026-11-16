package com.openguard.core.security

/**
 * STUB — Phase 2+ only.
 *
 * Planned: a per-device keypair in Android Keystore for signed (and later
 * end-to-end encrypted) location uploads. The MVP authenticates uploads
 * with a bearer device token (see TokenStore) and needs no Keystore keys,
 * so this object intentionally does nothing yet.
 *
 * Do NOT treat anything here as implemented: [hasDeviceKey] always returns
 * false until the Phase 2 key work lands.
 */
object DeviceKeys {
    fun hasDeviceKey(): Boolean = false
}
