use crate::errors::AstralError;
use bytes::{BufMut, Bytes, BytesMut};

pub const MAGIC_BYTE: u8 = 0xA7; // Astral Protocol Identifier
pub const HEADER_SIZE: usize = 9;

#[derive(Clone, Debug, PartialEq)]
pub struct FrameChunk {
    pub session_id: u16,
    pub chunk_index: u8,
    pub total_chunks: u8,
    pub payload: Vec<u8>,
}

pub struct PacketFragmenter;

impl PacketFragmenter {
    /// Slices raw bytes based on dynamically negotiated physical MTU (BLE: ~20-512, Wi-Fi: ~1500-65535).
    pub fn fragment(session_id: u16, payload: &[u8], mtu: usize) -> Result<Vec<Vec<u8>>, AstralError> {
        if mtu <= HEADER_SIZE {
            return Err(AstralError::FramingError {
                reason: "MTU smaller than packet header size".into(),
            });
        }

        let max_chunk_payload = mtu - HEADER_SIZE;
        let chunks: Vec<&[u8]> = payload.chunks(max_chunk_payload).collect();
        let total_chunks = chunks.len();

        if total_chunks > 255 {
            return Err(AstralError::PayloadTooLarge { size: payload.len() as u32 });
        }

        let mut packet_list = Vec::with_capacity(total_chunks);

        for (index, chunk) in chunks.into_iter().enumerate() {
            let mut buffer = BytesMut::with_capacity(HEADER_SIZE + chunk.len());
            buffer.put_u8(MAGIC_BYTE);
            buffer.put_u16(session_id);
            buffer.put_u8(index as u8);
            buffer.put_u8(total_chunks as u8);
            let hash_bytes: [u8; 4] = blake3::hash(chunk).as_bytes()[0..4].try_into().unwrap();
            buffer.put_u32(u32::from_le_bytes(hash_bytes));
            buffer.put_slice(chunk);
            packet_list.push(buffer.to_vec());
        }

        Ok(packet_list)
    }
}
