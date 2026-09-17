import Foundation

/// Centralized configuration for AstralSDK.
/// Modeled after bitchat's `TransportConfig` — all tunable constants in one place.
public struct AstralConfig: Sendable {

    // MARK: - BLE Transport

    /// BLE service UUID for Astral payment mesh.
    public var bleServiceUUID: String = "0000A57F-0000-1000-8000-00805F9B34FB"

    /// Maximum BLE fragment size (bytes). ~512 MTU minus overhead.
    public var bleFragmentSize: Int = 469

    /// Default TTL for mesh packet relay.
    public var meshTTL: UInt8 = 3

    /// BLE scan duty-cycle ON duration (seconds).
    public var bleDutyOnDuration: TimeInterval = 5.0

    /// BLE scan duty-cycle OFF duration (seconds).
    public var bleDutyOffDuration: TimeInterval = 10.0

    /// Merchant BLE advertisement interval (seconds).
    public var bleAdvertiseInterval: TimeInterval = 1.0

    /// Max BLE central connections.
    public var bleMaxConnections: Int = 6

    /// BLE connect rate-limit interval (seconds).
    public var bleConnectRateLimit: TimeInterval = 0.5

    /// Fragment spacing to prevent BLE buffer overflow (ms).
    public var bleFragmentSpacingMs: Int = 30

    // MARK: - Relay Transport

    /// Supabase relay endpoint. `nil` means relay is disabled.
    public var relayEndpoint: String? = nil

    /// Supabase anon key for relay auth.
    public var relayAnonKey: String? = nil

    /// Relay poll interval for merchants (seconds). Used when Realtime is unavailable.
    public var relayPollInterval: TimeInterval = 5.0

    // MARK: - Payment Router

    /// Timeout for BLE discovery before falling back to relay (seconds).
    public var bleScanTimeout: TimeInterval = 2.0

    /// Max queued payments per recipient (outbox limit).
    public var outboxMaxPerRecipient: Int = 100

    /// Queued payment TTL (seconds). Default 24 hours.
    public var outboxTTLSeconds: TimeInterval = 86400

    /// How long to wait for merchant ACK after sending payment (seconds).
    /// If not received in time, customer gets a timeout error.
    public var paymentTimeoutSeconds: TimeInterval = 10.0

    // MARK: - Crypto

    /// Use Secure Enclave for key storage (iOS). Falls back to Keychain if unavailable.
    public var useSecureEnclave: Bool = true

    /// Keychain service identifier.
    public var keychainService: String = "com.astralnetwork.sdk"

    // MARK: - Protocol

    /// Protocol version for wire format.
    public var protocolVersion: UInt8 = 1

    /// Enable LZ4 compression for payloads > this threshold (bytes).
    public var compressionThreshold: Int = 100

    /// Enable PKCS#7 padding on packets for traffic analysis resistance.
    public var enablePadding: Bool = true

    // MARK: - Deduplication

    /// Max age for transaction dedup cache (seconds).
    public var dedupMaxAge: TimeInterval = 300

    /// Max entries in dedup cache.
    public var dedupMaxCount: Int = 1000

    // MARK: - Presets

    public static let `default` = AstralConfig()

    public static var testnet: AstralConfig {
        var c = AstralConfig()
        c.bleServiceUUID = "0000A57F-0000-1000-8000-00805F9B34FA"  // Different UUID for testnet
        c.meshTTL = 2
        c.outboxTTLSeconds = 3600  // 1 hour for testing
        return c
    }
}
