package com.astralnetwork.sdk.core

/**
 * AstralError — SDK error types.
 * Mirrors iOS AstralError enum for cross-platform consistency.
 */
sealed class AstralError(message: String) : Exception(message) {
    object NotInitialized : AstralError("SDK not initialized")
    object BleUnavailable : AstralError("Bluetooth unavailable")
    object RelayUnavailable : AstralError("Relay server unavailable")
    object SigningFailed : AstralError("Transaction signing failed")
    object VerificationFailed : AstralError("Signature verification failed")
    object Duplicate : AstralError("Double-spend: transaction already seen")
    object PacketDecodingFailed : AstralError("Packet decoding failed")
    class PaymentFailed(reason: String) : AstralError("Payment failed: $reason")
}
