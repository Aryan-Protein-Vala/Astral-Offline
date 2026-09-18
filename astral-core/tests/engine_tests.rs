use astral_core::AstralPaymentEngine;
use astral_core::mesh::packet::MeshPacket;
use astral_core::mesh::traits::{OfflineStorage, PhysicalRadio};
use parking_lot::RwLock;
use std::collections::HashSet;
use std::sync::Arc;

struct MockStorage {
    stored_packets: RwLock<Vec<MeshPacket>>,
    seen_ids: RwLock<HashSet<String>>,
}

impl MockStorage {
    fn new() -> Self {
        Self {
            stored_packets: RwLock::new(Vec::new()),
            seen_ids: RwLock::new(HashSet::new()),
        }
    }
}

impl OfflineStorage for MockStorage {
    fn store_packet(&self, packet: MeshPacket) {
        self.stored_packets.write().push(packet);
    }

    fn get_pending_packets(&self) -> Vec<MeshPacket> {
        self.stored_packets.read().clone()
    }

    fn has_seen_packet(&self, packet_id: String) -> bool {
        self.seen_ids.read().contains(&packet_id)
    }

    fn mark_packet_seen(&self, packet_id: String) {
        self.seen_ids.write().insert(packet_id);
    }
}

struct MockRadio {
    broadcasts: RwLock<Vec<Vec<u8>>>,
}

impl MockRadio {
    fn new() -> Self {
        Self {
            broadcasts: RwLock::new(Vec::new()),
        }
    }
}

impl PhysicalRadio for MockRadio {
    fn broadcast_payload(&self, payload: Vec<u8>) {
        self.broadcasts.write().push(payload);
    }
}

#[test]
fn test_engine_initialization_and_keys() {
    let engine = AstralPaymentEngine::new("vault_user_789".into(), 50_000);
    assert_eq!(engine.get_vault_id(), "vault_user_789");

    let pubkey = engine.get_public_key();
    assert_eq!(pubkey.len(), 65);
    assert_eq!(pubkey[0], 0x04);

    let counter = engine.authorize_offline_payment(5_000).expect("spend failed");
    assert_eq!(counter, 1);

    let digest = engine.blake3_hash(b"hello world".to_vec());
    assert_eq!(digest, blake3::hash(b"hello world").to_hex().to_string());
}

#[test]
fn test_engine_fragment_and_reassemble_for_radio() {
    let engine = AstralPaymentEngine::new("vault_radio".into(), 1_000);
    let payload = b"Test message for radio MTU slicing and reassembly via engine methods".to_vec();
    let session_id = 42u16;
    let mtu = 25; // Smaller than payload to force multiple fragments

    let fragments = engine
        .fragment_for_radio(session_id, payload.clone(), mtu)
        .expect("fragment_for_radio failed");
    assert!(fragments.len() > 1);

    let reassembled = engine
        .reassemble_from_radio(fragments)
        .expect("reassemble_from_radio failed");
    assert_eq!(reassembled, payload);
}

#[test]
fn test_engine_mesh_routing_and_e2e_encryption() {
    let alice = AstralPaymentEngine::new("alice_vault".into(), 10_000);
    let bob = AstralPaymentEngine::new("bob_vault".into(), 10_000);

    let alice_storage = Arc::new(MockStorage::new());
    let alice_radio = Arc::new(MockRadio::new());
    alice.register_adapters(alice_storage.clone(), alice_radio.clone());

    let bob_storage = Arc::new(MockStorage::new());
    let bob_radio = Arc::new(MockRadio::new());
    bob.register_adapters(bob_storage.clone(), bob_radio.clone());

    // Alice sends a confidential payload to Bob
    let secret_message = b"Super secret mesh transaction data".to_vec();
    let bob_pubkey = bob.get_public_key();

    alice
        .send_payload(secret_message.clone(), bob_pubkey.clone(), 512)
        .expect("send_payload failed");

    // Check that Alice's radio broadcasted the fragmented packet
    // Now simulate Bob receiving a packet directly addressed to Bob
    let alice_keypair_for_bob = astral_core::crypto::ecdh::AstralKeypair::generate();
    let shared = alice_keypair_for_bob.derive_symmetric_key(bob.get_public_key()).unwrap();
    let encrypted = astral_core::crypto::cipher::encrypt_payload(shared, secret_message.clone()).unwrap();

    let packet = MeshPacket {
        id: "unique_tx_packet_1".into(),
        sender_pubkey: alice_keypair_for_bob.serialize_public_key(),
        recipient_pubkey: bob.get_public_key(),
        ttl_hops: 5,
        encrypted_payload: encrypted,
    };

    let serialized = bincode::serialize(&packet).unwrap();
    let result = bob.handle_incoming_payload(serialized).expect("handle_incoming_payload failed");

    // Packet was meant for Bob, so Bob receives the decrypted plaintext
    assert_eq!(result, Some(secret_message));
}
