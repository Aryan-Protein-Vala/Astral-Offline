package com.astralnetwork.sdk.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.astralnetwork.sdk.crypto.NoiseSession
import com.astralnetwork.sdk.crypto.SignedTransaction
import com.astralnetwork.sdk.identity.KeyManager
import com.astralnetwork.sdk.identity.WalletID
import com.astralnetwork.sdk.routing.PaymentRouter
import com.astralnetwork.sdk.sync.DeduplicationService
import com.astralnetwork.sdk.transport.AstralTransport
import com.astralnetwork.sdk.transport.VerificationResult
import com.astralnetwork.sdk.wallet.AstralQRPayload
import com.astralnetwork.sdk.wallet.AstralTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * AstralSDK — The public interface for Astral Offline Payments and Mesh Networking.
 *
 * Security Guarantees:
 *   ✅ Hardware-backed signing identity (StrongBox / TEE)
 *   ✅ End-to-end encryption via P256 ECDH + HKDF-SHA256 + ChaCha20-Poly1305 (NoiseSession)
 *   ✅ P-256 ECDSA signature verification on every received transaction
 *   ✅ Bloom-filter deduplication against double-spend replay attacks
 *   ✅ Verifiable BLE & Relay transport: ACKs are only sent after complete cryptographic verification
 */
