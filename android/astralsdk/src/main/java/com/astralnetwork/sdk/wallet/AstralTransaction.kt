package com.astralnetwork.sdk.wallet

import com.astralnetwork.sdk.identity.WalletID
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * AstralTransaction — A signed, encrypted payment record.
 *
 * Binary packet format (cross-platform, Big-Endian):
 *   [Version:1][Type:1][TTL:1][Timestamp:8][Flags:1][PayloadLen:2]
 *   [SenderID:8][RecipientID:8][JSON Payload]
 *
 * This format is identical between Android and iOS. Any platform can decode
 * any packet without knowing who created it.
 *
 * The encoded bytes are what gets SIGNED by the hardware key before encryption.
 */
data class AstralTransaction(
    val id: String = UUID.randomUUID().toString(),
    val senderID: WalletID,
    val recipientID: WalletID,
    val amountPaisa: Long,   // In smallest denomination (paisa, cents, etc.)
    val timestamp: Long = System.currentTimeMillis(),
    val memo: String = "",
    var status: Status = Status.PENDING
) {
    enum class Status { PENDING, SENT, RECEIVED, FAILED }

    companion object {
        private const val VERSION: Byte = 2     // V2 — Rust core backed
        private const val TYPE_PAYMENT: Byte = 0x01
        private const val DEFAULT_TTL: Byte = 7 // 7 mesh hops
        private const val FLAG_HAS_RECIPIENT: Byte = 0x01
        private const val HEADER_SIZE = 14
        private const val ID_SIZE = 8

        fun decode(data: ByteArray): AstralTransaction? = try {
            if (data.size < HEADER_SIZE + ID_SIZE * 2) return null
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

            val version = buf.get()
            if (version != VERSION) return null
            val type = buf.get()
            if (type != TYPE_PAYMENT) return null
            buf.get() // TTL (skip)
            val ts = buf.long
            val flags = buf.get()
            val hasRecipient = (flags.toInt() and FLAG_HAS_RECIPIENT.toInt()) != 0
            val payloadLen = buf.short.toInt() and 0xFFFF

            val senderBytes = ByteArray(ID_SIZE).also { buf.get(it) }
            if (hasRecipient) ByteArray(ID_SIZE).also { buf.get(it) }

            if (buf.remaining() < payloadLen) return null
            val payloadBytes = ByteArray(payloadLen).also { buf.get(it) }
            val json = JSONObject(String(payloadBytes, Charsets.UTF_8))

            AstralTransaction(
                id = json.getString("txn_id"),
                senderID = WalletID(json.getString("sender")),
                recipientID = WalletID(json.getString("recipient")),
                amountPaisa = json.getLong("amount"),
                timestamp = json.optLong("ts", ts),
                memo = json.optString("memo", ""),
                status = Status.RECEIVED
            )
        } catch (e: Exception) { null }
    }

    /**
     * Encodes the transaction to the cross-platform binary packet format.
     * This is the payload that gets signed by the hardware key.
     */
    fun encode(): ByteArray {
        val payloadJson = JSONObject().apply {
            put("txn_id", id)
            put("amount", amountPaisa)
            put("sender", senderID.id)
            put("recipient", recipientID.id)
            put("ts", timestamp)
            if (memo.isNotEmpty()) put("memo", memo)
        }
        val payloadBytes = payloadJson.toString().toByteArray(Charsets.UTF_8)
        val totalSize = HEADER_SIZE + ID_SIZE * 2 + payloadBytes.size
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buf.put(VERSION)
        buf.put(TYPE_PAYMENT)
        buf.put(DEFAULT_TTL)
        buf.putLong(timestamp)
        buf.put(FLAG_HAS_RECIPIENT)
        buf.putShort(payloadBytes.size.toShort())
        buf.put(senderID.routingBytes)
        buf.put(recipientID.routingBytes)
        buf.put(payloadBytes)

        return buf.array()
    }

    val displayAmount: String get() = "₹%.2f".format(amountPaisa / 100.0)
}
