package com.astralnetwork.sdk.core

/**
 * AstralConfig — centralized configuration for the SDK.
 * Mirrors iOS AstralConfig. All BLE, crypto, and relay tuning in one place.
 */
data class AstralConfig(
    // BLE
    val bleServiceUUID: String = "0000A57F-0000-1000-8000-00805F9B34FB",
    val bleFragmentSize: Int = 469,
    val meshTTL: Int = 3,
    val bleDutyOnDuration: Long = 5000L,
    val bleDutyOffDuration: Long = 10000L,
    val bleMaxConnections: Int = 6,
    val bleFragmentSpacingMs: Long = 30L,

    // Relay
    val relayEndpoint: String? = null,
    val relayAnonKey: String? = null,
    val relayPollInterval: Long = 5000L,

    // Payment Router
    val bleScanTimeout: Long = 2000L,
    val outboxMaxPerRecipient: Int = 100,
    val outboxTTLSeconds: Long = 86400L,
    val paymentTimeoutMs: Long = 10000L,

    // Crypto
    val useStrongBox: Boolean = true,
    val keystoreAlias: String = "astral_identity_key",

    // Protocol
    val protocolVersion: Int = 1,

    // Dedup
    val dedupMaxAge: Long = 300_000L,     // 5 min in ms
    val dedupMaxCount: Int = 1000
) {
    companion object {
        val DEFAULT = AstralConfig()

        val TESTNET = AstralConfig(
            bleServiceUUID = "0000A57F-0000-1000-8000-00805F9B34FA",
            meshTTL = 2,
            outboxTTLSeconds = 3600L
        )
    }
}
