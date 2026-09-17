package com.astralnetwork.sdk.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.astralnetwork.sdk.crypto.NoiseSession
import com.astralnetwork.sdk.crypto.SignedTransaction
import com.astralnetwork.sdk.identity.KeyManager
import com.astralnetwork.sdk.identity.WalletID
import com.astralnetwork.sdk.routing.PaymentRouter
import com.astralnetwork.sdk.sync.DeduplicationService
import com.astralnetwork.sdk.transport.AstralTransport
import com.astralnetwork.sdk.transport.TransportKind
import com.astralnetwork.sdk.transport.VerificationResult
import com.astralnetwork.sdk.wallet.AstralQRPayload
import com.astralnetwork.sdk.wallet.AstralTransaction
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID

/**
 * AstralSDK — Decentralized payment mesh SDK for Android.
 *
 * Public API (drop-in for any Android app):
 *   sdk.start()                     → initializes Keystore keys + BLE
 *   sdk.sendPayment(amount, to)     → sign → encrypt → route → wait ACK
 *   sdk.onPaymentReceived           → fires ONLY after verify + dedup
 *   sdk.startListening()            → merchant BLE advertising
 *   sdk.generatePaymentQR()         → QR with public key for encrypted pay
 *
 * Mirrors iOS AstralSDK class 1:1.
 */
