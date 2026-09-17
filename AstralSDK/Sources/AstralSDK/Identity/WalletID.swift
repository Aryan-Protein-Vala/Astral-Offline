import Foundation
import CryptoKit

/// WalletID — Unique identity for an Astral wallet.
///
/// Derived from the SHA-256 hash of the Noise/signing public key,
/// truncated to 16 hex characters (8 bytes). Modeled after bitchat's `PeerID`.
///
/// Usage:
/// ```swift
/// let id = WalletID(publicKey: myPubKeyData)
/// print(id.short)  // "a1b2c3d4e5f6a7b8"
/// ```
public struct WalletID: Equatable, Hashable, Codable, Sendable {

    /// Full hex string identifier (16 characters = 8 bytes from SHA-256).
    public let id: String

    /// Short display form (first 8 chars).
    public var short: String { String(id.prefix(8)) }

    /// Initialize from a public key (derives identity via SHA-256).
    public init(publicKey: Data) {
        let digest = SHA256.hash(data: publicKey)
        let hex = digest.compactMap { String(format: "%02x", $0) }.joined()
        self.id = String(hex.prefix(16))
    }

    /// Initialize from an existing ID string.
    public init(id: String) {
        self.id = id.lowercased()
    }

    /// Initialize from raw hex data (8 bytes → 16 hex chars).
    public init?(data: Data) {
        guard data.count == 8 else { return nil }
        self.id = data.map { String(format: "%02x", $0) }.joined()
    }

    /// Convert to 8-byte routing data.
    public var routingData: Data? {
        guard id.count == 16 else { return nil }
        var data = Data()
        var hex = id
        while hex.count >= 2 {
            let byteStr = String(hex.prefix(2))
            if let byte = UInt8(byteStr, radix: 16) {
                data.append(byte)
            }
            hex = String(hex.dropFirst(2))
        }
        return data.count == 8 ? data : nil
    }

    /// Validation: must be exactly 16 lowercase hex characters.
    public var isValid: Bool {
        id.count == 16 && id.allSatisfy { $0.isHexDigit }
    }
}

// MARK: - CustomStringConvertible

extension WalletID: CustomStringConvertible {
    public var description: String { id }
}

// MARK: - Comparable

extension WalletID: Comparable {
    public static func < (lhs: WalletID, rhs: WalletID) -> Bool {
        lhs.id < rhs.id
    }
}
