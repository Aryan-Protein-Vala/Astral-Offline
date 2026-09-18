use p256::{
    elliptic_curve::sec1::ToEncodedPoint,
    PublicKey, SecretKey,
};
use rand_core::OsRng;
use crate::errors::AstralError;
use std::sync::Arc;
use hkdf::Hkdf;
use sha2::Sha256;

pub const DEFAULT_HKDF_SALT: &[u8] = b"AstralPayment-P256-v1";

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

    /// Creates an AstralKeypair from 32-byte raw secret scalar.
    #[uniffi::constructor]
    pub fn from_secret_bytes(bytes: Vec<u8>) -> Result<Arc<Self>, AstralError> {
        let secret = SecretKey::from_slice(&bytes).map_err(|_| AstralError::CryptoFailure {
            reason: "Invalid P-256 secret key scalar".into(),
        })?;
        let public_key = secret.public_key();
        Ok(Arc::new(Self { secret, public_key }))
    }

    /// Serializes the public key to the X9.62 uncompressed format (0x04 prefix + 32-byte X + 32-byte Y).
    pub fn serialize_public_key(&self) -> Vec<u8> {
        self.public_key.to_encoded_point(false).as_bytes().to_vec()
    }

    /// Derives a 32-byte symmetric key from the local Secret and a remote Public Key
    /// using HKDF-SHA256 with explicit salt and context info to protect against
    /// Unknown Key Share (UKS) and Key Compromise Impersonation (KCI) attacks.
    pub fn derive_symmetric_key_hkdf(
        &self,
        remote_pub_bytes: Vec<u8>,
        salt: Option<Vec<u8>>,
        info: Option<Vec<u8>>,
    ) -> Result<Vec<u8>, AstralError> {
        let remote_pub = PublicKey::from_sec1_bytes(&remote_pub_bytes).map_err(|_| AstralError::CryptoFailure {
            reason: "Invalid uncompressed P-256 public key".into(),
        })?;

        let shared = p256::ecdh::diffie_hellman(self.secret.to_nonzero_scalar(), remote_pub.as_affine());
        let shared_bytes = shared.raw_secret_bytes();

        let salt_ref = salt.as_deref().unwrap_or(DEFAULT_HKDF_SALT);
        let hk = Hkdf::<Sha256>::new(Some(salt_ref), shared_bytes.as_slice());

        // Standard canonical public key ordering for context info to ensure both parties
        // independently arrive at the identical shared symmetric key while binding both identities
        // against UKS and KCI attacks.
        let local_pub = self.serialize_public_key();
        let canonical_info = if local_pub < remote_pub_bytes {
            [local_pub.as_slice(), remote_pub_bytes.as_slice()].concat()
        } else {
            [remote_pub_bytes.as_slice(), local_pub.as_slice()].concat()
        };

        let info_ref = info.as_deref().unwrap_or(&canonical_info);

        let mut okm = [0u8; 32];
        hk.expand(info_ref, &mut okm).map_err(|_| AstralError::CryptoFailure {
            reason: "HKDF-SHA256 expansion failed".into(),
        })?;

        Ok(okm.to_vec())
    }

    /// Derives a 32-byte symmetric key from the local Secret and a remote Public Key
    /// using HKDF-SHA256 with domain salt and canonical public key context info.
    pub fn derive_symmetric_key(&self, remote_pub_bytes: Vec<u8>) -> Result<Vec<u8>, AstralError> {
        self.derive_symmetric_key_hkdf(remote_pub_bytes, None, None)
    }
}
