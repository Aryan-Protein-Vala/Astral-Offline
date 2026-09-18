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
    val amountPaisa: Long? = null       // Optional fixed amount
) {
    companion object {
        private const val VERSION = 2

        fun parse(qrString: String): AstralQRPayload? = try {
            val json = JSONObject(qrString)
            if (json.getInt("v") != VERSION) return null
            AstralQRPayload(
                walletID = WalletID(json.getString("id")),
                ecdhPublicKeyBase64 = json.optString("pk").takeIf { it.isNotEmpty() },
                amountPaisa = if (json.has("amt")) json.getLong("amt") else null
            )
        } catch (e: Exception) { null }
    }

    fun toJson(): String = JSONObject().apply {
        put("v", VERSION)
        put("id", walletID.id)
        ecdhPublicKeyBase64?.let { put("pk", it) }
        amountPaisa?.let { put("amt", it) }
    }.toString()

    /** Returns the raw ECDH public key bytes (65 bytes, X9.62). */
    fun getEcdhPublicKeyBytes(): ByteArray? {
        return ecdhPublicKeyBase64?.let { Base64.decode(it, Base64.NO_WRAP) }
    }
}