class AstralSDK(
    private val context: Context,
    val config: AstralConfig = AstralConfig.DEFAULT
) {

    companion object {
        private const val TAG = "AstralSDK"
    }

    val keyManager = KeyManager(useStrongBox = config.useStrongBox)
    val paymentRouter = PaymentRouter(config)
    val deduplicationService = DeduplicationService(config)

    private val transports = mutableListOf<AstralTransport>()
    private val sdkScope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    val walletID: WalletID? get() = keyManager.walletID
    val isHardwareBacked: Boolean get() = keyManager.isHardwareBacked
    val isStrongBoxBacked: Boolean get() = keyManager.isStrongBoxBacked
    var isListening: Boolean = false
        private set

    /**
     * Callback for verified incoming payments (merchant mode).
     */
    var onPaymentReceived: ((AstralTransaction) -> Unit)? = null

    /**
     * Callback for sent-payment confirmations.
     */
    var onPaymentConfirmed: ((AstralTransaction) -> Unit)? = null

    /**
     * Callback for rejected payments (signature failure, double-spend replay, etc.).
     */
    var onPaymentRejected: ((ByteArray, AstralError) -> Unit)? = null

    /**
     * Generic callback for verified raw payload delivery.
     */
    var onPayloadReceived: ((ByteArray, WalletID) -> Unit)? = null

    // ── Transport Registration ─────────────────────────────────────────────────

    /**
     * Registers a transport (BLE, Relay, etc.) with the SDK.
     */
    fun registerTransport(transport: AstralTransport) {
        transports.add(transport)
        paymentRouter.registerTransport(transport)

        // Wire raw encrypted data callback with ACK/NACK response mechanism
        transport.onRawDataReceived = { rawData, respond ->
            handleIncomingRawData(rawData, respond)
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    fun start() {
        keyManager.initialize(context)
        Log.d(TAG, "AstralSDK started — wallet: ${walletID?.short}")
        Log.d(TAG, "Security: StrongBox=${isStrongBoxBacked}, HW=${isHardwareBacked}")

        for (transport in transports) {
            transport.start()
        }
    }

    fun stop() {
        for (transport in transports) {
            transport.stop()
        }
        isListening = false
        Log.d(TAG, "AstralSDK stopped")
    }

    // ── Merchant Mode (Listening) ──────────────────────────────────────────────

    fun startListening() {
        val currentWallet = walletID ?: return
        isListening = true
        for (transport in transports) {
            transport.startListening(currentWallet)
        }
        Log.d(TAG, "Listening on transports — wallet: ${currentWallet.short}")
    }

    fun stopListening() {
        isListening = false
        for (transport in transports) {
            transport.stopListening()
        }
        Log.d(TAG, "Stopped listening on transports")
    }

    // ── Payment Sending (P256 Sign → NoiseSession Encrypt → Route) ─────────────

    /**
     * Sends an encrypted, signed payment to a merchant.
     *
     * Pipeline:
     *   1. Build transaction record
     *   2. Sign transaction using hardware-backed P-256 key
     *   3. Encrypt wire format using NoiseSession (ECDH + ChaCha20-Poly1305)
     *   4. Route via PaymentRouter (BLE direct → Relay)
     */
    fun sendPayment(
        amount: Int,
        to: WalletID,
        merchantPublicKey: ByteArray,
        completion: (Result<AstralTransaction>) -> Unit
    ) {
        val senderWallet = walletID
        if (senderWallet == null) {
            completion(Result.failure(AstralError.NotInitialized))
            return
        }

        val txn = AstralTransaction(
            id = UUID.randomUUID().toString(),
            amount = amount,
            senderID = senderWallet,
            recipientID = to,
            timestamp = System.currentTimeMillis(),
            status = AstralTransaction.Status.PENDING
        )

        sdkScope.launch {
            try {
                // 1. Sign transaction (private key never leaves secure element)
                val signedTxn = keyManager.signTransaction(txn)

                // 2. Encrypt signed wire payload with merchant's public key
                val wireData = signedTxn.toWireFormat()
                val finalPacket = NoiseSession.encrypt(wireData, merchantPublicKey)

                // 3. Route packet via best available transport
                paymentRouter.route(finalPacket, txn) { routeResult ->
                    routeResult.fold(
                        onSuccess = {
                            txn.status = AstralTransaction.Status.SENT
                            mainHandler.post {
                                completion(Result.success(txn))
                                onPaymentConfirmed?.invoke(txn)
                            }
                        },
                        onFailure = { error ->
                            txn.status = AstralTransaction.Status.FAILED
                            mainHandler.post {
                                val astralError = (error as? AstralError)
                                    ?: AstralError.PaymentFailed(error.message ?: "Send failed")
                                completion(Result.failure(astralError))
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send payment exception", e)
                mainHandler.post {
                    completion(Result.failure(AstralError.PaymentFailed(e.message ?: "Encryption/signing failed")))
                }
            }
        }
    }

    // ── Payment Receiving (Decrypt → Verify Signature → Dedup → ACK) ───────────

    /**
     * Handles incoming raw encrypted bytes from any registered transport.
     * All security verification steps must pass before responding with Verified (ACK).
     */
    private fun handleIncomingRawData(rawData: ByteArray, respond: (VerificationResult) -> Unit) {
        sdkScope.launch {
            try {
                // 1. Decrypt using NoiseSession
                val decryptedData = try {
                    NoiseSession.decrypt(rawData, keyManager)
                } catch (e: Exception) {
                    Log.w(TAG, "Decryption failed: ${e.message}")
                    respond(VerificationResult.Rejected(AstralError.PacketDecodingFailed))
                    reportRejection(rawData, AstralError.PacketDecodingFailed)
                    return@launch
                }

                // 2. Parse signed transaction
                val signedTxn = SignedTransaction.fromWireFormat(decryptedData)
                if (signedTxn == null) {
                    Log.w(TAG, "Signed transaction deserialization failed")
                    respond(VerificationResult.Rejected(AstralError.PacketDecodingFailed))
                    reportRejection(rawData, AstralError.PacketDecodingFailed)
                    return@launch
                }

                // 3. Verify P-256 ECDSA signature
                if (!KeyManager.verifyTransaction(signedTxn)) {
                    Log.w(TAG, "❌ Transaction signature verification failed")
                    respond(VerificationResult.Rejected(AstralError.VerificationFailed))
                    reportRejection(rawData, AstralError.VerificationFailed)
                    return@launch
                }

                // 4. Decode binary transaction packet
                val txn = AstralTransaction.decode(signedTxn.payload)
                if (txn == null) {
                    Log.w(TAG, "Transaction decoding failed")
                    respond(VerificationResult.Rejected(AstralError.PacketDecodingFailed))
                    reportRejection(rawData, AstralError.PacketDecodingFailed)
                    return@launch
                }

                // 5. Bloom filter deduplication check (anti double-spend)
                if (deduplicationService.isDuplicate(txn.id)) {
                    Log.w(TAG, "❌ Duplicate transaction detected: ${txn.id}")
                    respond(VerificationResult.Rejected(AstralError.Duplicate))
                    reportRejection(rawData, AstralError.Duplicate)
                    return@launch
                }

                // ✅ All checks passed — confirm to transport so ACK is transmitted!
                respond(VerificationResult.Verified)
                Log.d(TAG, "✅ Transaction ${txn.id} verified from ${txn.senderID.short}")

                // Deliver to application listeners
                mainHandler.post {
                    onPaymentReceived?.invoke(txn)
                    onPayloadReceived?.invoke(signedTxn.payload, txn.senderID)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error processing incoming packet: ${e.message}", e)
                respond(VerificationResult.Rejected(AstralError.PaymentFailed(e.message ?: "Processing error")))
            }
        }
    }

    private fun reportRejection(data: ByteArray, error: AstralError) {
        mainHandler.post {
            onPaymentRejected?.invoke(data, error)
        }
    }

    // ── QR Code Helpers ────────────────────────────────────────────────────────

    /**
     * Generates a QR payload for this node to receive payments.
     */
    fun generatePaymentQR(amountPaisa: Long? = null): AstralQRPayload {
        val currentWallet = walletID ?: WalletID("uninitialized")
        return AstralQRPayload(
            walletID = currentWallet,
            ecdhPublicKeyBase64 = keyManager.publicKeyBase64,
            amountPaisa = amountPaisa,
            relayEndpoint = config.relayEndpoint
        )
    }

    fun generateReceiveQR(amountPaisa: Long? = null): AstralQRPayload = generatePaymentQR(amountPaisa)

    fun sendPayload(
        payload: ByteArray,
        recipientQR: AstralQRPayload,
        onResult: (Result<Unit>) -> Unit
    ) {
        val recipientPubKeyBytes = recipientQR.getEcdhPublicKeyBytes()
        if (recipientPubKeyBytes == null) {
            onResult(Result.failure(IllegalArgumentException("Recipient QR missing ECDH public key")))
            return
        }
        val senderWallet = walletID
        if (senderWallet == null) {
            onResult(Result.failure(AstralError.NotInitialized))
            return
        }
        val txn = AstralTransaction(
            id = UUID.randomUUID().toString(),
            amount = 0,
            senderID = senderWallet,
            recipientID = recipientQR.walletID,
            timestamp = System.currentTimeMillis(),
            status = AstralTransaction.Status.PENDING
        )
        sdkScope.launch {
            try {
                val signedTxn = keyManager.signTransaction(txn)
                val wireData = signedTxn.toWireFormat()
                val finalPacket = NoiseSession.encrypt(wireData, recipientPubKeyBytes)
                paymentRouter.route(finalPacket, txn) { routeResult ->
                    mainHandler.post { onResult(routeResult) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(Result.failure(e)) }
            }
        }
    }

    fun parseQR(data: String): AstralQRPayload? = AstralQRPayload.parse(data)
}
