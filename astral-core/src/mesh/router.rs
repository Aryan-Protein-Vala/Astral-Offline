use crate::mesh::packet::MeshPacket;
use crate::mesh::traits::{OfflineStorage, PhysicalRadio};
use crate::errors::AstralError;
use std::sync::Arc;

#[derive(uniffi::Object)]
pub struct MeshRouter {
    local_pubkey: Vec<u8>,
    storage: Arc<dyn OfflineStorage>,
    radio: Arc<dyn PhysicalRadio>,
}

#[uniffi::export]
impl MeshRouter {
    #[uniffi::constructor]
    pub fn new(
        local_pubkey: Vec<u8>,
        storage: Arc<dyn OfflineStorage>,
        radio: Arc<dyn PhysicalRadio>,
    ) -> Arc<Self> {
        Arc::new(Self {
            local_pubkey,
            storage,
            radio,
        })
    }

    /// Processes an incoming raw payload from the physical radio (e.g. BLE/WiFi).
    /// Returns the deserialized `MeshPacket` if the packet was meant for this device.
    pub fn handle_incoming_packet(&self, raw_data: Vec<u8>) -> Result<Option<MeshPacket>, AstralError> {
        let mut packet: MeshPacket = bincode::deserialize(&raw_data).map_err(|_| AstralError::FramingError {
            reason: "Failed to deserialize mesh packet".into()
        })?;

        if self.storage.has_seen_packet(packet.id.clone()) {
            return Ok(None);
        }
        self.storage.mark_packet_seen(packet.id.clone());

        if packet.recipient_pubkey == self.local_pubkey {
            return Ok(Some(packet));
        }

        // Opportunistic DTN Routing
        if packet.ttl_hops > 0 {
            packet.ttl_hops -= 1;
            self.storage.store_packet(packet.clone());
            
            if let Ok(serialized) = bincode::serialize(&packet) {
                self.radio.broadcast_payload(serialized);
            }
        }

        Ok(None)
    }

    /// Processes an incoming raw payload from the physical radio (e.g. BLE/WiFi).
    /// Returns the encrypted payload if the packet was meant for this device.
    pub fn handle_incoming_payload(&self, raw_data: Vec<u8>) -> Result<Option<Vec<u8>>, AstralError> {
        Ok(self.handle_incoming_packet(raw_data)?.map(|p| p.encrypted_payload))
    }

    /// Triggers a re-broadcast of all stored, unexpired packets.
    pub fn sync_with_neighbor(&self) {
        let pending = self.storage.get_pending_packets();
        for packet in pending {
            if let Ok(serialized) = bincode::serialize(&packet) {
                self.radio.broadcast_payload(serialized);
            }
        }
    }
}
