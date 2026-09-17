use crate::mesh::packet::MeshPacket;

#[uniffi::export(with_foreign)]
pub trait OfflineStorage: Send + Sync {
    fn store_packet(&self, packet: MeshPacket);
    fn get_pending_packets(&self) -> Vec<MeshPacket>;
    fn has_seen_packet(&self, packet_id: String) -> bool;
    fn mark_packet_seen(&self, packet_id: String);
}

#[uniffi::export(with_foreign)]
pub trait PhysicalRadio: Send + Sync {
    fn broadcast_payload(&self, payload: Vec<u8>);
}
