import Foundation
import CryptoKit

/// TransactionSigner — P-256 ECDSA signing, verification, and fingerprint utilities.
///
/// Ensures cryptographically verifiable authenticity of Astral transactions.
public enum TransactionSigner {

    // MARK: - Verification

    /// Verify a P-256 ECDSA signature for the provided data and public key.
    ///
    /// Accepts signatures in either DER format or raw (r || s, 64-byte) format.
    ///
    /// - Parameters:
    ///   - data: The signed payload.
    ///   - signature: DER-encoded or 64-byte raw ECDSA signature.
    ///   - publicKey: Uncompressed ANSI x9.63 public key (65 bytes).
    /// - Returns: True if signature is valid; false otherwise.
    public static func verify(data: Data, signature: Data, publicKey: Data) -> Bool {
        guard let pubKey = try? P256.Signing.PublicKey(x963Representation: publicKey) else {
            return false
        }

        // Try DER representation first
        if let ecdsaSig = try? P256.Signing.ECDSASignature(derRepresentation: signature) {
            return pubKey.isValidSignature(ecdsaSig, for: data)
        }

        // Fall back to raw representation if 64 bytes
        if signature.count == 64, let ecdsaSig = try? P256.Signing.ECDSASignature(rawRepresentation: signature) {
            return pubKey.isValidSignature(ecdsaSig, for: data)
        }

        return false
    }

    // MARK: - Signing

    /// Sign data using a software P256 private key, returning the DER-encoded signature.
    ///
    /// - Parameters:
    ///   - data: The payload to sign.
    ///   - privateKey: The software private key.
    /// - Returns: DER-encoded signature bytes.
    public static func sign(data: Data, privateKey: P256.Signing.PrivateKey) throws -> Data {
        let signature = try privateKey.signature(for: data)
        return signature.derRepresentation
    }

    /// Sign data using a hardware-backed Secure Enclave P256 private key, returning the DER-encoded signature.
    ///
    /// - Parameters:
    ///   - data: The payload to sign.
    ///   - privateKey: The Secure Enclave private key.
    /// - Returns: DER-encoded signature bytes.
    public static func sign(data: Data, privateKey: SecureEnclave.P256.Signing.PrivateKey) throws -> Data {
        let signature = try privateKey.signature(for: data)
        return signature.derRepresentation
    }

    // MARK: - Fingerprints

    /// Generate a 64-character lowercase hexadecimal SHA-256 fingerprint of the given public key.
    ///
    /// - Parameter publicKey: Public key data.
    /// - Returns: 64-character hex string.
    public static func fingerprint(publicKey: Data) -> String {
        let digest = SHA256.hash(data: publicKey)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// Generate a 16-character short fingerprint (prefix of SHA-256 hash) for display or wallet identifiers.
    ///
    /// - Parameter publicKey: Public key data.
    /// - Returns: 16-character hex string.
    public static func shortFingerprint(publicKey: Data) -> String {
        let full = fingerprint(publicKey: publicKey)
        return String(full.prefix(16))
    }
}
