package com.astralnetwork.sdk.transport

/**
 * PacketFragmenter — Slices packets into MTU-sized chunks for BLE transmission and reassembles them.
 *
 * Wire format per fragment:
 *   [Type: 1B][Seq: 1B][Total: 1B][Payload]
 *
 * Types:
 *   0x00 — Single packet (no further fragments)
 *   0x01 — Start packet
 *   0x02 — Continue packet
 *   0x03 — End packet
 */
class PacketFragmenter(private val mtu: Int = 512) {

    companion object {
        const val TYPE_SINGLE: Byte = 0x00
        const val TYPE_START: Byte = 0x01
        const val TYPE_CONTINUE: Byte = 0x02
        const val TYPE_END: Byte = 0x03
        const val HEADER_SIZE = 3
    }

    private val chunks = mutableMapOf<Int, ByteArray>()
    private var expectedTotal = -1

    /**
     * Slices [data] into MTU-sized fragments with 3-byte headers.
     */
    fun fragment(data: ByteArray): List<ByteArray> {
        val maxPayload = mtu - HEADER_SIZE
        if (data.size <= maxPayload) {
            val chunk = ByteArray(HEADER_SIZE + data.size)
            chunk[0] = TYPE_SINGLE
            chunk[1] = 0.toByte()
            chunk[2] = 1.toByte()
            System.arraycopy(data, 0, chunk, HEADER_SIZE, data.size)
            return listOf(chunk)
        }

        val total = (data.size + maxPayload - 1) / maxPayload
        require(total <= 255) { "Data too large to fragment: requires $total chunks (max 255)" }

        val fragments = ArrayList<ByteArray>(total)
        var offset = 0
        for (seq in 0 until total) {
            val chunkLen = minOf(maxPayload, data.size - offset)
            val chunk = ByteArray(HEADER_SIZE + chunkLen)
            chunk[0] = when (seq) {
                0 -> TYPE_START
                total - 1 -> TYPE_END
                else -> TYPE_CONTINUE
            }
            chunk[1] = seq.toByte()
            chunk[2] = total.toByte()
            System.arraycopy(data, offset, chunk, HEADER_SIZE, chunkLen)
            fragments.add(chunk)
            offset += chunkLen
        }
        return fragments
    }

    /**
     * Feeds a received fragment chunk and returns the full reassembled payload when complete,
     * or null if more chunks are awaited.
     */
    @Synchronized
    fun reassemble(chunk: ByteArray): ByteArray? {
        if (chunk.size < HEADER_SIZE) return null

        val type = chunk[0]
        val seq = chunk[1].toInt() and 0xFF
        val total = chunk[2].toInt() and 0xFF
        val payload = chunk.copyOfRange(HEADER_SIZE, chunk.size)

        if (type == TYPE_SINGLE) {
            reset()
            return payload
        }

        if (type == TYPE_START) {
            reset()
            expectedTotal = total
            chunks[seq] = payload
        } else {
            if (expectedTotal == -1 || total != expectedTotal) {
                // Sequence out of sync or fresh stream without start
                reset()
                return null
            }
            chunks[seq] = payload
        }

        if (expectedTotal > 0 && chunks.size == expectedTotal) {
            // Verify all sequence indices 0 until expectedTotal are present
            val totalLength = (0 until expectedTotal).sumOf { chunks[it]?.size ?: return null }
            val reassembled = ByteArray(totalLength)
            var offset = 0
            for (i in 0 until expectedTotal) {
                val part = chunks[i] ?: return null
                System.arraycopy(part, 0, reassembled, offset, part.size)
                offset += part.size
            }
            reset()
            return reassembled
        }

        return null
    }

    @Synchronized
    fun reset() {
        chunks.clear()
        expectedTotal = -1
    }
}
