import Foundation
import Combine
import CryptoKit

/// AstralSDK — Decentralized payment mesh SDK.
///
/// Security architecture:
/// - Keys are hardware-backed (SecureEnclave P256) — private keys never leave the chip
/// - All BLE packets are encrypted (ECDH + ChaCha20-Poly1305) — no plaintext on-air
/// - All transactions are signed (P256 ECDSA) — tampering is detected
/// - Bloom filter dedup — double-spend replay attacks are rejected
/// - ACKs are sent ONLY after verification passes — no blind acknowledgments
public final class AstralSDK: ObservableObject {

    // MARK: - Public State

    @Published public private(set) var walletID: WalletID?
    @Published public private(set) var transportStatus: TransportStatus = .offline
    @Published public private(set) var isListening: Bool = false

    /// Callback for received + VERIFIED payments (merchant mode).
    public var onPaymentReceived: ((AstralTransaction) -> Void)?

    /// Callback for sent-payment confirmations.
    public var onPaymentConfirmed: ((AstralTransaction) -> Void)?

    /// Callback for rejected payments (signature fail, double-spend, etc).
    public var onPaymentRejected: ((Data, AstralError) -> Void)?

    /// Whether keys are hardware-backed (Secure Enclave).
    public var isHardwareBacked: Bool { keyManager.isHardwareBacked }

    // MARK: - Internal Components

    let config: AstralConfig
    let keyManager: KeyManager
    let paymentRouter: PaymentRouter
    let deduplicationService: DeduplicationService

    private var transports: [AstralTransport] = []
    private var cancellables = Set<AnyCancellable>()

    // MARK: - Init

    public init(config: AstralConfig = .default) {
        self.config = config
        self.keyManager = KeyManager(config: config)
        self.paymentRouter = PaymentRouter(config: config)
        self.deduplicationService = DeduplicationService(config: config)
    }

    // MARK: - Lifecycle

    public func start() throws {
        // 1. Initialize hardware-backed keys
        try keyManager.initialize()
        walletID = keyManager.walletID

        // 2. Start all registered transports
        for transport in transports {
            transport.start()
        }

        updateTransportStatus()
    }

    public func stop() {
        for transport in transports {
            transport.stop()
        }
        isListening = false
        transportStatus = .offline
    }

    /// Register a transport (BLE, Relay, etc). Call before `start()`.
    public func registerTransport(_ transport: AstralTransport) {
        transports.append(transport)
        paymentRouter.registerTransport(transport)
        // Wire the raw data handler with ACK callback for secure receive.
        // The transport passes raw encrypted bytes AND a respond closure.
        // SDK verifies everything, then calls respond(.verified) or respond(.rejected).
        // The transport uses that to send ACK or NACK back to the sender's phone.
        transport.onRawDataReceived = { [weak self] rawData, respond in
            self?.handleIncomingRawData(rawData, respond: respond)
        }
    }

    // MARK: - Send Payment (ENCRYPTED)

    /// Send a payment to a merchant.
    ///
    /// Security pipeline:
    /// 1. Encode transaction → binary packet
    /// 2. Sign with Secure Enclave P256 (ECDSA) — proves sender identity
    /// 3. Encrypt with NoiseSession (ECDH + ChaCha20-Poly1305) — confidentiality
    /// 4. Route via best transport (BLE → Relay → Queue)
    public func sendPayment(
        amount: Int,
        to merchantID: WalletID,
        merchantPublicKey: Data,
        completion: @escaping (Result<AstralTransaction, AstralError>) -> Void
    ) {
        guard let walletID = walletID else {
            completion(.failure(.notInitialized))
            return
        }

        // 1. Build transaction
        let txn = AstralTransaction(
            id: UUID().uuidString,
            amount: amount,
            senderID: walletID,
            recipientID: merchantID,
            timestamp: Date(),
            status: .pending
        )

        // 2. Sign with Secure Enclave (private key never leaves hardware)
        let signedTxn: SignedTransaction
        do {
            signedTxn = try keyManager.signTransaction(txn)
        } catch {
            completion(.failure(.signingFailed))
            return
        }

        // 3. Encrypt the signed payload with merchant's public key
        let wireData = signedTxn.toWireFormat()
        let finalPacket: Data
        do {
            finalPacket = try NoiseSession.encrypt(
                plaintext: wireData,
                merchantPublicKey: merchantPublicKey
            )
        } catch {
            completion(.failure(.signingFailed))
            return
        }

        // 4. Route it (BLE → Relay → Queue)
        paymentRouter.route(finalPacket, for: txn) { result in
            switch result {
            case .success:
                var confirmed = txn
                confirmed.status = .sent
                completion(.success(confirmed))
            case .failure(let error):
                completion(.failure(error))
            }
        }
    }

