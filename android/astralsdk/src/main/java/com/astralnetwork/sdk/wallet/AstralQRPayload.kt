package com.astralnetwork.sdk.wallet

import com.astralnetwork.sdk.identity.WalletID
import org.json.JSONObject

/**
 * AstralQRPayload — data encoded in merchant QR codes.
 * Includes wallet ID and public key for encrypted payments.
 *
 * CROSS-PLATFORM FORMAT (must match iOS AstralQRPayload.swift):
 *
 * JSON format:
 *   {"type":"astral_pay","id":"<wallet_id>","pk":"<base64_pubkey>","v":1}
 *
 * URL format:
 *   astral://pay?id=<wallet_id>&pk=<base64_pubkey>&amount=<paisa>&relay=<url>
 */
data class AstralQRPayload(
    val type: String = "astral_pay",
    val walletID: WalletID,
    val publicKey: String? = null,      // Base64 encoded X.509 public key
    val amount: Int? = null,            // Pre-filled amount (paisa), optional
    val relayEndpoint: String? = null   // For server-assisted delivery
) {
    /**
     * Serialize to JSON string for QR code encoding.
     * Keys MUST match iOS: "type", "id", "pk", "v", "amount", "relay"
     */
    fun toJSONString(): String {
        return JSONObject().apply {
            put("type", type)
            put("id", walletID.id)        // ← was "wallet_id", must be "id" to match iOS
            publicKey?.let { put("pk", it) }  // ← was "public_key", must be "pk" to match iOS
            put("v", 1)
            amount?.let { put("amount", it) }
            relayEndpoint?.let { put("relay", it) }
        }.toString()
    }

    /**
     * Encode as astral:// URL string (for printed QR codes).
     * Params MUST match iOS: id, pk, amount, relay
     */
    fun toURLString(): String {
        val sb = StringBuilder("astral://pay?id=${walletID.id}")
        publicKey?.let { sb.append("&pk=$it") }
        amount?.let { sb.append("&amount=$it") }
        relayEndpoint?.let { sb.append("&relay=$it") }
        return sb.toString()
    }

    companion object {
        /**
         * Parse from scanned QR code string.
         * Accepts BOTH iOS and Android key formats for backwards compatibility.
         */
        fun parse(from: String): AstralQRPayload? {
            // Try JSON format first
            try {
                val json = JSONObject(from)
                val type = json.optString("type", "")
                if (type == "astral_pay" || type == "astral_wallet") {
                    // Accept both iOS keys ("id","pk") and legacy Android keys ("wallet_id","public_key")
                    val id = json.optString("id",
                             json.optString("wallet_id",
                             json.optString("pub_key", "")))
                    val pk = json.optString("pk",
                             json.optString("public_key", null))

                    if (id.isNotEmpty()) {
                        return AstralQRPayload(
                            type = type,
                            walletID = WalletID(id),
                            publicKey = pk,
                            amount = if (json.has("amount")) json.getInt("amount") else null,
                            relayEndpoint = json.optString("relay", null)
                        )
                    }
                }
            } catch (_: Exception) {}

            // Try URL format: astral://pay?id=...&pk=...
            if (from.startsWith("astral://pay")) {
                val queryString = from.substringAfter("?", "")
                if (queryString.isEmpty()) return null

                val params = mutableMapOf<String, String>()
                for (part in queryString.split("&")) {
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2) params[kv[0]] = kv[1]
                }

                // Accept both iOS params (id, pk) and legacy (wallet, pub)
                val id = params["id"] ?: params["wallet"] ?: return null
                val pk = params["pk"] ?: params["pub"]

                return AstralQRPayload(
                    walletID = WalletID(id),
                    publicKey = pk,
                    amount = params["amount"]?.toIntOrNull(),
                    relayEndpoint = params["relay"]
                )
            }

            return null
        }
    }
}
