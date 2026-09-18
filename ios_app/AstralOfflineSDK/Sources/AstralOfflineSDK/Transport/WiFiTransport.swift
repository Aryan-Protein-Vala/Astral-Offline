import Foundation
import Network
import os.log

/**
 * WiFiTransport — Cross-platform local WiFi mesh via Bonjour / Network.framework.
 *
 * Uses Apple's Network.framework which is cross-platform with:
 *   - Android NSD (Network Service Discovery) via _astral._tcp. mDNS service type
 *   - Linux Avahi with the same service type
 *   - macOS (same framework — zero changes needed)
 *
 * This means an iPhone, MacBook, and Android phone on the same WiFi network
 * can all discover each other and exchange Astral packets automatically.
 *
 * Protocol: TCP + 4-byte length-prefixed frames (identical to Android WiFiTransport).
 */
final class WiFiTransport: NSObject {

    private let log = Logger(subsystem: "com.astralnetwork.sdk", category: "WiFiTransport")

    private static let serviceType = "_astral._tcp."
    private static let port: UInt16 = 47823

    private var listener: NWListener?
    private var browser: NWBrowser?
    private var connections: [NWConnection] = []

    var onPacketReceived: ((Data) -> Void)?
    var onPeerDiscovered: ((NWEndpoint) -> Void)?

    // ── Advertising (so other platforms can find us) ────────────────────────────

    func startBonjourAdvertising(walletID: String) {
        do {
            let params = NWParameters.tcp
            listener = try NWListener(using: params, on: NWEndpoint.Port(integerLiteral: Self.port))
            listener?.service = NWListener.Service(type: Self.serviceType)

            listener?.newConnectionHandler = { [weak self] conn in
                self?.handleIncomingConnection(conn)
            }
            listener?.start(queue: .global())
            log.info("Bonjour TCP server started on port \(Self.port) ✅")
        } catch {
            log.error("Failed to start Bonjour listener: \(error.localizedDescription)")
        }
    }

    // ── Discovery (find Android / MacBook / other iOS peers) ───────────────────

    func startBonjourDiscovery() {
        let params = NWParameters()
        params.includePeerToPeer = true  // Include nearby devices even without AP

        browser = NWBrowser(for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil), using: params)
        browser?.browseResultsChangedHandler = { [weak self] results, _ in
            for result in results {
                if case .add = result.change {
                    self?.log.info("Peer discovered: \(result.endpoint.debugDescription)")
                    self?.onPeerDiscovered?(result.endpoint)
                }
            }
        }
        browser?.start(queue: .global())
        log.info("Bonjour discovery started for \(Self.serviceType)")
    }

    func stop() {
        listener?.cancel()
        browser?.cancel()
        connections.forEach { $0.cancel() }
        connections.removeAll()
    }

    // ── Sending ─────────────────────────────────────────────────────────────────

    func sendToDiscoveredPeers(_ data: Data) {
        connections.forEach { sendOnConnection($0, data: data) }
    }

    func send(to endpoint: NWEndpoint, data: Data) {
        let conn = NWConnection(to: endpoint, using: .tcp)
        conn.start(queue: .global())
        conn.stateUpdateHandler = { [weak self] state in
            if case .ready = state {
                self?.sendOnConnection(conn, data: data)
            }
        }
    }

    // ── Incoming ─────────────────────────────────────────────────────────────────

    private func handleIncomingConnection(_ conn: NWConnection) {
        connections.append(conn)
        conn.start(queue: .global())

        // Read 4-byte length prefix then read the full payload
        conn.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, _, error in
            guard let data = data, data.count == 4 else { return }
            let length = Int(UInt32(bigEndian: data.withUnsafeBytes { $0.load(as: UInt32.self) }))

            conn.receive(minimumIncompleteLength: length, maximumLength: length) { [weak self] payload, _, _, error in
                guard let payload = payload else { return }
                self?.log.info("Received \(payload.count) bytes via WiFi")
                self?.onPacketReceived?(payload)
            }
        }
    }

    private func sendOnConnection(_ conn: NWConnection, data: Data) {
        var length = UInt32(data.count).bigEndian
        var frameData = Data(bytes: &length, count: 4)
        frameData.append(data)
        conn.send(content: frameData, completion: .contentProcessed { [weak self] error in
            if let error = error {
                self?.log.error("WiFi send error: \(error.localizedDescription)")
            }
        })
    }
}
