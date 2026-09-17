package com.astralnetwork.sdk.identity

import java.security.MessageDigest

/**
 * WalletID — identity derived from public key SHA-256 fingerprint.
 * Mirrors iOS WalletID. The ID is the hex-encoded hash of the
 * raw EC public key bytes (W point, uncompressed x963 format).
 */
data class WalletID(val id: String) {

    /** Short display form: first 8 chars of the fingerprint. */
    val short: String get() = if (id.length >= 8) id.substring(0, 8) else id

    companion object {
        /** Derive WalletID from raw public key bytes. */
        fun fromPublicKey(publicKeyBytes: ByteArray): WalletID {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(publicKeyBytes)
            val hex = hash.joinToString("") { "%02x".format(it) }
            return WalletID(hex)
        }
    }
}
