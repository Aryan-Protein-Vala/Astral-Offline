use astral_core::crypto::cipher::{
    decrypt_payload, decrypt_payload_with_aad, encrypt_payload, encrypt_payload_with_aad,
    MIN_PACKED_SIZE,
};
use astral_core::crypto::ecdh::{AstralKeypair, DEFAULT_HKDF_SALT};
use astral_core::errors::AstralError;

#[test]
fn test_chacha20poly1305_roundtrip_without_aad() {
    let key = vec![0x42u8; 32];
    let payload = b"Confidential offline transaction payload".to_vec();

    let encrypted = encrypt_payload(key.clone(), payload.clone()).expect("encryption failed");
    assert!(encrypted.len() >= MIN_PACKED_SIZE);

    let decrypted = decrypt_payload(key, encrypted).expect("decryption failed");
    assert_eq!(decrypted, payload);
}

#[test]
fn test_chacha20poly1305_roundtrip_with_aad() {
    let key = vec![0x77u8; 32];
    let payload = b"Payload with associated authenticated header data".to_vec();
    let aad = b"Header: sender=Alice, recipient=Bob, nonce=100".to_vec();

    let encrypted = encrypt_payload_with_aad(key.clone(), payload.clone(), aad.clone())
        .expect("encryption with aad failed");

    // Successful decryption with matching AAD
    let decrypted = decrypt_payload_with_aad(key.clone(), encrypted.clone(), aad.clone())
        .expect("decryption with aad failed");
    assert_eq!(decrypted, payload);

    // Tampered AAD must fail MAC verification
    let tampered_aad = b"Header: sender=Eve, recipient=Bob, nonce=100".to_vec();
    let fail_result = decrypt_payload_with_aad(key, encrypted, tampered_aad);
    assert!(matches!(fail_result, Err(AstralError::CryptoFailure { .. })));
}

#[test]
fn test_cipher_ciphertext_tampering() {
    let key = vec![0x11u8; 32];
    let payload = b"Data to be tampered with".to_vec();

    let mut encrypted = encrypt_payload(key.clone(), payload).expect("encrypt failed");
    let last = encrypted.len() - 1;
    encrypted[last] ^= 0x01; // Corrupt MAC tag

    let result = decrypt_payload(key, encrypted);
    assert!(matches!(result, Err(AstralError::CryptoFailure { .. })));
}

#[test]
fn test_cipher_length_check_under_28_bytes() {
    let key = vec![0x99u8; 32];

    // Exactly 27 bytes (less than 12 nonce + 16 tag = 28)
    let short_data = vec![0u8; 27];
    let err = decrypt_payload(key.clone(), short_data).unwrap_err();
    match err {
        AstralError::CryptoFailure { reason } => {
            assert!(
                reason.contains("too short"),
                "Error should mention too short: {}",
                reason
            );
        }
        other => panic!("Unexpected error: {:?}", other),
    }

    // 0 bytes
    let empty_data = vec![];
    assert!(matches!(
        decrypt_payload(key, empty_data),
        Err(AstralError::CryptoFailure { .. })
    ));
}

#[test]
fn test_cipher_invalid_key_length() {
    let invalid_key = vec![0x12u8; 16]; // 16 bytes instead of 32
    let payload = b"Test".to_vec();

    assert!(matches!(
        encrypt_payload(invalid_key.clone(), payload),
        Err(AstralError::CryptoFailure { .. })
    ));

    assert!(matches!(
        decrypt_payload(invalid_key, vec![0u8; 30]),
        Err(AstralError::CryptoFailure { .. })
    ));
}

#[test]
fn test_ecdh_hkdf_sha256_symmetric_agreement() {
    let alice = AstralKeypair::generate();
    let bob = AstralKeypair::generate();

    let alice_pub = alice.serialize_public_key();
    let bob_pub = bob.serialize_public_key();

    // Verify public key format (X9.62 uncompressed, 65 bytes starting with 0x04)
    assert_eq!(alice_pub.len(), 65);
    assert_eq!(alice_pub[0], 0x04);
    assert_eq!(bob_pub.len(), 65);
    assert_eq!(bob_pub[0], 0x04);

    assert_eq!(DEFAULT_HKDF_SALT, b"AstralPayment-P256-v1");

    // Both parties derive symmetric key using default HKDF-SHA256
    let alice_derived = alice.derive_symmetric_key(bob_pub.clone()).expect("Alice derivation failed");
    let bob_derived = bob.derive_symmetric_key(alice_pub.clone()).expect("Bob derivation failed");

    // Keys must be 32 bytes and match identically
    assert_eq!(alice_derived.len(), 32);
    assert_eq!(bob_derived.len(), 32);
    assert_eq!(
        alice_derived, bob_derived,
        "Alice and Bob must compute identical symmetric keys via ECDH + HKDF-SHA256"
    );

    // Can encrypt with Alice's key and decrypt with Bob's key
    let message = b"Secret message between Alice and Bob".to_vec();
    let ciphertext = encrypt_payload(alice_derived, message.clone()).expect("encryption failed");
    let decrypted = decrypt_payload(bob_derived, ciphertext).expect("decryption failed");
    assert_eq!(decrypted, message);
}

#[test]
fn test_ecdh_hkdf_context_info_prevents_uks_and_kci() {
    let alice = AstralKeypair::generate();
    let bob = AstralKeypair::generate();

    let alice_pub = alice.serialize_public_key();
    let bob_pub = bob.serialize_public_key();

    let custom_salt = b"Astral-Custom-Salt-v2".to_vec();
    let custom_info_1 = b"session-ctx:session-1".to_vec();
    let custom_info_2 = b"session-ctx:session-2".to_vec();

    // Key derived with info 1
    let key1 = alice
        .derive_symmetric_key_hkdf(
            bob_pub.clone(),
            Some(custom_salt.clone()),
            Some(custom_info_1),
        )
        .expect("derivation 1 failed");

    // Key derived with info 2 (different context binding)
    let key2 = alice
        .derive_symmetric_key_hkdf(
            bob_pub.clone(),
            Some(custom_salt.clone()),
            Some(custom_info_2),
        )
        .expect("derivation 2 failed");

    // Different context info must produce different keys (domain separation / UKS protection)
    assert_ne!(key1, key2);

    // Bob with same context info gets identical key
    let bob_key1 = bob
        .derive_symmetric_key_hkdf(
            alice_pub.clone(),
            Some(custom_salt),
            Some(b"session-ctx:session-1".to_vec()),
        )
        .expect("Bob derivation failed");
    assert_eq!(key1, bob_key1);
}

#[test]
fn test_ecdh_invalid_public_key() {
    let alice = AstralKeypair::generate();
    let corrupted_pub = vec![0x04; 30]; // Invalid length for P-256 public key

    assert!(matches!(
        alice.derive_symmetric_key(corrupted_pub),
        Err(AstralError::CryptoFailure { .. })
    ));
}
