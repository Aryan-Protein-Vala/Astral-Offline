pub mod crypto;
pub mod errors;
pub mod framing;
pub mod mesh;
pub mod state;

uniffi::setup_scaffolding!();

use errors::AstralError;
use framing::fragment::PacketFragmenter;
use state::vault::HardwareVault;
use std::sync::Arc;

#[derive(uniffi::Object)]
pub struct AstralEngine {
    vault: Arc<HardwareVault>,
}

#[uniffi::export]
impl AstralEngine {
    #[uniffi::constructor]
    pub fn new(vault_id: String, initial_balance: u64) -> Arc<Self> {
        Arc::new(Self {
            vault: Arc::new(HardwareVault::new(vault_id, initial_balance)),
        })
    }

    /// Primary interface for native Bluetooth/Wi-Fi drivers to slice outbound raw payloads.
    pub fn slice_outbound(&self, session_id: u16, payload: Vec<u8>, mtu: u32) -> Result<Vec<Vec<u8>>, AstralError> {
        PacketFragmenter::fragment(session_id, &payload, mtu as usize)
    }

    /// Validates local state transitions and executes client-side double-spending protection.
    pub fn execute_offline_payment(&self, amount: u64) -> Result<u64, AstralError> {
        self.vault.spend_offline(amount)
    }

    /// Direct cryptographic hash interface for mesh integrity.
    pub fn compute_packet_digest(&self, data: Vec<u8>) -> String {
        blake3::hash(&data).to_hex().to_string()
    }
}
