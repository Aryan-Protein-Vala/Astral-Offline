import Foundation

/// Result type for the verification callback.
/// Transport uses this to send ACK (verified) or NACK (rejected) back to the sender.
public enum VerificationResult {
    case verified    // All checks passed → send ACK to sender
    case rejected(AstralError)  // Failed → send NACK with reason
}

/// Abstract transport protocol — any payment delivery mechanism conforms to this.
///
/// Security contract:
/// - Transports carry ENCRYPTED bytes — they never see plaintext.
/// - Transports MUST NOT auto-ACK. They call `onRawDataReceived` with a
///   response callback. The SDK verifies/decrypts, then calls the callback
///   with `.verified` (send ACK) or `.rejected` (send NACK).
public protocol AstralTransport: AnyObject {

    /// Transport kind identifier.
    var kind: TransportKind { get }

    /// Whether this transport is currently ready to send/receive.
    var isAvailable: Bool { get }

    /// Raw encrypted data callback WITH response callback.
    ///
    /// When a transport receives data:
    /// 1. It calls `onRawDataReceived?(data, responseCallback)`
    /// 2. SDK decrypts → verifies signature → checks dedup
    /// 3. SDK calls `responseCallback(.verified)` or `responseCallback(.rejected(error))`
    /// 4. Transport sends ACK or NACK back to the sender
    ///
    /// The transport MUST NOT send any application-level acknowledgment
    /// until the responseCallback is invoked.
    var onRawDataReceived: ((_ data: Data, _ respond: @escaping (VerificationResult) -> Void) -> Void)? { get set }

    /// Callback for transport status changes.
    var onStatusChanged: ((Bool) -> Void)? { get set }

    // MARK: - Lifecycle

    func start()
    func stop()

    // MARK: - Sending

    /// Send an ENCRYPTED packet to a specific recipient.
    func send(
        packet: Data,
        to recipientID: WalletID,
        completion: @escaping (Result<Void, AstralError>) -> Void
    )

    /// Check if a specific recipient is reachable via this transport.
    func isReachable(_ recipientID: WalletID) -> Bool

    // MARK: - Merchant Listening

    func startListening(as walletID: WalletID)
    func stopListening()
}

/// Identifies which transport type this is for priority ordering.
public enum TransportKind: Int, Comparable, Sendable {
    case ble = 0
    case relay = 1

    public static func < (lhs: TransportKind, rhs: TransportKind) -> Bool {
        lhs.rawValue < rhs.rawValue
    }
}
