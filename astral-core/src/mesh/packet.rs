use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct MeshPacket {
    pub id: String,
    pub sender_pubkey: Vec<u8>,
    pub recipient_pubkey: Vec<u8>,
    pub ttl_hops: u8,
    pub encrypted_payload: Vec<u8>,
}
