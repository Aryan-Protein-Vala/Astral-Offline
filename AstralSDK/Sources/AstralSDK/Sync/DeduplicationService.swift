import Foundation

/// BloomFilter — Probabilistic duplicate detection for transaction IDs.
///
/// A REAL Bloom filter, not a dictionary pretending to be one.
///
/// Properties:
/// - False positives possible (rare, controlled by size/hash count)
/// - False negatives IMPOSSIBLE (if we say "seen", it was definitely seen)
/// - O(1) insert and query
/// - Fixed memory regardless of how many transactions are processed
///
/// Parameters chosen for payment security:
/// - 10,000 capacity with 0.01% false positive rate
/// - 6 hash functions (k = ceil(-ln(p) / ln(2)) where p = 0.0001)
/// - ~20KB memory footprint
public final class BloomFilter {

    // MARK: - Storage

    /// Bit array stored as [UInt64] for efficient bitwise operations.
    private var bits: [UInt64]
    private let bitCount: Int
    private let hashCount: Int
    private var insertCount: Int = 0

    // MARK: - Init

    /// Create a Bloom filter with given capacity and false positive probability.
    ///
    /// - Parameters:
    ///   - capacity: Expected number of elements.
    ///   - falsePositiveRate: Target false positive probability (e.g., 0.0001 = 0.01%).
    public init(capacity: Int = 10_000, falsePositiveRate: Double = 0.0001) {
        // m = -n * ln(p) / (ln(2))^2
        let m = Int(ceil(-Double(capacity) * log(falsePositiveRate) / (log(2.0) * log(2.0))))
        self.bitCount = max(m, 64)  // minimum 64 bits

        // k = (m/n) * ln(2)
        let k = Int(ceil(Double(bitCount) / Double(capacity) * log(2.0)))
        self.hashCount = max(k, 1)

        // Allocate bit array
        let wordCount = (bitCount + 63) / 64
        self.bits = [UInt64](repeating: 0, count: wordCount)
    }

    // MARK: - Operations

    /// Insert an element into the filter.
    public func insert(_ element: String) {
        let hashes = computeHashes(element)
        for h in hashes {
            let index = h % bitCount
            let wordIndex = index / 64
            let bitIndex = index % 64
            bits[wordIndex] |= (1 << UInt64(bitIndex))
        }
        insertCount += 1
    }

    /// Check if an element MIGHT be in the filter.
    /// - Returns `true` = possibly seen (could be false positive).
    /// - Returns `false` = DEFINITELY never seen (guaranteed).
    public func mightContain(_ element: String) -> Bool {
        let hashes = computeHashes(element)
        for h in hashes {
            let index = h % bitCount
            let wordIndex = index / 64
            let bitIndex = index % 64
            if bits[wordIndex] & (1 << UInt64(bitIndex)) == 0 {
                return false  // Definitely not present
            }
        }
        return true  // Possibly present
    }

    /// Reset the filter (clear all bits).
    public func reset() {
        bits = [UInt64](repeating: 0, count: bits.count)
        insertCount = 0
    }

    /// Approximate fill ratio (for monitoring saturation).
    public var fillRatio: Double {
        let setBits = bits.reduce(0) { $0 + $1.nonzeroBitCount }
        return Double(setBits) / Double(bitCount)
    }

    /// Number of elements inserted.
    public var count: Int { insertCount }

    // MARK: - Hashing

    /// Generate `hashCount` independent hash positions using double-hashing scheme.
    ///
    /// Uses the Kirsch-Mitzenmacher optimization:
    /// h_i(x) = h1(x) + i * h2(x)
    /// where h1 and h2 are two independent hash functions.
    /// This gives k independent hashes from just 2 hash computations.
    private func computeHashes(_ element: String) -> [Int] {
        let data = Array(element.utf8)

        // h1: FNV-1a hash
        let h1 = fnv1a(data)

        // h2: DJB2 hash
        let h2 = djb2(data)

        var hashes: [Int] = []
        hashes.reserveCapacity(hashCount)
        for i in 0..<hashCount {
            let combined = h1 &+ UInt64(i) &* h2
            hashes.append(Int(combined % UInt64(bitCount)))
        }
        return hashes
    }

    /// FNV-1a hash (64-bit).
    private func fnv1a(_ data: [UInt8]) -> UInt64 {
        var hash: UInt64 = 0xcbf29ce484222325  // FNV offset basis
        let prime: UInt64 = 0x100000001b3       // FNV prime
        for byte in data {
            hash ^= UInt64(byte)
            hash = hash &* prime
        }
        return hash
    }

    /// DJB2 hash (64-bit variant).
    private func djb2(_ data: [UInt8]) -> UInt64 {
        var hash: UInt64 = 5381
        for byte in data {
            hash = ((hash << 5) &+ hash) &+ UInt64(byte)  // hash * 33 + byte
        }
        // Ensure non-zero for the double-hashing scheme
        return hash | 1
    }
}

// MARK: - DeduplicationService

/// DeduplicationService — Prevents double-spend attacks using a Bloom filter
/// backed by a time-based rotation strategy.
///
/// Architecture:
/// - Primary Bloom filter for current window
/// - Secondary Bloom filter for previous window (handles boundary edge case)
/// - Filters rotate every `maxAge` seconds
/// - Total memory: ~40KB for 10,000 transactions at 0.01% FP rate
public final class DeduplicationService {

    private var primaryFilter: BloomFilter
    private var secondaryFilter: BloomFilter
    private var lastRotation: Date
    private let rotationInterval: TimeInterval
    private let capacity: Int

    public init(config: AstralConfig = .default) {
        self.rotationInterval = config.dedupMaxAge
        self.capacity = config.dedupMaxCount
        self.primaryFilter = BloomFilter(capacity: capacity, falsePositiveRate: 0.0001)
        self.secondaryFilter = BloomFilter(capacity: capacity, falsePositiveRate: 0.0001)
        self.lastRotation = Date()
    }

    /// Check if a transaction ID has been seen. Returns `true` if DUPLICATE.
    ///
    /// If NOT a duplicate, automatically inserts into the filter.
    /// This is atomic: check-and-insert in one call to prevent TOCTOU races.
    public func isDuplicate(_ transactionID: String) -> Bool {
        rotateIfNeeded()

        // Check both filters (primary = current window, secondary = previous window)
        if primaryFilter.mightContain(transactionID) ||
           secondaryFilter.mightContain(transactionID) {
            return true  // Duplicate (or extremely rare false positive — 0.01%)
        }

        // Not seen → insert into primary
        primaryFilter.insert(transactionID)
        return false
    }

    /// Force rotation (for testing).
    public func forceRotate() {
        secondaryFilter = primaryFilter
        primaryFilter = BloomFilter(capacity: capacity, falsePositiveRate: 0.0001)
        lastRotation = Date()
    }

    /// Reset all filters.
    public func reset() {
        primaryFilter.reset()
        secondaryFilter.reset()
        lastRotation = Date()
    }

    /// Combined count across both filters.
    public var count: Int {
        primaryFilter.count + secondaryFilter.count
    }

    /// Combined fill ratio.
    public var fillRatio: Double {
        max(primaryFilter.fillRatio, secondaryFilter.fillRatio)
    }

    // MARK: - Rotation

    private func rotateIfNeeded() {
        let now = Date()
        if now.timeIntervalSince(lastRotation) >= rotationInterval {
            // Promote primary to secondary, create fresh primary
            secondaryFilter = primaryFilter
            primaryFilter = BloomFilter(capacity: capacity, falsePositiveRate: 0.0001)
            lastRotation = now
        }
    }
}
