use chacha20poly1305::{
    aead::{Aead, AeadCore, KeyInit, OsRng},
    ChaCha20Poly1305, Key, Nonce,
};
use crate::errors::AstralError;

/// Encrypts an arbitrary payload using ChaCha20-Poly1305.
/// Returns the nonce prepended to the ciphertext.
#[uniffi::export]
pub fn encrypt_payload(symmetric_key: Vec<u8>, payload: Vec<u8>) -> Result<Vec<u8>, AstralError> {
    if symmetric_key.len() != 32 {
        return Err(AstralError::CryptoFailure { reason: "Key must be 32 bytes".into() });
    }
    let key = Key::from_slice(&symmetric_key);
    let cipher = ChaCha20Poly1305::new(key);
    let nonce = ChaCha20Poly1305::generate_nonce(&mut OsRng);

    let ciphertext = cipher.encrypt(&nonce, payload.as_slice()).map_err(|_| AstralError::CryptoFailure {
        reason: "Encryption failed (Poly1305)".into(),
    })?;

    let mut packed = nonce.to_vec();
    packed.extend(ciphertext);
    Ok(packed)
}

/// Decrypts a payload encrypted with `encrypt_payload`.
/// Expects the first 12 bytes to be the nonce.
#[uniffi::export]
pub fn decrypt_payload(symmetric_key: Vec<u8>, packed_data: Vec<u8>) -> Result<Vec<u8>, AstralError> {
    if symmetric_key.len() != 32 {
        return Err(AstralError::CryptoFailure { reason: "Key must be 32 bytes".into() });
    }
    if packed_data.len() < 12 {
        return Err(AstralError::CryptoFailure {
            reason: "Ciphertext too short (missing nonce)".into(),
        });
    }

    let key = Key::from_slice(&symmetric_key);
    let cipher = ChaCha20Poly1305::new(key);
    let nonce = Nonce::from_slice(&packed_data[0..12]);
    let ciphertext = &packed_data[12..];

    cipher.decrypt(nonce, ciphertext).map_err(|_| AstralError::CryptoFailure {
        reason: "Decryption or MAC verification failed (Poly1305)".into(),
    })
}
