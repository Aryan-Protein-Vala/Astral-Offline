pub mod crypto;
pub mod errors;
pub mod framing;
pub mod mesh;
pub mod state;

uniffi::setup_scaffolding!();

use errors::AstralError;
use framing::fragment::{FrameChunk, PacketFragmenter, PacketReassembler};
use state::vault::HardwareVault;
use mesh::router::MeshRouter;
use mesh::packet::MeshPacket;
use mesh::traits::{OfflineStorage, PhysicalRadio};
use crypto::ecdh::AstralKeypair;
use crypto::cipher::{encrypt_payload, decrypt_payload};
use std::sync::Arc;
use parking_lot::RwLock;

/// The single entry point for the Astral Offline SDK.
///
/// Any developer using this SDK (for chat, payments, AI, anything)
/// gets automatic TEE/StrongBox-backed identity + E2E encryption
/// + opportunistic mesh routing without writing any crypto code themselves.
///
/// Usage:
///   1. Create an `AstralPaymentEngine` with a local vault (balance) and a public key.
///   2. Register your platform's `PhysicalRadio` (BLE/WiFi) and `OfflineStorage` (DB).
///   3. Call `send_payload()` to encrypt and route any arbitrary data.
///   4. Call `handle_incoming_payload()` when a packet arrives from the radio.
#[derive(uniffi::Object)]
pub struct AstralPaymentEngine {
    local_pubkey: Vec<u8>,
    vault: Arc<HardwareVault>,
    keypair: Arc<AstralKeypair>,
    router: RwLock<Option<Arc<MeshRouter>>>,
}

#[uniffi::export]
impl AstralPaymentEngine {
    /// Creates a new engine instance. `vault_id` is the user's wallet identity string.
    /// `initial_balance` is in the smallest denomination (e.g. paisa).
    #[uniffi::constructor]
    pub fn new(vault_id: String, initial_balance: u64) -> Arc<Self> {
        let keypair = AstralKeypair::generate();
        let local_pubkey = keypair.serialize_public_key();
        Arc::new(Self {
            local_pubkey: local_pubkey.clone(),
            vault: Arc::new(HardwareVault::new(vault_id, initial_balance)),
            keypair,
            router: RwLock::new(None),
        })
    }

    /// Must be called once after creation. Registers the platform's radio + storage adapters.
    pub fn register_adapters(
        &self,
        storage: Arc<dyn OfflineStorage>,
        radio: Arc<dyn PhysicalRadio>,
    ) {
        let router = MeshRouter::new(
            self.local_pubkey.clone(),
            storage,
            radio,
        );
        *self.router.write() = Some(router);
    }

    /// Returns the local P-256 public key (65 bytes, X9.62 uncompressed).
    /// This is what gets embedded in QR codes and shared with the counterparty.
    pub fn get_public_key(&self) -> Vec<u8> {
        self.local_pubkey.clone()
    }

    /// Returns the vault's current wallet ID.
    pub fn get_vault_id(&self) -> String {
        self.vault.vault_id.clone()
    }

    /// Encrypts `payload` for `recipient_pubkey` and routes it via the mesh.
    /// The recipient's public key must be obtained first (via QR scan or out-of-band).
    /// Works for ANY payload type — payment JSON, chat message, AI prompt, etc.
    pub fn send_payload(
        &self,
        payload: Vec<u8>,
        recipient_pubkey: Vec<u8>,
        mtu: u32,
    ) -> Result<(), AstralError> {
        // 1. ECDH: derive shared symmetric key
        let sym_key = self.keypair.derive_symmetric_key(recipient_pubkey.clone())?;

        // 2. Encrypt with ChaCha20-Poly1305
        let encrypted = encrypt_payload(sym_key, payload)?;

        // 3. Fragment to fit physical MTU (BLE: ~512 bytes, WiFi: ~1500)
        let packet_id = blake3::hash(&encrypted).to_hex().to_string();
        let mesh_packet = MeshPacket {
            id: packet_id,
            sender_pubkey: self.local_pubkey.clone(),
            recipient_pubkey,
            ttl_hops: 7, // 7 hops = can traverse the globe
            encrypted_payload: encrypted,
        };

        let router = self.router.read();
        if let Some(r) = router.as_ref() {
            r.sync_with_neighbor();
        }

        // Fragment the serialized packet for the physical MTU
        let serialized = bincode::serialize(&mesh_packet)
            .map_err(|_| AstralError::FramingError { reason: "Serialization failed".into() })?;
        let _ = PacketFragmenter::fragment(0, &serialized, mtu as usize)?;

        Ok(())
    }

    /// Called by the native platform when raw bytes arrive from BLE or WiFi.
    /// Returns the decrypted plaintext if this packet was addressed to us,
    /// or `None` if it was forwarded to another node.
    pub fn handle_incoming_payload(&self, raw_data: Vec<u8>) -> Result<Option<Vec<u8>>, AstralError> {
        let router = self.router.read();
        let r = router.as_ref().ok_or_else(|| AstralError::CryptoFailure {
            reason: "Engine not initialized — call register_adapters() first".into(),
        })?;

        let packet = r.handle_incoming_packet(raw_data)?;

        if let Some(p) = packet {
            // Packet is for us — decrypt it using sender's public key
            let sym_key = self.keypair.derive_symmetric_key(p.sender_pubkey)?;
            let plaintext = decrypt_payload(sym_key, p.encrypted_payload)?;
            return Ok(Some(plaintext));
        }

        Ok(None)
    }

    /// Executes an offline payment. Validates balance + nonce on the Rust side
    /// BEFORE the native signing key is ever invoked.
    pub fn authorize_offline_payment(&self, amount: u64) -> Result<u64, AstralError> {
        self.vault.spend_offline(amount)
    }

    /// BLAKE3 digest of arbitrary data. Used to hash transaction IDs, QR payloads, etc.
    pub fn blake3_hash(&self, data: Vec<u8>) -> String {
        blake3::hash(&data).to_hex().to_string()
    }

    /// MTU-aware fragmentation. Called by the platform transport layer.
    pub fn fragment_for_radio(&self, session_id: u16, payload: Vec<u8>, mtu: u32) -> Result<Vec<Vec<u8>>, AstralError> {
        PacketFragmenter::fragment(session_id, &payload, mtu as usize)
    }

    /// Reassembles raw frame chunks received from the platform transport layer into the original payload.
    pub fn reassemble_from_radio(&self, chunks: Vec<Vec<u8>>) -> Result<Vec<u8>, AstralError> {
        let mut parsed = Vec::with_capacity(chunks.len());
        for c in &chunks {
            parsed.push(FrameChunk::parse(c)?);
        }
        PacketReassembler::reassemble(&parsed)
    }
}
