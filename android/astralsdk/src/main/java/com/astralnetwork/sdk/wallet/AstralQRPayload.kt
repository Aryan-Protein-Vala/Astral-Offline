package com.astralnetwork.sdk.wallet

import android.util.Base64
import com.astralnetwork.sdk.identity.WalletID
import org.json.JSONObject

/**
 * AstralQRPayload — what gets embedded in a QR code for payment initiation.
 *
 * Contains:
 *  - walletID: for routing / display
 *  - ecdhPublicKey: the X9.62 ECDH key the payer uses to encrypt the payment
 *  - amount (optional): pre-set amount for fixed-price requests
 *
 * JSON format (compact, cross-platform compatible with iOS):
 *   {"v":2,"id":"<wallet_id>","pk":"<base64_ecdh_pubkey>","amt":5000}
 *
 * The `pk` field is a base64-encoded 65-byte X9.62 uncompressed public key.
 * Cross-platform with iOS CryptoKit P256.KeyAgreement.PublicKey.
 */
data class AstralQRPayload(
    val walletID: WalletID,
    val ecdhPublicKeyBase64: String?,   // 65-byte X9.62 key, base64
    val amountPaisa: Long? = null,      // Optional fixed amount
    val relayEndpoint: String? = null
) {
    val publicKey: String? get() = ecdhPublicKeyBase64
    val amount: Int? get() = amountPaisa?.toInt()

    companion object {
        private const val VERSION = 2

        fun parse(qrString: String): AstralQRPayload? = try {
            if (qrString.startsWith("astral://pay")) {
                // Parse URL scheme
                val uri = android.net.Uri.parse(qrString)
                val id = uri.getQueryParameter("id") ?: return null
                val pk = uri.getQueryParameter("pk")
                val amt = uri.getQueryParameter("amount")?.toLongOrNull()
                AstralQRPayload(
                    walletID = WalletID(id),
                    ecdhPublicKeyBase64 = pk,
                    amountPaisa = amt
                )
            } else {
                val json = JSONObject(qrString)
                if (json.has("v") && json.getInt("v") != VERSION) return null
                val id = if (json.has("id")) json.getString("id") else json.optString("wallet_id", "")
                if (id.isEmpty()) return null
                val pk = json.optString("pk").ifEmpty { json.optString("public_key") }.takeIf { it.isNotEmpty() }
                val amt = if (json.has("amt")) json.getLong("amt") else if (json.has("amount")) json.getLong("amount") else null
                AstralQRPayload(
                    walletID = WalletID(id),
                    ecdhPublicKeyBase64 = pk,
                    amountPaisa = amt,
                    relayEndpoint = json.optString("relay").takeIf { it.isNotEmpty() }
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("v", VERSION)
        put("id", walletID.id)
        ecdhPublicKeyBase64?.let { put("pk", it) }
        amountPaisa?.let { put("amt", it) }
        relayEndpoint?.let { put("relay", it) }
    }.toString()

    fun toJSONString(): String = toJson()

    fun toURLString(): String {
        val base = "astral://pay?id=${walletID.id}"
        val withPk = if (ecdhPublicKeyBase64 != null) "$base&pk=$ecdhPublicKeyBase64" else base
        val withAmt = if (amountPaisa != null) "$withPk&amount=$amountPaisa" else withPk
        return withAmt
    }

    /** Returns the raw ECDH public key bytes (65 bytes, X9.62). */
    fun getEcdhPublicKeyBytes(): ByteArray? {
        return ecdhPublicKeyBase64?.let { Base64.decode(it, Base64.NO_WRAP) }
    }
}
