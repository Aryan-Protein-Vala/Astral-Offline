import Foundation

/// All recoverable errors in AstralSDK.
public enum AstralError: Error, Sendable, CustomStringConvertible {
    case notInitialized
    case keyGenerationFailed
    case signingFailed
    case verificationFailed
    case noTransportAvailable
    case bleUnavailable
    case relayUnavailable
    case packetEncodingFailed
    case packetDecodingFailed
    case invalidQRCode
    case paymentFailed(String)
    case timeout
    case duplicate

    public var description: String {
        switch self {
        case .notInitialized:       return "SDK not initialized — call start() first"
        case .keyGenerationFailed:  return "Failed to generate keypair"
        case .signingFailed:        return "Transaction signing failed"
        case .verificationFailed:   return "Signature verification failed"
        case .noTransportAvailable: return "No transport available (BLE/Relay/Queue)"
        case .bleUnavailable:       return "Bluetooth is not available"
        case .relayUnavailable:     return "Relay server is unreachable"
        case .packetEncodingFailed: return "Failed to encode packet"
        case .packetDecodingFailed: return "Failed to decode packet"
        case .invalidQRCode:        return "Invalid or unrecognized QR code"
        case .paymentFailed(let m): return "Payment failed: \(m)"
        case .timeout:              return "Operation timed out"
        case .duplicate:            return "Duplicate transaction"
        }
    }
}
