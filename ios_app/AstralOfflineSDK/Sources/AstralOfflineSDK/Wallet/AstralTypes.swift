import Foundation

/**
 * WalletID — Stable cross-platform identity derived from a P-256 public key.
 * Identical derivation logic as Android WalletID.fromPublicKey().
 */
public struct WalletID: Codable, Hashable, Identifiable {
    public let id: String

    public init(id: String) { self.id = id }

    public init(publicKeyData: Data) {
        import CryptoKit
        let hash = SHA256.hash(data: publicKeyData)
        self.id = hash.prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    public var short: String { id.suffix(8).uppercased() }
    public var displayName: String { "Astral \(short)" }

    /// 8 raw routing bytes (matches Android WalletID.routingBytes, big-endian hex decode)
    public var routingBytes: Data {
        var result = Data(count: 8)
        let hex = id
        for i in 0..<min(hex.count / 2, 8) {
            let start = hex.index(hex.startIndex, offsetBy: i * 2)
            let end = hex.index(start, offsetBy: 2)
            result[i] = UInt8(hex[start..<end], radix: 16) ?? 0
        }
        return result
    }
}

/**
 * AstralQRPayload — What lives in a QR code for receiving payments or messages.
 * Cross-platform JSON format (identical to Android AstralQRPayload).
 */
public struct AstralQRPayload: Codable {
    private static let version = 2

    public let walletID: WalletID
    public let ecdhPublicKeyData: Data?  // 65-byte X9.62 uncompressed
    public let amountPaisa: Int64?

    public init(walletID: WalletID, ecdhPublicKeyData: Data? = nil, amountPaisa: Int64? = nil) {
        self.walletID = walletID
        self.ecdhPublicKeyData = ecdhPublicKeyData
        self.amountPaisa = amountPaisa
    }

    public func toQRString() -> String {
        var dict: [String: Any] = ["v": Self.version, "id": walletID.id]
        if let pk = ecdhPublicKeyData { dict["pk"] = pk.base64EncodedString() }
        if let amt = amountPaisa { dict["amt"] = amt }
        let data = try! JSONSerialization.data(withJSONObject: dict)
        return String(data: data, encoding: .utf8)!
    }

    public static func parse(_ string: String) -> AstralQRPayload? {
        guard let data = string.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              json["v"] as? Int == version,
              let idStr = json["id"] as? String else { return nil }

        let pkBase64 = json["pk"] as? String
        let pkData = pkBase64.flatMap { Data(base64Encoded: $0) }
        let amt = json["amt"] as? Int64

        return AstralQRPayload(
            walletID: WalletID(id: idStr),
            ecdhPublicKeyData: pkData,
            amountPaisa: amt
        )
    }
}

/**
 * AstralTransaction — Cross-platform binary payment record.
 * Binary encoding matches Android AstralTransaction.encode() exactly.
 */
public struct AstralTransaction: Identifiable, Codable {
    public let id: String
    public let senderID: WalletID
    public let recipientID: WalletID
    public let amountPaisa: Int64
    public let timestamp: Date
    public let memo: String
    public var status: Status

    public enum Status: String, Codable { case pending, sent, received, failed }

    public var displayAmount: String {
        String(format: "₹%.2f", Double(amountPaisa) / 100.0)
    }

    public func toPayload() throws -> Data {
        try JSONEncoder().encode(self)
    }

    public static func from(payload: Data) throws -> AstralTransaction {
        try JSONDecoder().decode(AstralTransaction.self, from: payload)
    }
}

/**
 * AstralError — SDK errors, cross-platform aligned.
 */
public enum AstralError: Error, LocalizedError {
    case keyNotInitialized
    case missingRecipientKey
    case encryptionFailed
    case decryptionFailed
    case signatureVerificationFailed
    case malformedPacket
    case insufficientFunds

    public var errorDescription: String? {
        switch self {
        case .keyNotInitialized:           return "SDK not initialized. Call start() first."
        case .missingRecipientKey:         return "Recipient QR code missing encryption key."
        case .encryptionFailed:            return "Encryption failed."
        case .decryptionFailed:            return "Decryption failed. Packet may be tampered."
        case .signatureVerificationFailed: return "Signature verification failed. Sender identity rejected."
        case .malformedPacket:             return "Malformed packet received."
        case .insufficientFunds:           return "Insufficient balance."
        }
    }
}
