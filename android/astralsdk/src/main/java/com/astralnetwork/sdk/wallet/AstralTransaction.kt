package com.astralnetwork.sdk.wallet

import com.astralnetwork.sdk.identity.WalletID
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AstralTransaction — single payment record.
 * Mirrors iOS AstralTransaction struct.
 *
 * ENCODING — CROSS-PLATFORM ALIGNED:
 *   encode() produces the EXACT binary format of iOS AstralPacket.encode():
 *
 *   [Version:1][Type:1][TTL:1][Timestamp:8][Flags:1][PayloadLen:2]
 *   [SenderID:8][RecipientID:8][JSON Payload]
 *
 *   Total header = 14 bytes + 8 (sender) + 8 (recipient) = 30 bytes before JSON.
 *   ALL multi-byte integers are Big-Endian.
 *
 *   This is what gets SIGNED by the sender — so both platforms must produce
 *   identical binary for the same transaction data.
 */
data class AstralTransaction(
    val id: String,
    val senderID: WalletID,
    val recipientID: WalletID,
    val amount: Int,           // In paisa
    val timestamp: Long,       // Unix millis
    var status: Status = Status.PENDING
) {
    enum class Status { PENDING, SENT, RECEIVED, FAILED }

    // ── AstralPacket Constants (matching iOS) ──
    companion object {
        private const val VERSION: Byte = 1
        private const val TYPE_TRANSACTION: Byte = 0x01
        private const val TTL: Byte = 3
        private const val FLAG_HAS_RECIPIENT: Byte = 0x01
        private const val HEADER_SIZE = 14   // version(1)+type(1)+ttl(1)+ts(8)+flags(1)+payloadLen(2)
        private const val ID_SIZE = 8        // 8-byte wallet ID for routing

        /** Decode from binary AstralPacket format. */
        fun decode(data: ByteArray): AstralTransaction? {
            return try {
                if (data.size < HEADER_SIZE + ID_SIZE) return null
                val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

                // Version
                val version = buf.get()
                if (version != VERSION) return null

                // Type
                val type = buf.get()
                if (type != TYPE_TRANSACTION) return null

                // TTL (skip)
                buf.get()

                // Timestamp (UInt64 big-endian)
                val ts = buf.long

                // Flags
                val flags = buf.get()
                val hasRecipient = (flags.toInt() and FLAG_HAS_RECIPIENT.toInt()) != 0

                // Payload length
                val payloadLen = buf.short.toInt() and 0xFFFF

                // Sender ID (8 bytes)
                val senderBytes = ByteArray(ID_SIZE)
                buf.get(senderBytes)

                // Recipient ID (8 bytes, optional)
                if (hasRecipient) {
                    val recipientBytes = ByteArray(ID_SIZE)
                    buf.get(recipientBytes)
                }

                // JSON Payload
                if (buf.remaining() < payloadLen) return null
                val payloadBytes = ByteArray(payloadLen)
                buf.get(payloadBytes)

                val json = JSONObject(String(payloadBytes, Charsets.UTF_8))

                AstralTransaction(
                    id = json.getString("txn_id"),
                    senderID = WalletID(json.getString("sender")),
                    recipientID = WalletID(json.getString("recipient")),
                    amount = json.getInt("amount"),
                    timestamp = json.optLong("ts", ts),
                    status = Status.RECEIVED
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Encode to AstralPacket binary format (matches iOS AstralPacket.encode()).
     *
     * Layout: [Version:1][Type:1][TTL:1][Timestamp:8][Flags:1][PayloadLen:2]
     *         [SenderID:8][RecipientID:8][JSON Payload]
     */
    fun encode(): ByteArray {
        // Build JSON payload portion (matches iOS payload dict)
        val payloadJson = JSONObject().apply {
            put("txn_id", id)
            put("amount", amount)
            put("sender", senderID.id)
            put("recipient", recipientID.id)
            put("ts", timestamp)
        }
        val payloadBytes = payloadJson.toString().toByteArray(Charsets.UTF_8)

        // Build binary packet
        val totalSize = HEADER_SIZE + ID_SIZE + ID_SIZE + payloadBytes.size
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // Header (14 bytes)
        buf.put(VERSION)                                    // 1: version
        buf.put(TYPE_TRANSACTION)                           // 1: type
        buf.put(TTL)                                        // 1: ttl
        buf.putLong(timestamp)                              // 8: timestamp (big-endian)
        buf.put(FLAG_HAS_RECIPIENT)                         // 1: flags
        buf.putShort(payloadBytes.size.toShort())            // 2: payload length (big-endian)

        // Sender ID (8 bytes — first 8 bytes of wallet ID hex → raw bytes)
        buf.put(walletIdToRoutingBytes(senderID))

        // Recipient ID (8 bytes)
        buf.put(walletIdToRoutingBytes(recipientID))

        // JSON Payload
        buf.put(payloadBytes)

        return buf.array()
    }

    /**
     * Convert a WalletID to 8 routing bytes (matches iOS WalletID.routingData).
     * Takes the first 8 bytes of the hex-encoded wallet ID.
     */
    private fun walletIdToRoutingBytes(wid: WalletID): ByteArray {
        val hex = wid.id
        val result = ByteArray(ID_SIZE)
        val bytesToRead = minOf(hex.length / 2, ID_SIZE)
        for (i in 0 until bytesToRead) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
