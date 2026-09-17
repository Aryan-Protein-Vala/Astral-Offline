package com.astralnetwork.sdk.sync

import com.astralnetwork.sdk.core.AstralConfig

/**
 * DeduplicationService — Bloom filter for double-spend detection.
 *
 * Uses FNV-1a + DJB2 double-hashing with time-based dual-filter rotation.
 * Mirrors iOS DeduplicationService.
 *
 * Security: prevents replay attacks by rejecting transactions whose
 * nonce/hash has been seen within the filter's time window.
 */
class DeduplicationService(config: AstralConfig = AstralConfig.DEFAULT) {

    private val maxAge: Long = config.dedupMaxAge
    private val filterSize: Int = config.dedupMaxCount * 10    // 10x for low false-positive rate
    private val hashCount: Int = 7

    // Dual rotating filters
    private var currentFilter = BooleanArray(filterSize)
    private var previousFilter = BooleanArray(filterSize)
    private var lastRotation = System.currentTimeMillis()

    /**
     * Check if a transaction hash has been seen before.
     * @return true if DUPLICATE (double-spend), false if new.
     */
    @Synchronized
    fun isDuplicate(transactionHash: ByteArray): Boolean {
        rotateIfNeeded()

        val hashes = computeHashes(transactionHash)

        // Check both filters
        val inCurrent = hashes.all { currentFilter[it] }
        val inPrevious = hashes.all { previousFilter[it] }

        if (inCurrent || inPrevious) {
            return true   // DUPLICATE — reject!
        }

        // Insert into current filter
        hashes.forEach { currentFilter[it] = true }
        return false      // New transaction — accept
    }

    private fun rotateIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastRotation > maxAge) {
            previousFilter = currentFilter
            currentFilter = BooleanArray(filterSize)
            lastRotation = now
        }
    }

    /** FNV-1a + DJB2 double-hashing for Bloom filter indices. */
    private fun computeHashes(data: ByteArray): List<Int> {
        val fnv = fnv1a(data)
        val djb2 = djb2(data)
        val size = filterSize.toLong()

        return (0 until hashCount).map { i ->
            val h = (fnv + i.toLong() * djb2) and 0x7FFFFFFFL
            (h % size).toInt()
        }
    }

    private fun fnv1a(data: ByteArray): Long {
        var hash = -3750763034362895579L  // 0xcbf29ce484222325 as signed Long
        for (b in data) {
            hash = hash xor (b.toLong() and 0xFFL)
            hash *= 1099511628211L        // 0x100000001b3 as decimal
        }
        return hash
    }

    private fun djb2(data: ByteArray): Long {
        var hash = 5381L
        for (b in data) {
            hash = ((hash shl 5) + hash) + (b.toLong() and 0xFFL)
        }
        return hash
    }
}
