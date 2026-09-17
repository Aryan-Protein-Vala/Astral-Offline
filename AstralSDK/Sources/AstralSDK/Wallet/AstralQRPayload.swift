import Foundation

/// QR code payload for Astral payments.
///
/// Supports two formats:
/// - **JSON** (for display between apps)
/// - **URL** (`astral://pay?id=...&pk=...&relay=...&amount=...`)
public struct AstralQRPayload: Codable, Sendable {

    /// Merchant wallet identity.
    public let walletID: WalletID

    /// Base64-encoded public key (for signature verification).
    public let publicKey: String

    /// Optional fixed amount in paisa (nil = open amount).
    public let amount: Int?

    /// Optional relay server endpoint for online fallback.
    public let relayEndpoint: String?

    // MARK: - Encode to URL

    /// Encode as `astral://` URL string (for printed QR codes).
    public func toURLString() -> String {
        var components = URLComponents()
        components.scheme = "astral"
        components.host = "pay"

        var queryItems: [URLQueryItem] = [
            URLQueryItem(name: "id", value: walletID.id),
            URLQueryItem(name: "pk", value: publicKey)
        ]

        if let amount = amount {
            queryItems.append(URLQueryItem(name: "amount", value: "\(amount)"))
        }
        if let relay = relayEndpoint {
            queryItems.append(URLQueryItem(name: "relay", value: relay))
        }

        components.queryItems = queryItems
        return components.string ?? "astral://pay?id=\(walletID.id)&pk=\(publicKey)"
    }

    /// Encode as JSON string (for dynamic QR codes in-app).
    public func toJSONString() -> String {
        var dict: [String: Any] = [
            "type": "astral_pay",
            "id": walletID.id,
            "pk": publicKey,
            "v": 1
        ]
        if let amount = amount { dict["amount"] = amount }
        if let relay = relayEndpoint { dict["relay"] = relay }

        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let str = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return str
    }

    // MARK: - Parse

    /// Parse a scanned QR code (accepts both URL and JSON formats).
    public static func parse(from data: String) -> AstralQRPayload? {
        // Try URL format first: astral://pay?id=...
        if data.hasPrefix("astral://pay") {
            return parseURL(data)
        }

        // Try JSON format: {"type":"astral_pay",...}
        if data.contains("astral_pay") || data.contains("astral_wallet") {
            return parseJSON(data)
        }

        return nil
    }

    private static func parseURL(_ urlString: String) -> AstralQRPayload? {
        guard let components = URLComponents(string: urlString) else { return nil }
        let items = components.queryItems ?? []

        // Accept both iOS params (id, pk) and Android legacy (wallet, pub)
        let id = items.first(where: { $0.name == "id" })?.value
              ?? items.first(where: { $0.name == "wallet" })?.value
        let pk = items.first(where: { $0.name == "pk" })?.value
              ?? items.first(where: { $0.name == "pub" })?.value

        guard let id = id, let pk = pk else { return nil }

        let amount = items.first(where: { $0.name == "amount" })?.value.flatMap { Int($0) }
        let relay = items.first(where: { $0.name == "relay" })?.value

        return AstralQRPayload(
            walletID: WalletID(id: id),
            publicKey: pk,
            amount: amount,
            relayEndpoint: relay
        )
    }

    private static func parseJSON(_ jsonString: String) -> AstralQRPayload? {
        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }

        // Support both iOS keys ("id","pk") and Android legacy keys ("wallet_id","public_key")
        let id = json["id"] as? String ?? json["wallet_id"] as? String ?? ""
        let pk = json["pk"] as? String ?? json["public_key"] as? String ?? json["pub_key"] as? String ?? ""
        let amount = json["amount"] as? Int
        let relay = json["relay"] as? String

        guard !id.isEmpty || !pk.isEmpty else { return nil }

        // If `id` is empty, derive from pk
        let walletID: WalletID
        if !id.isEmpty {
            walletID = WalletID(id: id)
        } else if let pkData = Data(base64Encoded: pk) {
            walletID = WalletID(publicKey: pkData)
        } else {
            return nil
        }

        return AstralQRPayload(
            walletID: walletID,
            publicKey: pk,
            amount: amount,
            relayEndpoint: relay
        )
    }
}
