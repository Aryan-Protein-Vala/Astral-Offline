use crate::errors::AstralError;
use bytes::{BufMut, BytesMut};
use std::collections::HashMap;
use std::time::{Duration, Instant};

pub const MAGIC_BYTE: u8 = 0xA7; // Astral Protocol Identifier
pub const HEADER_SIZE: usize = 9;
pub const DEFAULT_REASSEMBLY_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Debug, PartialEq)]
pub struct FrameChunk {
    pub session_id: u16,
    pub chunk_index: u8,
    pub total_chunks: u8,
    pub payload: Vec<u8>,
}

impl FrameChunk {
    /// Parses a raw wire frame into a `FrameChunk`, validating magic byte,
    /// header length, chunk indices, and the 4-byte BLAKE3 checksum.
    pub fn parse(data: &[u8]) -> Result<Self, AstralError> {
        if data.len() < HEADER_SIZE {
            return Err(AstralError::FramingError {
                reason: "Data too short for packet header".into(),
            });
        }
        if data[0] != MAGIC_BYTE {
            return Err(AstralError::FramingError {
                reason: format!("Invalid magic byte: {:#04X}", data[0]),
            });
        }
        let session_id = u16::from_be_bytes([data[1], data[2]]);
        let chunk_index = data[3];
        let total_chunks = data[4];
        let expected_hash = &data[5..9];
        let payload = &data[9..];

        if total_chunks == 0 {
            return Err(AstralError::FramingError {
                reason: "Total chunks cannot be zero".into(),
            });
        }

        if chunk_index >= total_chunks {
            return Err(AstralError::FramingError {
                reason: format!(
                    "Invalid chunk index {} for total chunks {}",
                    chunk_index, total_chunks
                ),
            });
        }

        let hash = blake3::hash(payload);
        let actual_hash = &hash.as_bytes()[0..4];
        if actual_hash != expected_hash {
            return Err(AstralError::FramingError {
                reason: "Chunk checksum validation failed".into(),
            });
        }

        Ok(Self {
            session_id,
            chunk_index,
            total_chunks,
            payload: payload.to_vec(),
        })
    }

    /// Serializes a `FrameChunk` back to wire format.
    pub fn to_wire_bytes(&self) -> Vec<u8> {
        let mut buffer = BytesMut::with_capacity(HEADER_SIZE + self.payload.len());
        buffer.put_u8(MAGIC_BYTE);
        buffer.put_u16(self.session_id);
        buffer.put_u8(self.chunk_index);
        buffer.put_u8(self.total_chunks);
        let hash_bytes: [u8; 4] = blake3::hash(&self.payload).as_bytes()[0..4].try_into().unwrap();
        buffer.put_slice(&hash_bytes);
        buffer.put_slice(&self.payload);
        buffer.to_vec()
    }
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

        let total_chunks = if total_chunks == 0 { 1 } else { total_chunks };
        let chunk_slices: Vec<&[u8]> = if payload.is_empty() {
            vec![&[][..]]
        } else {
            chunks
        };

        let mut packet_list = Vec::with_capacity(total_chunks);

        for (index, chunk) in chunk_slices.into_iter().enumerate() {
            let mut buffer = BytesMut::with_capacity(HEADER_SIZE + chunk.len());
            buffer.put_u8(MAGIC_BYTE);
            buffer.put_u16(session_id);
            buffer.put_u8(index as u8);
            buffer.put_u8(total_chunks as u8);
            let hash_bytes: [u8; 4] = blake3::hash(chunk).as_bytes()[0..4].try_into().unwrap();
            buffer.put_slice(&hash_bytes);
            buffer.put_slice(chunk);
            packet_list.push(buffer.to_vec());
        }

        Ok(packet_list)
    }
}

#[derive(Debug, Clone)]
struct ReassemblySession {
    total_chunks: u8,
    chunks: HashMap<u8, Vec<u8>>,
    created_at: Instant,
    last_updated: Instant,
}

impl ReassemblySession {
    fn new(total_chunks: u8) -> Self {
        let now = Instant::now();
        Self {
            total_chunks,
            chunks: HashMap::with_capacity(total_chunks as usize),
            created_at: now,
            last_updated: now,
        }
    }

    fn is_expired(&self, timeout: Duration) -> bool {
        self.created_at.elapsed() > timeout
    }
}

pub struct PacketReassembler {
    sessions: HashMap<u16, ReassemblySession>,
    timeout: Duration,
}

impl Default for PacketReassembler {
    fn default() -> Self {
        Self::new(DEFAULT_REASSEMBLY_TIMEOUT)
    }
}

impl PacketReassembler {
    /// Creates a new reassembler with the specified session timeout.
    pub fn new(timeout: Duration) -> Self {
        Self {
            sessions: HashMap::new(),
            timeout,
        }
    }

