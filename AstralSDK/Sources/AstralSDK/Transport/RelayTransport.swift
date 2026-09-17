import Foundation

/// RelayTransport — HTTP relay via Supabase Edge Functions.
///
/// Security: Carries ENCRYPTED bytes only — never sees plaintext.
/// Uses `onRawDataReceived` for secure data delivery to SDK.
public final class RelayTransport: AstralTransport {

    public let kind: TransportKind = .relay

    public var isAvailable: Bool {
        config.relayEndpoint != nil
    }


    public var onRawDataReceived: ((_ data: Data, _ respond: @escaping (VerificationResult) -> Void) -> Void)?
    public var onStatusChanged: ((Bool) -> Void)?

    private let config: AstralConfig
    private var myWalletID: WalletID?
    private var pollTimer: Timer?
    private let session: URLSession

    public init(config: AstralConfig = .default) {
        self.config = config
        self.session = URLSession(configuration: .default)
    }

    public func start() {
        onStatusChanged?(isAvailable)
    }

    public func stop() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    public func send(
        packet: Data,
        to recipientID: WalletID,
        completion: @escaping (Result<Void, AstralError>) -> Void
    ) {
        guard let endpoint = config.relayEndpoint else {
            completion(.failure(.relayUnavailable))
            return
        }

        guard let url = URL(string: "\(endpoint)/functions/v1/relay-push") else {
            completion(.failure(.relayUnavailable))
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let anonKey = config.relayAnonKey {
            request.setValue("Bearer \(anonKey)", forHTTPHeaderField: "Authorization")
        }

        let body: [String: Any] = [
            "recipient_id": recipientID.id,
            "sender_id": myWalletID?.id ?? "",
            "payload": packet.base64EncodedString(),
            "timestamp": Int(Date().timeIntervalSince1970 * 1000)
        ]

        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        let task = session.dataTask(with: request) { data, response, error in
            if let error = error {
                completion(.failure(.paymentFailed(error.localizedDescription)))
                return
            }
            guard let httpResponse = response as? HTTPURLResponse,
                  (200...299).contains(httpResponse.statusCode) else {
                completion(.failure(.relayUnavailable))
                return
            }
            completion(.success(()))
        }
        task.resume()
    }

    public func isReachable(_ recipientID: WalletID) -> Bool {
        return isAvailable
    }

    public func startListening(as walletID: WalletID) {
        myWalletID = walletID
        startPolling()
    }

    public func stopListening() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    private func startPolling() {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: config.relayPollInterval, repeats: true) { [weak self] _ in
            self?.pollForPayments()
        }
        pollForPayments()
    }

    private func pollForPayments() {
        guard let endpoint = config.relayEndpoint,
              let walletID = myWalletID,
              let url = URL(string: "\(endpoint)/functions/v1/relay-poll?merchant_id=\(walletID.id)") else {
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        if let anonKey = config.relayAnonKey {
            request.setValue("Bearer \(anonKey)", forHTTPHeaderField: "Authorization")
        }

        let task = session.dataTask(with: request) { [weak self] data, response, error in
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
                return
            }

            for entry in json {
                guard let payloadBase64 = entry["payload"] as? String,
                      let payloadData = Data(base64Encoded: payloadBase64) else {
                    continue
                }

                // Forward ENCRYPTED bytes to SDK for verification pipeline.
                // Relay doesn't need ACK/NACK (HTTP response handles that).
                // The respond closure is a no-op for relay but required by protocol.
                self?.onRawDataReceived?(payloadData) { _ in
                    // HTTP relay: ACK is implicit via 200 response.
                    // No BLE-style ACK/NACK needed.
                }
            }
        }
        task.resume()
    }
}
