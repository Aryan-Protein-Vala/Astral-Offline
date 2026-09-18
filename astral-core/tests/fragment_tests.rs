use astral_core::errors::AstralError;
use astral_core::framing::fragment::{
    FrameChunk, PacketFragmenter, PacketReassembler, HEADER_SIZE, MAGIC_BYTE,
};
use std::thread;
use std::time::Duration;

#[test]
fn test_fragment_and_reassemble_roundtrip() {
    let payload = b"Hello, Astral Offline Mesh Network! This is a multi-chunk test message.".repeat(10);
    let session_id = 1337u16;
    let mtu = 50; // Forces multi-chunk fragmentation

    let raw_chunks = PacketFragmenter::fragment(session_id, &payload, mtu).expect("fragmentation failed");
    assert!(raw_chunks.len() > 1);

    // Verify all chunks have size <= mtu
    for chunk in &raw_chunks {
        assert!(chunk.len() <= mtu);
    }

    // Parse each raw chunk
    let mut parsed_chunks = Vec::new();
    for raw in &raw_chunks {
        let chunk = FrameChunk::parse(raw).expect("parse failed");
        assert_eq!(chunk.session_id, session_id);
        parsed_chunks.push(chunk);
    }

    // Direct slice reassembly
    let reconstructed = PacketReassembler::reassemble(&parsed_chunks).expect("reassembly failed");
    assert_eq!(reconstructed, payload);
}

#[test]
fn test_endianness_hash_slice_match() {
    let chunk_data = b"Arbitrary payload chunk for endianness verification";
    let session_id = 0x1234u16;
    let mtu = 100;

    let raw_chunks = PacketFragmenter::fragment(session_id, chunk_data, mtu).expect("fragmentation failed");
    let raw = &raw_chunks[0];

    // Check header layout:
    // Byte 0: MAGIC_BYTE
    assert_eq!(raw[0], MAGIC_BYTE);
    // Bytes 1..3: session_id in big-endian
    assert_eq!(&raw[1..3], &session_id.to_be_bytes());
    // Byte 3: chunk_index = 0
    assert_eq!(raw[3], 0);
    // Byte 4: total_chunks = 1
    assert_eq!(raw[4], 1);

    // Bytes 5..9: 4-byte hash
    let stored_hash = &raw[5..9];
    let payload = &raw[HEADER_SIZE..];
    let hash_binding = blake3::hash(payload);
    let expected_hash = &hash_binding.as_bytes()[0..4];

    // Endianness verification: stored_hash must exactly equal expected_hash bytes without byte-reversal
    assert_eq!(
        stored_hash, expected_hash,
        "Checksum bytes in header must match blake3 prefix exactly without endianness reversal"
    );
}

#[test]
fn test_stateful_reassembler_out_of_order() {
    let payload = b"Fragmented out-of-order message test payload with sufficient length to span 5 chunks.";
    let session_id = 0xABCDu16;
    let mtu = 30;

    let raw_chunks = PacketFragmenter::fragment(session_id, payload, mtu).expect("fragment failed");
    assert!(raw_chunks.len() >= 3);

    let mut reassembler = PacketReassembler::new(Duration::from_secs(5));

    // Ingest chunks out of order: chunk 1, chunk 0, chunk 2, etc.
    let count = raw_chunks.len();
    let mut order: Vec<usize> = (0..count).collect();
    order.reverse(); // Ingest in reverse order

    let mut final_payload = None;
    for &idx in &order {
        let result = reassembler.process_raw_chunk(&raw_chunks[idx]).expect("chunk processing failed");
        if let Some(data) = result {
            final_payload = Some(data);
        }
    }

    assert_eq!(final_payload, Some(payload.to_vec()));
    assert_eq!(reassembler.active_session_count(), 0);
}

#[test]
fn test_reassembler_timeout() {
    let payload = b"Timeout test message";
    let session_id = 0x55A1u16;
    let mtu = 15; // Splits into multiple chunks

    let raw_chunks = PacketFragmenter::fragment(session_id, payload, mtu).expect("fragment failed");
    assert!(raw_chunks.len() > 1);

    // Create reassembler with very short 20ms timeout
    let mut reassembler = PacketReassembler::new(Duration::from_millis(20));

    // Ingest first chunk
    let first = reassembler.process_raw_chunk(&raw_chunks[0]).expect("first chunk failed");
    assert!(first.is_none());
    assert!(reassembler.has_session(session_id));

    // Sleep past the timeout
    thread::sleep(Duration::from_millis(35));

    // Next chunk should detect session timeout and return ReassemblyTimeout error
    let second_res = reassembler.process_raw_chunk(&raw_chunks[1]);
    match second_res {
        Err(AstralError::ReassemblyTimeout { session_id: sid }) => {
            assert_eq!(sid, session_id);
        }
        other => panic!("Expected ReassemblyTimeout, got {:?}", other),
    }

    // Session should be cleared
    assert!(!reassembler.has_session(session_id));
}

#[test]
fn test_prune_expired_sessions() {
    let payload = b"Prune expired session test";
    let session_id = 0x7777u16;
    let mtu = 15;

    let raw_chunks = PacketFragmenter::fragment(session_id, payload, mtu).expect("fragment failed");
    let mut reassembler = PacketReassembler::new(Duration::from_millis(15));

    reassembler.process_raw_chunk(&raw_chunks[0]).expect("process failed");
    assert_eq!(reassembler.active_session_count(), 1);

    thread::sleep(Duration::from_millis(25));

    let pruned = reassembler.prune_expired();
    assert_eq!(pruned, vec![session_id]);
    assert_eq!(reassembler.active_session_count(), 0);
}

#[test]
fn test_corrupted_chunk_detection() {
    let payload = b"Corrupted chunk test";
    let session_id = 0x9999u16;
    let mtu = 50;

    let raw_chunks = PacketFragmenter::fragment(session_id, payload, mtu).expect("fragment failed");
    let mut corrupted = raw_chunks[0].clone();

    // Corrupt the magic byte
    corrupted[0] = 0x00;
    assert!(matches!(
        FrameChunk::parse(&corrupted),
        Err(AstralError::FramingError { .. })
    ));

    // Restore magic byte, corrupt payload byte
    corrupted[0] = MAGIC_BYTE;
    let last_idx = corrupted.len() - 1;
    corrupted[last_idx] ^= 0xFF; // Flip bits in payload
    assert!(matches!(
        FrameChunk::parse(&corrupted),
        Err(AstralError::FramingError { .. })
    ));
}

#[test]
fn test_mtu_too_small() {
    let payload = b"MTU bounds check";
    let res = PacketFragmenter::fragment(1, payload, HEADER_SIZE);
    assert!(matches!(res, Err(AstralError::FramingError { .. })));
}

#[test]
fn test_empty_payload_fragmentation() {
    let payload = b"";
    let raw_chunks = PacketFragmenter::fragment(42, payload, 50).expect("fragment empty payload failed");
    assert_eq!(raw_chunks.len(), 1);

    let parsed = FrameChunk::parse(&raw_chunks[0]).expect("parse empty chunk failed");
    assert_eq!(parsed.payload.len(), 0);
    assert_eq!(parsed.total_chunks, 1);

    let reassembled = PacketReassembler::reassemble(&[parsed]).expect("reassemble empty failed");
    assert_eq!(reassembled, b"");
}
