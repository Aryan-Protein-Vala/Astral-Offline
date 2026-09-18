import Foundation
import CryptoKit

/// NoiseSession — Confidentiality and authenticity for Astral transactions.
///
/// Implements an ephemeral P-256 ECDH key exchange with HKDF-SHA256 key derivation
/// and ChaCha20-Poly1305 AEAD encryption.
///
/// Wire Format:
/// - Version: 1 byte (`0x01`)
/// - Ephemeral Public Key: 65 bytes (uncompressed ANSI x9.63 format, starts with `0x04`)
/// - Combined Box: 12 bytes nonce + ciphertext + 16 bytes Poly1305 authentication tag
///
/// Total overhead: 94 bytes + ciphertext length
public enum NoiseSession {

    // MARK: - Constants

    /// Protocol wire version.
    public static let version: UInt8 = 0x01

    /// HKDF salt string for domain separation.
    public static let saltString = "AstralPayment-P256-v1"

    /// Salt data for HKDF derivation.
    public static let salt = saltString.data(using: .utf8)!

    /// Ephemeral public key size in bytes (ANSI x9.63 uncompressed).
    public static let ephemeralKeySize = 65

    /// ChaCha20-Poly1305 nonce size in bytes.
    public static let nonceSize = 12

    /// ChaCha20-Poly1305 tag size in bytes.
    public static let tagSize = 16

    /// Minimum wire payload size: 1 (version) + 65 (key) + 12 (nonce) + 16 (tag) = 94 bytes.
    public static let minWireSize = 1 + ephemeralKeySize + nonceSize + tagSize

    // MARK: - Encryption (Sender / Customer Side)

    /// Encrypt plaintext payload for a recipient with given P256 key-agreement public key.
    ///
    /// - Parameters:
    ///   - plaintext: The raw data to encrypt (e.g. `SignedTransaction.toWireFormat()`).
    ///   - merchantPublicKey: Recipient's uncompressed P256 key-agreement public key (65 bytes x9.63).
    /// - Returns: Wire-formatted encrypted payload: `[0x01][65B ephemeral key][12B nonce + ciphertext + 16B tag]`.
    public static func encrypt(plaintext: Data, merchantPublicKey: Data) throws -> Data {
        let merchantKey: P256.KeyAgreement.PublicKey
        do {
            merchantKey = try P256.KeyAgreement.PublicKey(x963Representation: merchantPublicKey)
        } catch {
            throw AstralError.packetDecodingFailed
        }

        // Generate ephemeral keypair for forward secrecy
        let ephemeralKey = P256.KeyAgreement.PrivateKey()
        let ephemeralPubData = ephemeralKey.publicKey.x963Representation

        // Perform ECDH key agreement
        let sharedSecret: SharedSecret
        do {
            sharedSecret = try ephemeralKey.sharedSecretFromKeyAgreement(with: merchantKey)
        } catch {
            throw AstralError.verificationFailed
        }

        // Derive 256-bit symmetric key using HKDF-SHA256
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: ephemeralPubData + merchantPublicKey,
            outputByteCount: 32
        )

        // Encrypt with ChaCha20-Poly1305
        let sealedBox: ChaChaPoly.SealedBox
        do {
            sealedBox = try ChaChaPoly.seal(plaintext, using: symmetricKey)
        } catch {
            throw AstralError.paymentFailed("Encryption failed")
        }

        // Wire format: [version: 1B][ephemeralKey: 65B][combined: 12B nonce + ciphertext + 16B tag]
        var wireData = Data(capacity: 1 + ephemeralPubData.count + sealedBox.combined.count)
        wireData.append(version)
        wireData.append(ephemeralPubData)
        wireData.append(sealedBox.combined)

        return wireData
    }

    // MARK: - Decryption (Receiver / Merchant Side)

    /// Decrypt wire-formatted payload using KeyManager (hardware-backed Secure Enclave or software fallback).
    ///
    /// - Parameters:
    ///   - wireData: The raw received packet.
    ///   - keyManager: The local KeyManager holding the key-agreement private key.
    /// - Returns: Decrypted plaintext.
    public static func decrypt(wireData: Data, keyManager: KeyManager) throws -> Data {
        guard wireData.count >= minWireSize else {
            throw AstralError.packetDecodingFailed
        }

        guard wireData[0] == version else {
            throw AstralError.packetDecodingFailed
        }

        guard let merchantPubData = keyManager.keyAgreementPublicKeyData else {
            throw AstralError.notInitialized
        }

        let ephemeralKeyData = wireData.subdata(in: 1..<1 + ephemeralKeySize)
        let combinedData = wireData.subdata(in: 1 + ephemeralKeySize..<wireData.count)

        // Perform ECDH key agreement via KeyManager (SE or software)
        let sharedSecret: SharedSecret
        do {
            sharedSecret = try keyManager.performKeyAgreement(with: ephemeralKeyData)
        } catch {
            throw AstralError.verificationFailed
        }

        // Derive same symmetric key
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: ephemeralKeyData + merchantPubData,
            outputByteCount: 32
        )

        // Open ChaCha20-Poly1305 sealed box
        do {
            let sealedBox = try ChaChaPoly.SealedBox(combined: combinedData)
            return try ChaChaPoly.open(sealedBox, using: symmetricKey)
        } catch {
            throw AstralError.verificationFailed
        }
    }

    /// Decrypt wire-formatted payload using a raw P256 key-agreement private key.
    ///
    /// - Parameters:
    ///   - wireData: The raw received packet.
    ///   - merchantPrivateKey: The recipient private key.
    /// - Returns: Decrypted plaintext.
    public static func decrypt(wireData: Data, merchantPrivateKey: P256.KeyAgreement.PrivateKey) throws -> Data {
        guard wireData.count >= minWireSize else {
            throw AstralError.packetDecodingFailed
        }

        guard wireData[0] == version else {
            throw AstralError.packetDecodingFailed
        }

        let ephemeralKeyData = wireData.subdata(in: 1..<1 + ephemeralKeySize)
        let combinedData = wireData.subdata(in: 1 + ephemeralKeySize..<wireData.count)

        let ephemeralKey = try P256.KeyAgreement.PublicKey(x963Representation: ephemeralKeyData)
        let sharedSecret = try merchantPrivateKey.sharedSecretFromKeyAgreement(with: ephemeralKey)
        let merchantPubData = merchantPrivateKey.publicKey.x963Representation

        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: ephemeralKeyData + merchantPubData,
            outputByteCount: 32
        )

        let sealedBox = try ChaChaPoly.SealedBox(combined: combinedData)
        return try ChaChaPoly.open(sealedBox, using: symmetricKey)
    }
}