    /// Reconstructs a full packet payload directly from an ordered or unordered
    /// slice of `FrameChunk` instances belonging to the same session.
    pub fn reassemble(chunks: &[FrameChunk]) -> Result<Vec<u8>, AstralError> {
        if chunks.is_empty() {
            return Err(AstralError::FramingError {
                reason: "Cannot reassemble empty chunk slice".into(),
            });
        }

        let session_id = chunks[0].session_id;
        let total_chunks = chunks[0].total_chunks;

        if total_chunks == 0 {
            return Err(AstralError::FramingError {
                reason: "Total chunks in frame header cannot be zero".into(),
            });
        }

        let mut collected: HashMap<u8, &[u8]> = HashMap::with_capacity(total_chunks as usize);

        for chunk in chunks {
            if chunk.session_id != session_id {
                return Err(AstralError::FramingError {
                    reason: format!(
                        "Mismatched session ID in chunk slice: expected {}, got {}",
                        session_id, chunk.session_id
                    ),
                });
            }
            if chunk.total_chunks != total_chunks {
                return Err(AstralError::FramingError {
                    reason: format!(
                        "Mismatched total chunks in chunk slice: expected {}, got {}",
                        total_chunks, chunk.total_chunks
                    ),
                });
            }
            if chunk.chunk_index >= total_chunks {
                return Err(AstralError::FramingError {
                    reason: format!(
                        "Invalid chunk index {} for total chunks {}",
                        chunk.chunk_index, total_chunks
                    ),
                });
            }
            collected.insert(chunk.chunk_index, &chunk.payload);
        }

        if collected.len() != total_chunks as usize {
            return Err(AstralError::FramingError {
                reason: format!(
                    "Missing chunks: expected {}, received {}",
                    total_chunks,
                    collected.len()
                ),
            });
        }

        let total_len: usize = collected.values().map(|p| p.len()).sum();
        let mut assembled = Vec::with_capacity(total_len);

        for i in 0..total_chunks {
            let part = collected.get(&i).ok_or_else(|| AstralError::FramingError {
                reason: format!("Missing chunk index {}", i),
            })?;
            assembled.extend_from_slice(part);
        }

        Ok(assembled)
    }

    /// Ingests a `FrameChunk`. If this chunk completes the session, returns `Ok(Some(assembled_payload))`.
    /// If more chunks are still needed, returns `Ok(None)`.
    /// If the session has timed out, returns `Err(AstralError::ReassemblyTimeout { session_id })`.
    pub fn process_chunk(&mut self, chunk: FrameChunk) -> Result<Option<Vec<u8>>, AstralError> {
        let session_id = chunk.session_id;

        // Check if existing session has timed out
        if let Some(session) = self.sessions.get(&session_id) {
            if session.is_expired(self.timeout) {
                self.sessions.remove(&session_id);
                return Err(AstralError::ReassemblyTimeout { session_id });
            }
            if session.total_chunks != chunk.total_chunks {
                return Err(AstralError::FramingError {
                    reason: format!(
                        "Total chunks mismatch for session {}: existing {}, chunk {}",
                        session_id, session.total_chunks, chunk.total_chunks
                    ),
                });
            }
        }

        if chunk.total_chunks == 0 {
            return Err(AstralError::FramingError {
                reason: "Total chunks cannot be zero".into(),
            });
        }
        if chunk.chunk_index >= chunk.total_chunks {
            return Err(AstralError::FramingError {
                reason: format!(
                    "Invalid chunk index {} for total chunks {}",
                    chunk.chunk_index, chunk.total_chunks
                ),
            });
        }

        let session = self
            .sessions
            .entry(session_id)
            .or_insert_with(|| ReassemblySession::new(chunk.total_chunks));

        session.last_updated = Instant::now();
        session.chunks.insert(chunk.chunk_index, chunk.payload);

        if session.chunks.len() == session.total_chunks as usize {
            let session = self.sessions.remove(&session_id).unwrap();
            let total_len: usize = session.chunks.values().map(|p| p.len()).sum();
            let mut assembled = Vec::with_capacity(total_len);
            for i in 0..session.total_chunks {
                let part = session.chunks.get(&i).ok_or_else(|| AstralError::FramingError {
                    reason: format!("Missing chunk index {}", i),
                })?;
                assembled.extend_from_slice(part);
            }
            Ok(Some(assembled))
        } else {
            Ok(None)
        }
    }

    /// Ingests a raw wire packet slice, parsing and validating its header and BLAKE3 checksum,
    /// and routing it to the active reassembly session.
    pub fn process_raw_chunk(&mut self, raw_data: &[u8]) -> Result<Option<Vec<u8>>, AstralError> {
        let chunk = FrameChunk::parse(raw_data)?;
        self.process_chunk(chunk)
    }

    /// Cleans up any sessions that have exceeded the timeout window, returning their session IDs.
    pub fn prune_expired(&mut self) -> Vec<u16> {
        let timeout = self.timeout;
        let mut expired = Vec::new();
        self.sessions.retain(|sid, session| {
            if session.is_expired(timeout) {
                expired.push(*sid);
                false
            } else {
                true
            }
        });
        expired
    }

    /// Cancels an active reassembly session, discarding buffered chunks.
    pub fn cancel_session(&mut self, session_id: u16) -> bool {
        self.sessions.remove(&session_id).is_some()
    }

    /// Returns true if an active session exists for the given ID.
    pub fn has_session(&self, session_id: u16) -> bool {
        self.sessions.contains_key(&session_id)
    }

    /// Returns the number of currently active reassembly sessions.
    pub fn active_session_count(&self) -> usize {
        self.sessions.len()
    }

    /// Returns `Some((received_count, total_count))` for an active session.
    pub fn session_progress(&self, session_id: u16) -> Option<(usize, usize)> {
        self.sessions.get(&session_id).map(|s| (s.chunks.len(), s.total_chunks as usize))
    }
}
