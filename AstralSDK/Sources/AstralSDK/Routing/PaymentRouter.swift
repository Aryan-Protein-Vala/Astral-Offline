import Foundation

/// PaymentRouter — Selects the best transport and routes payments.
///
/// Priority order: BLE (offline, private) → Relay (online) → Queue (retry later).
/// Modeled on bitchat's `MessageRouter` with outbox queuing and TTL eviction.
public final class PaymentRouter {

    // MARK: - Private State

    private var transports: [AstralTransport] = []
    private let config: AstralConfig

    /// Outbox: queued payments for offline/unreachable recipients.
    private var outbox: [WalletID: [QueuedPayment]] = [:]

    private struct QueuedPayment {
        let packet: Data
        let transaction: AstralTransaction
        let enqueuedAt: Date
    }

    // MARK: - Init

    init(config: AstralConfig) {
        self.config = config
    }

    /// Register a transport for routing.
    func registerTransport(_ transport: AstralTransport) {
        transports.append(transport)
        // Sort by priority (BLE first)
        transports.sort { $0.kind < $1.kind }
    }

    // MARK: - Routing

    /// Route a signed payment packet to the best available transport.
    ///
    /// Logic:
    /// 1. Try each transport in priority order (BLE → Relay)
    /// 2. If a transport can reach the recipient, send via that transport
    /// 3. If no transport is available, queue for later
    func route(
        _ packet: Data,
        for txn: AstralTransaction,
        completion: @escaping (Result<Void, AstralError>) -> Void
    ) {
        // 1. Find a reachable transport
        if let transport = reachableTransport(for: txn.recipientID) {
            transport.send(packet: packet, to: txn.recipientID) { result in
                completion(result)
            }
            return
        }

        // 2. Try any available transport (even if recipient not specifically "reachable")
        if let transport = availableTransport() {
            transport.send(packet: packet, to: txn.recipientID) { result in
                completion(result)
            }
            return
        }

        // 3. Queue for later
        enqueue(packet: packet, txn: txn)
        completion(.failure(.noTransportAvailable))
    }

    // MARK: - Transport Selection

    /// Find the first transport where the recipient is reachable.
    private func reachableTransport(for recipientID: WalletID) -> AstralTransport? {
        transports.first { $0.isAvailable && $0.isReachable(recipientID) }
    }

    /// Find any available transport regardless of specific reachability.
    private func availableTransport() -> AstralTransport? {
        transports.first { $0.isAvailable }
    }

    // MARK: - Outbox

    private func enqueue(packet: Data, txn: AstralTransaction) {
        let queued = QueuedPayment(packet: packet, transaction: txn, enqueuedAt: Date())

        if outbox[txn.recipientID] == nil {
            outbox[txn.recipientID] = []
        }
        outbox[txn.recipientID]?.append(queued)

        // Enforce per-recipient limit (FIFO eviction)
        if let count = outbox[txn.recipientID]?.count, count > config.outboxMaxPerRecipient {
            outbox[txn.recipientID]?.removeFirst()
        }
    }

    /// Flush queued payments for a specific recipient (called when transport becomes available).
    public func flushOutbox(for recipientID: WalletID) {
        guard let queued = outbox[recipientID], !queued.isEmpty else { return }

        let now = Date()
        var remaining: [QueuedPayment] = []

        for payment in queued {
            // Skip expired (TTL exceeded)
            if now.timeIntervalSince(payment.enqueuedAt) > config.outboxTTLSeconds {
                continue
            }

            if let transport = reachableTransport(for: recipientID) ?? availableTransport() {
                transport.send(packet: payment.packet, to: recipientID) { _ in }
            } else {
                remaining.append(payment)
            }
        }

        if remaining.isEmpty {
            outbox.removeValue(forKey: recipientID)
        } else {
            outbox[recipientID] = remaining
        }
    }

    /// Flush all queued payments.
    public func flushAllOutbox() {
        for id in Array(outbox.keys) {
            flushOutbox(for: id)
        }
    }

    /// Clean up expired queued payments.
    public func cleanupExpired() {
        let now = Date()
        for id in Array(outbox.keys) {
            outbox[id]?.removeAll { now.timeIntervalSince($0.enqueuedAt) > config.outboxTTLSeconds }
            if outbox[id]?.isEmpty == true {
                outbox.removeValue(forKey: id)
            }
        }
    }

    /// Number of queued payments across all recipients.
    public var queuedCount: Int {
        outbox.values.reduce(0) { $0 + $1.count }
    }
}
