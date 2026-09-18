package com.astralnetwork.sdk.routing

import com.astralnetwork.sdk.core.AstralConfig
import com.astralnetwork.sdk.core.AstralError
import com.astralnetwork.sdk.identity.WalletID
import com.astralnetwork.sdk.transport.AstralTransport
import com.astralnetwork.sdk.transport.TransportKind
import com.astralnetwork.sdk.wallet.AstralTransaction

/**
 * PaymentRouter — routes payment packets via best available transport.
 *
 * Priority: BLE (direct) → Relay (server) → Queue (store & forward).
 * Mirrors iOS PaymentRouter.
 */
class PaymentRouter(private val config: AstralConfig = AstralConfig.DEFAULT) {

    private var transports: List<AstralTransport> = emptyList()

    fun setTransports(transports: List<AstralTransport>) {
        this.transports = transports
    }

    fun registerTransport(transport: AstralTransport) {
        this.transports = this.transports + transport
    }

    /**
     * Route encrypted packet via best transport.
     */
    fun route(
        packet: ByteArray,
        transaction: AstralTransaction,
        completion: (Result<Unit>) -> Unit
    ) {
        // 1. Try BLE first (offline, direct)
        val ble = transports.firstOrNull { it.kind == TransportKind.BLE && it.isAvailable }
        if (ble != null && ble.isReachable(transaction.recipientID)) {
            ble.send(packet, transaction.recipientID, completion)
            return
        }

        // 2. Try Relay (server-assisted)
        val relay = transports.firstOrNull { it.kind == TransportKind.RELAY && it.isAvailable }
        if (relay != null) {
            relay.send(packet, transaction.recipientID, completion)
            return
        }

        // 3. No transport available
        completion(Result.failure(AstralError.BleUnavailable))
    }
}
