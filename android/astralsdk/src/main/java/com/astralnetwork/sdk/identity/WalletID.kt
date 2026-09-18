package com.astralnetwork.sdk.identity

import java.security.MessageDigest

/**
 * WalletID — A stable, human-readable identity derived from a P-256 public key.
 *
 * The first 8 bytes of SHA-256(publicKey) become the wallet's routing address.
 * Cross-platform compatible with iOS WalletID.
 */
data class WalletID(val id: String) {

    companion object {
        fun fromPublicKey(publicKeyBytes: ByteArray): WalletID {
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
            val id = digest.take(8).joinToString("") { "%02x".format(it) }
            return WalletID(id)
        }
    }

    // Short display string — last 4 chars of the ID
    val short: String get() = id.takeLast(8).uppercase()

    // 8 raw routing bytes derived from the hex ID (cross-platform binary format)
    val routingBytes: ByteArray get() {
        val result = ByteArray(8)
        val bytesToRead = minOf(id.length / 2, 8)
        for (i in 0 until bytesToRead) {
            result[i] = id.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    override fun toString(): String = "AstralWallet($short)"
}
