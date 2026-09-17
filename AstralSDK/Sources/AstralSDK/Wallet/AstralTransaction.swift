import Foundation

/// Core transaction model for all Astral payments.
public struct AstralTransaction: Codable, Sendable {
    public var id: String
    public var amount: Int           // In paisa (₹1 = 100)
    public var senderID: WalletID
    public var recipientID: WalletID
    public var timestamp: Date
    public var status: Status

    public enum Status: String, Codable, Sendable {
        case pending    // Created, not yet sent
        case sent       // Transmitted via transport
        case delivered  // Delivery confirmed by recipient
        case failed     // Sending failed after retries
        case queued     // Stored locally for later retry
    }

    public init(
        id: String,
        amount: Int,
        senderID: WalletID,
        recipientID: WalletID,
        timestamp: Date,
        status: Status
    ) {
        self.id = id
        self.amount = amount
        self.senderID = senderID
        self.recipientID = recipientID
        self.timestamp = timestamp
        self.status = status
    }
}
