use p256::{
    PublicKey, SecretKey,
    elliptic_curve::sec1::ToEncodedPoint,
};
use rand_core::OsRng;
use crate::errors::AstralError;
use std::sync::Arc;

#[derive(uniffi::Object)]
pub struct AstralKeypair {
    secret: SecretKey,
    public_key: PublicKey,
}

#[uniffi::export]
impl AstralKeypair {
    /// Generates a new NIST P-256 keypair.
    #[uniffi::constructor]
    pub fn generate() -> Arc<Self> {
        let secret = SecretKey::random(&mut OsRng);
        let public_key = secret.public_key();
        Arc::new(Self { secret, public_key })
    }

    /// Serializes the public key to the X9.62 uncompressed format (0x04 prefix + 32-byte X + 32-byte Y).
    pub fn serialize_public_key(&self) -> Vec<u8> {
        self.public_key.to_encoded_point(false).as_bytes().to_vec()
    }

    /// Derives a 32-byte symmetric key from the local Secret and a remote Public Key using BLAKE3 as a KDF.
    pub fn derive_symmetric_key(&self, remote_pub_bytes: Vec<u8>) -> Result<Vec<u8>, AstralError> {
        let remote_pub = PublicKey::from_sec1_bytes(&remote_pub_bytes).map_err(|_| AstralError::CryptoFailure {
            reason: "Invalid uncompressed P-256 public key".into(),
        })?;
        
        let shared = p256::ecdh::diffie_hellman(self.secret.to_nonzero_scalar(), remote_pub.as_affine());
        Ok(blake3::hash(shared.raw_secret_bytes().as_slice()).as_bytes().to_vec())
    }
}
