import Foundation

/// AstralPacket — Compact binary wire format for Astral transactions.
///
/// Modeled on bitchat's `BinaryProtocol` with TLV (Type-Length-Value) encoding.
///
/// ```
/// ┌──────────┬──────┬─────┬───────────┬─────┬──────────┬───────────┬──────────┬────────────┐
/// │ Version  │ Type │ TTL │ Timestamp │Flags│ PayloadLen│ SenderID  │RecipientID│ Payload    │
/// │ 1 byte   │1 byte│1 b  │ 8 bytes   │1 b  │ 2 bytes  │ 8 bytes   │8 bytes*  │ Variable   │
/// └──────────┴──────┴─────┴───────────┴─────┴──────────┴───────────┴──────────┴────────────┘
/// * RecipientID is optional (present if Flags.hasRecipient is set)
/// ```
public struct AstralPacket {

    // MARK: - Constants

    static let headerSize = 14   // version(1) + type(1) + ttl(1) + timestamp(8) + flags(1) + payloadLen(2)
    static let idSize = 8        // 8-byte wallet ID

    // MARK: - Message Types

    public enum MessageType: UInt8 {
        case transaction = 0x01
        case acknowledgment = 0x02
        case announcement = 0x03
        case handshakeInit = 0x10
        case handshakeResp = 0x11
    }

    // MARK: - Flags

    struct Flags {
        static let hasRecipient: UInt8 = 0x01
        static let hasSignature: UInt8 = 0x02
        static let isCompressed: UInt8 = 0x04
    }

    // MARK: - Encode

    /// Encode a transaction into compact binary format.
    public static func encode(_ txn: AstralTransaction) -> Data? {
        // Build the transaction payload as JSON (simple for v1)
        let payloadDict: [String: Any] = [
            "txn_id": txn.id,
            "amount": txn.amount,
            "sender": txn.senderID.id,
            "recipient": txn.recipientID.id,
            "ts": Int(txn.timestamp.timeIntervalSince1970 * 1000)
        ]

        guard let payloadData = try? JSONSerialization.data(withJSONObject: payloadDict) else {
            return nil
        }

        guard payloadData.count <= 65535 else { return nil }  // UInt16 max

        var data = Data()
        data.reserveCapacity(headerSize + idSize * 2 + payloadData.count)

        // Version
        data.append(1)

        // Type
        data.append(MessageType.transaction.rawValue)

        // TTL
        data.append(3)

        // Timestamp (UInt64, big-endian)
        let ts = UInt64(txn.timestamp.timeIntervalSince1970 * 1000)
        for shift in stride(from: 56, through: 0, by: -8) {
            data.append(UInt8((ts >> UInt64(shift)) & 0xFF))
        }

        // Flags
        let flags: UInt8 = Flags.hasRecipient  // Always include recipient for transactions
        data.append(flags)

        // Payload length (UInt16, big-endian)
        let payloadLen = UInt16(payloadData.count)
        data.append(UInt8(payloadLen >> 8))
        data.append(UInt8(payloadLen & 0xFF))

        // Sender ID (8 bytes)
        if let senderData = txn.senderID.routingData {
            data.append(senderData)
        } else {
            data.append(Data(repeating: 0, count: idSize))
        }

        // Recipient ID (8 bytes)
        if let recipientData = txn.recipientID.routingData {
            data.append(recipientData)
        } else {
            data.append(Data(repeating: 0, count: idSize))
        }

        // Payload
        data.append(payloadData)

        return data
    }

    // MARK: - Decode

    /// Decode binary data back into a transaction.
    public static func decode(_ data: Data) -> AstralTransaction? {
        guard data.count >= headerSize + idSize else { return nil }

        var offset = 0

        // Version
        let version = data[offset]; offset += 1
        guard version == 1 else { return nil }

        // Type
        let type = data[offset]; offset += 1
        guard type == MessageType.transaction.rawValue else { return nil }

        // TTL (skip for decoding)
        _ = data[offset]; offset += 1

        // Timestamp
        var ts: UInt64 = 0
        for _ in 0..<8 {
            ts = (ts << 8) | UInt64(data[offset])
            offset += 1
        }

        // Flags
        let flags = data[offset]; offset += 1
        let hasRecipient = (flags & Flags.hasRecipient) != 0

        // Payload length
        let payloadLen = Int(UInt16(data[offset]) << 8 | UInt16(data[offset + 1]))
        offset += 2

        // Sender ID (8 bytes)
        guard offset + idSize <= data.count else { return nil }
        let _ = data[offset..<offset + idSize]  // senderData (extracted from header)
        offset += idSize

        // Recipient ID (8 bytes, optional)
        if hasRecipient {
            guard offset + idSize <= data.count else { return nil }
            offset += idSize
        }

        // Payload
        guard offset + payloadLen <= data.count else { return nil }
        let payloadData = data[offset..<offset + payloadLen]

        // Parse JSON payload
        guard let json = try? JSONSerialization.jsonObject(with: Data(payloadData)) as? [String: Any],
              let txnId = json["txn_id"] as? String,
              let amount = json["amount"] as? Int,
              let sender = json["sender"] as? String,
              let recipient = json["recipient"] as? String else {
            return nil
        }

        return AstralTransaction(
            id: txnId,
            amount: amount,
            senderID: WalletID(id: sender),
            recipientID: WalletID(id: recipient),
            timestamp: Date(timeIntervalSince1970: TimeInterval(ts) / 1000.0),
            status: .delivered
        )
    }
}