class AstralSDK(
    private val config: AstralConfig = AstralConfig.DEFAULT,
    private var context: Context? = null
) {

    companion object {
        private const val TAG = "AstralSDK"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // MARK: - Public State
    var walletID: WalletID? = null
        private set

    var transportStatus: TransportStatus = TransportStatus.OFFLINE
        private set

    var isListening: Boolean = false
        private set

    var isHardwareBacked: Boolean = false
        private set

    // MARK: - Callbacks
    var onPaymentReceived: ((AstralTransaction) -> Unit)? = null
    var onPaymentRejected: ((ByteArray, AstralError) -> Unit)? = null

    // MARK: - Internal Modules
    private val keyManager = KeyManager(config)
    private val paymentRouter = PaymentRouter(config)
    private val deduplicationService = DeduplicationService(config)
    private val transports = mutableListOf<AstralTransport>()

    // MARK: - Registration

    fun registerTransport(transport: AstralTransport) {
        // Wire the verification pipeline
        transport.onRawDataReceived = { data, respond ->
            handleIncomingRawData(data, respond)
        }
        transports.add(transport)
        paymentRouter.setTransports(transports)
    }

    // MARK: - Lifecycle

    @Throws(AstralError::class)
    fun start() {
        // 1. Initialize hardware-backed keys (pass context for key persistence)
        keyManager.initialize(context)
        walletID = keyManager.walletID
        isHardwareBacked = keyManager.isHardwareBacked

        // 2. Start all transports
        for (transport in transports) {
            transport.start()
            transport.onStatusChanged = { _ -> updateTransportStatus() }
        }

        updateTransportStatus()
        Log.d(TAG, "AstralSDK started — wallet: ${walletID?.short}")
        Log.d(TAG, "Hardware-backed: $isHardwareBacked")
    }

    fun stop() {
        transports.forEach { it.stop() }
        isListening = false
        transportStatus = TransportStatus.OFFLINE
    }

    // MARK: - Send Payment (ENCRYPTED)

    /**
     * Send a payment. Full security pipeline:
     * 1. Build transaction
     * 2. Sign with Keystore P256 (ECDSA)
     * 3. Encrypt with NoiseSession (ECDH + ChaCha20-Poly1305)
     * 4. Route via BLE → Relay → Queue
     * 5. Wait for merchant ACK/NACK before calling completion
     */
    fun sendPayment(
        amount: Int,
        to: WalletID,
        merchantPublicKey: ByteArray,
        completion: (Result<AstralTransaction>) -> Unit
    ) {
        val myWalletID = walletID ?: run {
            completion(Result.failure(AstralError.NotInitialized))
            return
        }

        // 1. Build transaction
        val txn = AstralTransaction(
            id = UUID.randomUUID().toString(),
            senderID = myWalletID,
            recipientID = to,
            amount = amount,
            timestamp = System.currentTimeMillis()
        )

        // 2. Sign it
        val signedTxn = SignedTransaction.sign(txn.encode(), keyManager)
        if (signedTxn == null) {
            completion(Result.failure(AstralError.SigningFailed))
            return
        }

        // 3. Encrypt with NoiseSession (ephemeral P256 ECDH + ChaCha20-Poly1305)
        // merchantPublicKey must be x963 format (65 bytes) — matches iOS CryptoKit
        val encrypted = NoiseSession.encrypt(signedTxn.toWireFormat(), merchantPublicKey)
        if (encrypted == null) {
            completion(Result.failure(AstralError.PaymentFailed("Encryption failed")))
            return
        }

        // 4. Route
        paymentRouter.route(encrypted, txn) { result ->
            result.fold(
                onSuccess = {
                    val confirmed = txn.copy(status = AstralTransaction.Status.SENT)
                    completion(Result.success(confirmed))
                },
                onFailure = { error ->
                    completion(Result.failure(error as? AstralError ?: AstralError.PaymentFailed(error.message ?: "Unknown")))
                }
            )
        }
    }

    // MARK: - Receive Payment (VERIFY → DECRYPT → DEDUP → ACK)

    private fun handleIncomingRawData(rawData: ByteArray, respond: (VerificationResult) -> Unit) {
        // 1. Decrypt using our encryption private key (software P256 ECDH)
        // Sender's ephemeral pubkey is embedded in the wire format header
        val decrypted = NoiseSession.decrypt(rawData, keyManager)
        if (decrypted == null) {
            Log.e(TAG, "❌ Decrypt failed — raw data size: ${rawData.size}")
            respond(VerificationResult.Rejected(AstralError.PacketDecodingFailed))
            reportRejection(rawData, AstralError.PacketDecodingFailed)
            return
        }

        // 2. Parse signed transaction from decrypted payload
        val parsedSigned = SignedTransaction.fromWireFormat(decrypted) ?: run {
            Log.e(TAG, "❌ SignedTransaction.fromWireFormat failed — decrypted size: ${decrypted.size}")
            respond(VerificationResult.Rejected(AstralError.PacketDecodingFailed))
            reportRejection(rawData, AstralError.PacketDecodingFailed)
            return
        }

        // 3. Verify signature
        // Public key is in x963 format (65 bytes) — convert to Java PublicKey
        try {
            val pubKey = NoiseSession.x963ToPublicKey(parsedSigned.publicKeyBytes)
                ?: throw IllegalArgumentException("Invalid x963 key: ${parsedSigned.publicKeyBytes.size} bytes")

            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pubKey)
            sig.update(parsedSigned.payload)
            if (!sig.verify(parsedSigned.signature)) {
                Log.e(TAG, "❌ Signature verification failed")
                respond(VerificationResult.Rejected(AstralError.VerificationFailed))
                reportRejection(rawData, AstralError.VerificationFailed)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Signature verification exception: ${e.message}", e)
            respond(VerificationResult.Rejected(AstralError.VerificationFailed))
            reportRejection(rawData, AstralError.VerificationFailed)
            return
        }

        // 4. Dedup check (Bloom filter — hash txn ID, not raw bytes)
        val txnIdHash = MessageDigest.getInstance("SHA-256").digest(parsedSigned.payload)
        if (deduplicationService.isDuplicate(txnIdHash)) {
            respond(VerificationResult.Rejected(AstralError.Duplicate))
            reportRejection(rawData, AstralError.Duplicate)
            return
        }

        // 5. Decode transaction (AstralPacket binary format)
        val txn = AstralTransaction.decode(parsedSigned.payload) ?: run {
            Log.e(TAG, "❌ AstralTransaction.decode failed — payload size: ${parsedSigned.payload.size}")
            respond(VerificationResult.Rejected(AstralError.PacketDecodingFailed))
            reportRejection(rawData, AstralError.PacketDecodingFailed)
            return
        }

        // ✅ ALL CHECKS PASSED — tell transport to send ACK
        respond(VerificationResult.Verified)

        // Notify the app ON MAIN THREAD (required for Compose UI updates)
        mainHandler.post {
            onPaymentReceived?.invoke(txn)
        }
        Log.d(TAG, "📥 Payment received: ₹${txn.amount / 100} from ${txn.senderID.short}")
    }

    private fun reportRejection(data: ByteArray, error: AstralError) {
        onPaymentRejected?.invoke(data, error)
    }

    // MARK: - Merchant Mode

    fun startListening() {
        val wid = walletID ?: return
        transports.forEach { it.startListening(wid) }
        isListening = true
        Log.d(TAG, "📡 Listening for payments as ${wid.short}")
    }

    fun stopListening() {
        transports.forEach { it.stopListening() }
        isListening = false
    }

    // MARK: - QR Generation

    fun generatePaymentQR(amount: Int? = null): AstralQRPayload {
        // QR embeds the ENCRYPTION public key (software P256, for ECDH)
        // NOT the Keystore signing key (which can't do ECDH)
        val encPubKeyBase64 = keyManager.getEncryptionPublicKeyBytes()?.let {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }

        return AstralQRPayload(
            walletID = walletID ?: WalletID("unknown"),
            publicKey = encPubKeyBase64,
            amount = amount,
            relayEndpoint = config.relayEndpoint
        )
    }

    fun parseQR(data: String): AstralQRPayload? = AstralQRPayload.parse(data)

    // MARK: - Private

    private fun updateTransportStatus() {
        transportStatus = when {
            transports.any { it.isAvailable && it.kind == TransportKind.BLE } -> TransportStatus.BLE
            transports.any { it.isAvailable && it.kind == TransportKind.RELAY } -> TransportStatus.RELAY
            else -> TransportStatus.OFFLINE
        }
    }
}

enum class TransportStatus(val displayName: String) {
    BLE("BLE Mesh"),
    RELAY("Server Relay"),
    OFFLINE("Offline")
}
