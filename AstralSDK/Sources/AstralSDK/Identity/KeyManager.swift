import Foundation
import CryptoKit
#if canImport(Security)
import Security
#endif

/// KeyManager — Hardware-backed cryptographic key management.
///
/// **Security Architecture:**
/// - **Signing Key:** `SecureEnclave.P256.Signing` — private key NEVER leaves the hardware chip.
///   Falls back to software `P256.Signing` ONLY on simulator (clearly marked as insecure).
/// - **Key Agreement:** `SecureEnclave.P256.KeyAgreement` — used for ECDH in NoiseSession.
///   Same hardware-only guarantee.
/// - **Keychain Storage:** Only the Secure Enclave key *reference* is stored, not raw key material.
///
/// Why P256 (not Curve25519):
///   Apple's Secure Enclave exclusively supports NIST P-256 (secp256r1).
///   Curve25519 keys are SOFTWARE-ONLY in CryptoKit — they sit in Keychain RAM
///   and can be extracted via jailbreak. P256 via SecureEnclave is physically
///   impossible to extract — the private key never leaves the chip.
public final class KeyManager {

    // MARK: - Public Properties

    /// The wallet identity derived from the signing public key.
    public private(set) var walletID: WalletID?

    /// Raw signing public key data (P256, uncompressed x963 representation).
    public var signingPublicKeyData: Data? {
        if let seKey = secureEnclaveSigningKey {
            return seKey.publicKey.x963Representation
        }
        return softwareSigningKey?.publicKey.x963Representation
    }

    /// Raw key-agreement public key data (for NoiseSession / QR codes).
    public var keyAgreementPublicKeyData: Data? {
        if let seKey = secureEnclaveKeyAgreementKey {
            return seKey.publicKey.x963Representation
        }
        return softwareKeyAgreementKey?.publicKey.x963Representation
    }

    /// Base64-encoded public key (for QR codes and announcements).
    public var publicKeyBase64: String {
        guard let data = keyAgreementPublicKeyData else { return "" }
        return data.base64EncodedString()
    }

    /// Whether keys are hardware-backed (true = Secure Enclave, false = software fallback).
    public private(set) var isHardwareBacked: Bool = false

    // MARK: - Private Keys

    private let config: AstralConfig

    // Secure Enclave keys (hardware-backed, preferred)
    private var secureEnclaveSigningKey: SecureEnclave.P256.Signing.PrivateKey?
    private var secureEnclaveKeyAgreementKey: SecureEnclave.P256.KeyAgreement.PrivateKey?

    // Software fallback keys (simulator ONLY — clearly marked insecure)
    private var softwareSigningKey: P256.Signing.PrivateKey?
    private var softwareKeyAgreementKey: P256.KeyAgreement.PrivateKey?

    // MARK: - Constants

    private let signingTag = "com.astralnetwork.sdk.signing.se"
    private let keyAgreementTag = "com.astralnetwork.sdk.keyagreement.se"

    // MARK: - Init

    init(config: AstralConfig) {
        self.config = config
    }

    // MARK: - Lifecycle

    /// Initialize keys. Attempts Secure Enclave first, falls back to software ONLY on simulator.
    func initialize() throws {
        if SecureEnclave.isAvailable && config.useSecureEnclave {
            try initializeSecureEnclave()
            isHardwareBacked = true
        } else {
            #if targetEnvironment(simulator)
            print("⚠️ [AstralSDK] SIMULATOR MODE — using SOFTWARE keys. NOT SECURE for production.")
            try initializeSoftwareFallback()
            isHardwareBacked = false
            #else
            // On real device without Secure Enclave — this should never happen on modern iPhones
            throw AstralError.keyGenerationFailed
            #endif
        }

        // Derive WalletID from signing public key
        guard let pubKeyData = signingPublicKeyData else {
            throw AstralError.keyGenerationFailed
        }
        walletID = WalletID(publicKey: pubKeyData)
    }

    // MARK: - Secure Enclave Initialization

    private func initializeSecureEnclave() throws {
        // Signing key
        if let existingSigning = loadSecureEnclaveSigningKey() {
            secureEnclaveSigningKey = existingSigning
        } else {
            let accessControl = SecAccessControlCreateWithFlags(
                nil,
                kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                [.privateKeyUsage],
                nil
            )!
            secureEnclaveSigningKey = try SecureEnclave.P256.Signing.PrivateKey(
                accessControl: accessControl
            )
            try saveSecureEnclaveKey(secureEnclaveSigningKey!.dataRepresentation, tag: signingTag)
        }

        // Key agreement key
        if let existingKA = loadSecureEnclaveKeyAgreementKey() {
            secureEnclaveKeyAgreementKey = existingKA
        } else {
            let accessControl = SecAccessControlCreateWithFlags(
                nil,
                kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                [.privateKeyUsage],
                nil
            )!
            secureEnclaveKeyAgreementKey = try SecureEnclave.P256.KeyAgreement.PrivateKey(
                accessControl: accessControl
            )
            try saveSecureEnclaveKey(secureEnclaveKeyAgreementKey!.dataRepresentation, tag: keyAgreementTag)
        }
    }

    // MARK: - Software Fallback (Simulator Only)

    private func initializeSoftwareFallback() throws {
        // Try to load from keychain first
        if let signingData = loadKeyData(tag: signingTag),
           let kaData = loadKeyData(tag: keyAgreementTag) {
            softwareSigningKey = try P256.Signing.PrivateKey(rawRepresentation: signingData)
            softwareKeyAgreementKey = try P256.KeyAgreement.PrivateKey(rawRepresentation: kaData)
        } else {
            softwareSigningKey = P256.Signing.PrivateKey()
            softwareKeyAgreementKey = P256.KeyAgreement.PrivateKey()
            try saveKeyData(softwareSigningKey!.rawRepresentation, tag: signingTag)
            try saveKeyData(softwareKeyAgreementKey!.rawRepresentation, tag: keyAgreementTag)
        }
    }

