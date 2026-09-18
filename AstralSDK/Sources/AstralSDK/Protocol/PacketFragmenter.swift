import Foundation

/// PacketFragmenter — BLE MTU slicing and reassembly for Astral network packets.
///
/// Prepends a 3-byte header to every fragmented write:
/// - Byte 0: Fragment Type (`0x00` = single, `0x01` = start, `0x02` = middle, `0x03` = end)
/// - Byte 1: Sequence Number (0-indexed)
/// - Byte 2: Total Fragments Count
///
/// Ensures packets exceeding the physical BLE MTU (or configured fragment size)
/// are safely sequenced and reassembled across GATT write operations.
public struct PacketFragmenter: Sendable {

    // MARK: - Types

    public enum FragmentType: UInt8, Sendable {
        case single = 0x00
        case start  = 0x01
        case middle = 0x02
        case end    = 0x03
    }

    // MARK: - Constants

    /// Size of the framing header: [type: 1B][seq: 1B][total: 1B].
    public static let headerSize: Int = 3

    // MARK: - Properties

    /// Maximum transmission unit size including the 3-byte header.
    public let mtu: Int

    /// In-progress fragment store: sequence number -> fragment payload.
    private var receivedFragments: [UInt8: Data] = [:]

    /// Expected total fragments for the active reassembly session.
    private var expectedTotal: UInt8 = 0

    /// Whether a reassembly sequence is currently in progress.
    public var isReassembling: Bool {
        !receivedFragments.isEmpty
    }

    // MARK: - Initialization

    public init(mtu: Int) {
        self.mtu = max(mtu, Self.headerSize + 1)
    }

    // MARK: - Fragmentation

    /// Slices raw data into fragments bounded by MTU.
    ///
    /// - Parameter data: Raw data payload to be transmitted.
    /// - Returns: Array of wire-ready fragments with 3-byte headers.
    public func fragment(_ data: Data) -> [Data] {
        let maxPayloadPerChunk = max(1, mtu - Self.headerSize)

        // Single fragment case
        if data.count <= maxPayloadPerChunk {
            var packet = Data(capacity: Self.headerSize + data.count)
            packet.append(FragmentType.single.rawValue)
            packet.append(0x00) // seq 0
            packet.append(0x01) // total 1
            packet.append(data)
            return [packet]
        }

        // Multi-fragment case
        let totalChunks = Int(ceil(Double(data.count) / Double(maxPayloadPerChunk)))
        guard totalChunks <= 255 else {
            // Cannot represent > 255 fragments in 1-byte sequence counter
            return []
        }

        var fragments: [Data] = []
        fragments.reserveCapacity(totalChunks)

        for index in 0..<totalChunks {
            let offset = index * maxPayloadPerChunk
            let length = min(maxPayloadPerChunk, data.count - offset)
            let chunkPayload = data.subdata(in: offset..<(offset + length))

            let type: FragmentType
            if index == 0 {
                type = .start
            } else if index == totalChunks - 1 {
                type = .end
            } else {
                type = .middle
            }

            var packet = Data(capacity: Self.headerSize + chunkPayload.count)
            packet.append(type.rawValue)
            packet.append(UInt8(index))
            packet.append(UInt8(totalChunks))
            packet.append(chunkPayload)
            fragments.append(packet)
        }

        return fragments
    }

    // MARK: - Reassembly

    /// Processes an incoming fragment chunk.
    ///
    /// - Parameter fragment: The raw fragment bytes received over BLE.
    /// - Returns: The completely reassembled Data if this chunk completes the sequence, or nil if more fragments are expected.
    public mutating func reassemble(_ fragment: Data) -> Data? {
        guard fragment.count >= Self.headerSize else {
            return nil
        }

        let typeByte = fragment[0]
        let seq = fragment[1]
        let total = fragment[2]

        guard let type = FragmentType(rawValue: typeByte) else {
            return nil
        }

        let payload = fragment.subdata(in: Self.headerSize..<fragment.count)

        switch type {
        case .single:
            reset()
            return payload

        case .start:
            reset()
            expectedTotal = total
            receivedFragments[seq] = payload

        case .middle, .end:
            if expectedTotal == 0 {
                expectedTotal = total
            } else if expectedTotal != total {
                // Mismatched session — reset
                reset()
                return nil
            }
            receivedFragments[seq] = payload
        }

        // Check if all expected fragments have arrived
        if expectedTotal > 0 && receivedFragments.count == Int(expectedTotal) {
            var fullData = Data()
            for i in 0..<expectedTotal {
                guard let piece = receivedFragments[i] else {
                    // Missing an intermediate fragment
                    return nil
                }
                fullData.append(piece)
            }
            reset()
            return fullData
        }

        return nil
    }

    /// Reset internal reassembly state.
    public mutating func reset() {
        receivedFragments.removeAll(keepingCapacity: true)
        expectedTotal = 0
    }
}
