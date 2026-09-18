package com.astralnetwork.sdk.crypto

import com.astralnetwork.sdk.identity.KeyManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PublicKey
import java.security.Signature

/**
 * SignedTransaction — An encoded payment payload signed with a hardware-backed P-256 ECDSA key.
 *
 * Wire format (identical to iOS SignedTransaction):
 *   [pubKeyLen: 2B big-endian][senderPublicKey][sigLen: 2B big-endian][signature][payload]
 */
data class SignedTransaction(
    val payload: ByteArray,
    val signature: ByteArray,
    val senderPublicKey: ByteArray // 65-byte uncompressed X9.63 public key
) {

    /**
     * Serializes to the wire format:
     *   [pubKeyLen: 2B][pubKey][sigLen: 2B][sig][payload]
     */
    fun toWireFormat(): ByteArray {
        val totalSize = 2 + senderPublicKey.size + 2 + signature.size + payload.size
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(senderPublicKey.size.toShort())
        buf.put(senderPublicKey)
        buf.putShort(signature.size.toShort())
        buf.put(signature)
        buf.put(payload)
        return buf.array()
    }

    /**
     * Verifies the ECDSA signature over the payload.
     * If [pubKey] is null, derives the public key from [senderPublicKey] (X9.63 format).
     */
    fun verify(pubKey: PublicKey? = null): Boolean {
        return try {
            val key = pubKey ?: NoiseSession.x963ToPublicKey(senderPublicKey)
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(payload)
            verifier.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    companion object {

        /**
         * Signs a raw payload using the hardware-backed [KeyManager] and returns a [SignedTransaction].
         */
        fun sign(payload: ByteArray, keyManager: KeyManager): SignedTransaction {
            val signature = keyManager.sign(payload)
            val pubKey = keyManager.getSigningPublicKey()
                ?: error("Signing public key unavailable")
            val pubKeyBytes = NoiseSession.publicKeyToX963(pubKey)
            return SignedTransaction(
                payload = payload,
                signature = signature,
                senderPublicKey = pubKeyBytes
            )
        }

        /**
         * Deserializes a [SignedTransaction] from wire format bytes.
         */
        fun fromWireFormat(bytes: ByteArray): SignedTransaction? = try {
            if (bytes.size < 4) null
            else {
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

                // 1. Sender Public Key
                val pkLen = buf.short.toInt() and 0xFFFF
                if (buf.remaining() < pkLen + 2) null
                else {
                    val pubKey = ByteArray(pkLen).also { buf.get(it) }

                    // 2. Signature
                    val sigLen = buf.short.toInt() and 0xFFFF
                    if (buf.remaining() < sigLen) null
                    else {
                        val sig = ByteArray(sigLen).also { buf.get(it) }

                        // 3. Payload
                        val payload = ByteArray(buf.remaining()).also { buf.get(it) }

                        SignedTransaction(
                            payload = payload,
                            signature = sig,
                            senderPublicKey = pubKey
                        )
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SignedTransaction
        if (!payload.contentEquals(other.payload)) return false
        if (!signature.contentEquals(other.signature)) return false
        if (!senderPublicKey.contentEquals(other.senderPublicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = payload.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + senderPublicKey.contentHashCode()
        return result
    }
}