    // MARK: - Transaction Signing (P256/ECDSA)

    /// Sign transaction data. The private key NEVER leaves Secure Enclave.
    /// The chip performs the signature internally and returns only the result.
    func signTransaction(_ txn: AstralTransaction) throws -> SignedTransaction {
        let packetData = AstralPacket.encode(txn)
        guard let payload = packetData else {
            throw AstralError.packetEncodingFailed
        }

        let signature: P256.Signing.ECDSASignature

        if let seKey = secureEnclaveSigningKey {
            // Hardware signing — private key never leaves the chip
            signature = try seKey.signature(for: payload)
        } else if let swKey = softwareSigningKey {
            signature = try swKey.signature(for: payload)
        } else {
            throw AstralError.notInitialized
        }

        return SignedTransaction(
            payload: payload,
            signature: signature.derRepresentation,
            senderPublicKey: signingPublicKeyData!
        )
    }

    /// Verify a signed transaction from a sender's public key.
    public static func verifyTransaction(_ signed: SignedTransaction) -> Bool {
        guard let pubKey = try? P256.Signing.PublicKey(x963Representation: signed.senderPublicKey) else {
            return false
        }
        guard let ecdsaSig = try? P256.Signing.ECDSASignature(derRepresentation: signed.signature) else {
            return false
        }
        return pubKey.isValidSignature(ecdsaSig, for: signed.payload)
    }

    /// Get the key agreement private key data for NoiseSession decryption (merchant side).
    /// On Secure Enclave, this performs ECDH inside the chip — the raw private key is NEVER exposed.
    func performKeyAgreement(with peerPublicKey: Data) throws -> SharedSecret {
        guard let peerKey = try? P256.KeyAgreement.PublicKey(x963Representation: peerPublicKey) else {
            throw AstralError.verificationFailed
        }

        if let seKey = secureEnclaveKeyAgreementKey {
            return try seKey.sharedSecretFromKeyAgreement(with: peerKey)
        } else if let swKey = softwareKeyAgreementKey {
            return try swKey.sharedSecretFromKeyAgreement(with: peerKey)
        } else {
            throw AstralError.notInitialized
        }
    }

    // MARK: - Keychain Persistence

    private func saveSecureEnclaveKey(_ data: Data, tag: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: config.keychainService,
            kSecAttrAccount as String: tag,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        SecItemDelete(query as CFDictionary)
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw AstralError.keyGenerationFailed
        }
    }

    private func loadSecureEnclaveSigningKey() -> SecureEnclave.P256.Signing.PrivateKey? {
        guard let data = loadKeyData(tag: signingTag) else { return nil }
        return try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: data)
    }

    private func loadSecureEnclaveKeyAgreementKey() -> SecureEnclave.P256.KeyAgreement.PrivateKey? {
        guard let data = loadKeyData(tag: keyAgreementTag) else { return nil }
        return try? SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: data)
    }

    private func saveKeyData(_ data: Data, tag: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: config.keychainService,
            kSecAttrAccount as String: tag,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        SecItemDelete(query as CFDictionary)
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw AstralError.keyGenerationFailed
        }
    }

    private func loadKeyData(tag: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: config.keychainService,
            kSecAttrAccount as String: tag,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { return nil }
        return result as? Data
    }
}

// MARK: - SignedTransaction

/// A transaction with its ECDSA signature and sender's public key.
/// This is the actual wire payload — signature is verified before processing.
public struct SignedTransaction: Codable, Sendable {
    /// The raw encoded transaction packet.
    public let payload: Data
    /// P256 ECDSA signature (DER-encoded).
    public let signature: Data
    /// Sender's P256 signing public key (x963 representation).
    public let senderPublicKey: Data

    /// Encode to wire format: [pubKeyLen:2][pubKey][sigLen:2][sig][payload]
    public func toWireFormat() -> Data {
        var data = Data()
        // Public key
        let pkLen = UInt16(senderPublicKey.count)
        data.append(UInt8(pkLen >> 8))
        data.append(UInt8(pkLen & 0xFF))
        data.append(senderPublicKey)
        // Signature
        let sigLen = UInt16(signature.count)
        data.append(UInt8(sigLen >> 8))
        data.append(UInt8(sigLen & 0xFF))
        data.append(signature)
        // Payload
        data.append(payload)
        return data
    }

    /// Decode from wire format.
    public static func fromWireFormat(_ data: Data) -> SignedTransaction? {
        guard data.count >= 4 else { return nil }
        var offset = 0

        // Public key
        let pkLen = Int(UInt16(data[offset]) << 8 | UInt16(data[offset + 1])); offset += 2
        guard offset + pkLen <= data.count else { return nil }
        let pubKey = Data(data[offset..<offset + pkLen]); offset += pkLen

        // Signature
        guard offset + 2 <= data.count else { return nil }
        let sigLen = Int(UInt16(data[offset]) << 8 | UInt16(data[offset + 1])); offset += 2
        guard offset + sigLen <= data.count else { return nil }
        let sig = Data(data[offset..<offset + sigLen]); offset += sigLen

        // Payload
        let payload = Data(data[offset...])

        return SignedTransaction(payload: payload, signature: sig, senderPublicKey: pubKey)
    }
}
