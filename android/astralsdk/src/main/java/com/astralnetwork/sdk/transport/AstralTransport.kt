package com.astralnetwork.sdk.transport

import com.astralnetwork.sdk.core.AstralError
import com.astralnetwork.sdk.identity.WalletID

/**
 * Transport abstraction for payment delivery.
 * Mirrors iOS AstralTransport protocol.
 */
interface AstralTransport {
    val kind: TransportKind
    val isAvailable: Boolean

    /** Raw encrypted data callback WITH respond callback (for ACK/NACK) */
    var onRawDataReceived: ((data: ByteArray, respond: (VerificationResult) -> Unit) -> Unit)?
    var onStatusChanged: ((Boolean) -> Unit)?

    fun start()
    fun stop()
    fun send(packet: ByteArray, to: WalletID, completion: (Result<Unit>) -> Unit)
    fun isReachable(recipientID: WalletID): Boolean
    fun startListening(walletID: WalletID)
    fun stopListening()
}

enum class TransportKind { BLE, RELAY }

/** Result of SDK verification pipeline — passed back to transport for ACK/NACK. */
sealed class VerificationResult {
    object Verified : VerificationResult()
    data class Rejected(val error: AstralError) : VerificationResult()
}
