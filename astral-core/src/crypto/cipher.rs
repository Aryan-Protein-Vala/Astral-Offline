use chacha20poly1305::{
    aead::{Aead, AeadCore, KeyInit, OsRng, Payload},
    ChaCha20Poly1305, Key, Nonce,
};
use crate::errors::AstralError;

pub const NONCE_SIZE: usize = 12;
pub const TAG_SIZE: usize = 16;
pub const MIN_PACKED_SIZE: usize = NONCE_SIZE + TAG_SIZE; // 28 bytes

/// Encrypts an arbitrary payload using ChaCha20-Poly1305 with Associated Authenticated Data (AAD).
/// Returns the 12-byte nonce prepended to the ciphertext and 16-byte Poly1305 MAC tag.
#[uniffi::export]
pub fn encrypt_payload_with_aad(
    symmetric_key: Vec<u8>,
    payload: Vec<u8>,
    aad: Vec<u8>,
) -> Result<Vec<u8>, AstralError> {
    if symmetric_key.len() != 32 {
        return Err(AstralError::CryptoFailure {
            reason: "Key must be 32 bytes".into(),
        });
    }
    let key = Key::from_slice(&symmetric_key);
    let cipher = ChaCha20Poly1305::new(key);
    let nonce = ChaCha20Poly1305::generate_nonce(&mut OsRng);

    let aead_payload = Payload {
        msg: payload.as_slice(),
        aad: aad.as_slice(),
    };

    let ciphertext = cipher.encrypt(&nonce, aead_payload).map_err(|_| AstralError::CryptoFailure {
        reason: "Encryption failed (Poly1305)".into(),
    })?;

    let mut packed = nonce.to_vec();
    packed.extend(ciphertext);
    Ok(packed)
}

/// Encrypts an arbitrary payload using ChaCha20-Poly1305 without AAD.
/// Returns the 12-byte nonce prepended to the ciphertext and Poly1305 tag.
#[uniffi::export]
pub fn encrypt_payload(symmetric_key: Vec<u8>, payload: Vec<u8>) -> Result<Vec<u8>, AstralError> {
    encrypt_payload_with_aad(symmetric_key, payload, Vec::new())
}

/// Decrypts a payload encrypted with `encrypt_payload_with_aad`.
/// Expects the first 12 bytes to be the nonce, followed by ciphertext and 16-byte Poly1305 tag.
#[uniffi::export]
pub fn decrypt_payload_with_aad(
    symmetric_key: Vec<u8>,
    packed_data: Vec<u8>,
    aad: Vec<u8>,
) -> Result<Vec<u8>, AstralError> {
    if symmetric_key.len() != 32 {
        return Err(AstralError::CryptoFailure {
            reason: "Key must be 32 bytes".into(),
        });
    }
    if packed_data.len() < MIN_PACKED_SIZE {
        return Err(AstralError::CryptoFailure {
            reason: format!(
                "Ciphertext too short: {} bytes (requires at least 12-byte nonce + 16-byte Poly1305 MAC tag = 28 bytes)",
                packed_data.len()
            ),
        });
    }

    let key = Key::from_slice(&symmetric_key);
    let cipher = ChaCha20Poly1305::new(key);
    let nonce = Nonce::from_slice(&packed_data[0..NONCE_SIZE]);
    let ciphertext = &packed_data[NONCE_SIZE..];

    let aead_payload = Payload {
        msg: ciphertext,
        aad: aad.as_slice(),
    };

    cipher.decrypt(nonce, aead_payload).map_err(|_| AstralError::CryptoFailure {
        reason: "Decryption or MAC verification failed (Poly1305)".into(),
    })
}

/// Decrypts a payload encrypted with `encrypt_payload`.
/// Expects the first 12 bytes to be the nonce, followed by ciphertext and 16-byte Poly1305 tag.
#[uniffi::export]
pub fn decrypt_payload(symmetric_key: Vec<u8>, packed_data: Vec<u8>) -> Result<Vec<u8>, AstralError> {
    decrypt_payload_with_aad(symmetric_key, packed_data, Vec::new())
}