    // MARK: - Receive Payment (VERIFY → DECRYPT → DEDUP → ACK)

    /// Process incoming raw encrypted data from any transport.
    ///
    /// Security pipeline (ALL steps must pass before ACK):
    /// 1. Decrypt with NoiseSession (Secure Enclave ECDH + ChaCha20)
    /// 2. Parse signed transaction from decrypted payload
    /// 3. Verify P256 ECDSA signature (proves sender identity, detects tampering)
    /// 4. Bloom filter dedup check (prevents double-spend replay)
    /// 5. Decode transaction
    /// 6. ONLY NOW: trigger onPaymentReceived callback
    private func handleIncomingRawData(_ rawData: Data, respond: @escaping (VerificationResult) -> Void) {
        // 1. Decrypt using NoiseSession (handles wire format parsing + SE ECDH internally)
        let decryptedData: Data
        do {
            decryptedData = try NoiseSession.decrypt(wireData: rawData, keyManager: keyManager)
        } catch {
            respond(.rejected(.packetDecodingFailed))
            reportRejection(rawData, error: .packetDecodingFailed)
            return
        }

        // 3. Parse signed transaction (pubkey + signature + payload)
        guard let signedTxn = SignedTransaction.fromWireFormat(decryptedData) else {
            respond(.rejected(.packetDecodingFailed))
            reportRejection(rawData, error: .packetDecodingFailed)
            return
        }

        // 4. Verify P256 ECDSA signature — proves sender identity, detects tampering
        guard KeyManager.verifyTransaction(signedTxn) else {
            respond(.rejected(.verificationFailed))
            reportRejection(rawData, error: .verificationFailed)
            return
        }

        // 5. Decode the actual transaction from the verified payload
        guard let txn = AstralPacket.decode(signedTxn.payload) else {
            respond(.rejected(.packetDecodingFailed))
            reportRejection(rawData, error: .packetDecodingFailed)
            return
        }

        // 6. Bloom filter dedup — prevents double-spend replay attacks
        if deduplicationService.isDuplicate(txn.id) {
            respond(.rejected(.duplicate))
            reportRejection(rawData, error: .duplicate)
            return
        }

        // ✅ ALL CHECKS PASSED — tell transport to send ACK to sender
        respond(.verified)

        // Now notify the app
        DispatchQueue.main.async { [weak self] in
            self?.onPaymentReceived?(txn)
        }
    }

    private func reportRejection(_ data: Data, error: AstralError) {
        DispatchQueue.main.async { [weak self] in
            self?.onPaymentRejected?(data, error)
        }
    }

    // MARK: - Merchant Mode

    public func startListening() {
        guard let walletID = walletID else { return }
        isListening = true
        for transport in transports {
            transport.startListening(as: walletID)
        }
    }

    public func stopListening() {
        isListening = false
        for transport in transports {
            transport.stopListening()
        }
    }

    // MARK: - QR Codes

    /// Generate a payment QR code containing wallet ID AND key-agreement public key.
    /// The customer NEEDS the public key to encrypt the payment.
    public func generatePaymentQR(amount: Int? = nil) -> AstralQRPayload {
        AstralQRPayload(
            walletID: walletID!,
            publicKey: keyManager.publicKeyBase64,
            amount: amount,
            relayEndpoint: config.relayEndpoint
        )
    }

    public func parseQR(_ data: String) -> AstralQRPayload? {
        AstralQRPayload.parse(from: data)
    }

    // MARK: - Private

    private func updateTransportStatus() {
        if transports.contains(where: { $0.isAvailable && $0.kind == .ble }) {
            transportStatus = .ble
        } else if transports.contains(where: { $0.isAvailable && $0.kind == .relay }) {
            transportStatus = .relay
        } else {
            transportStatus = .offline
        }
    }
}

// MARK: - Supporting Types

public enum TransportStatus: String, Sendable {
    case ble = "BLE Mesh"
    case relay = "Server Relay"
    case offline = "Offline"
}
