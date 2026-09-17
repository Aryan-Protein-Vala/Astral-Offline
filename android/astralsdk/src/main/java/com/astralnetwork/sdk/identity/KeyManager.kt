package com.astralnetwork.sdk.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.astralnetwork.sdk.core.AstralConfig
import com.astralnetwork.sdk.core.AstralError
import com.astralnetwork.sdk.crypto.NoiseSession
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * KeyManager — Hardware-backed P256 key management.
 *
 * Security architecture:
 * - Keys generated in Android Keystore (StrongBox if available, TEE fallback)
 * - Private key NEVER leaves the secure hardware
 * - Signs with SHA256withECDSA (P256)
 * - Public key exported in X.509 SubjectPublicKeyInfo format
 *
 * Mirrors iOS KeyManager (SecureEnclave P256).
 */
class KeyManager(private val config: AstralConfig = AstralConfig.DEFAULT) {

    companion object {
        private const val TAG = "AstralSDK"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val PREFS_NAME = "astral_sdk_keys"
        private const val PREF_ENC_PRIVATE = "enc_private_key"
        private const val PREF_ENC_PUBLIC = "enc_public_key"
    }

    private var prefs: SharedPreferences? = null

    var walletID: WalletID? = null
        private set

    var isHardwareBacked: Boolean = false
        private set

    private var keyStore: KeyStore? = null

    /**
     * Initialize keys. Generates P256 keypair in Keystore if not already present.
     * Tries StrongBox first, falls back to TEE.
     */
    @Throws(AstralError::class)
    fun initialize(context: Context? = null) {
        try {
            keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val ks = keyStore ?: throw AstralError.SigningFailed

            if (!ks.containsAlias(config.keystoreAlias)) {
                generateKey()
            }

            // Derive WalletID from public key
            val pubKey = getPublicKeyBytes() ?: throw AstralError.SigningFailed
            walletID = WalletID.fromPublicKey(pubKey)

            // Generate or load persisted encryption keypair for ECDH
            // (Keystore signing key can't do ECDH — Android enforces PURPOSE_SIGN only)
            loadOrGenerateEncryptionKeyPair()

            Log.d(TAG, "KeyManager initialized — wallet: ${walletID?.short}")
            Log.d(TAG, "Hardware-backed signing: $isHardwareBacked")
        } catch (e: AstralError) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "KeyManager init failed", e)
            throw AstralError.SigningFailed
        }
    }

    private fun generateKey() {
        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE_PROVIDER
        )

        // Try StrongBox first
        if (config.useStrongBox) {
            try {
                val spec = KeyGenParameterSpec.Builder(
                    config.keystoreAlias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setIsStrongBoxBacked(true)
                    .build()

                kpg.initialize(spec)
                kpg.generateKeyPair()
                isHardwareBacked = true
                Log.d(TAG, "Key generated (StrongBox ✅)")
                return
            } catch (e: Exception) {
                Log.d(TAG, "StrongBox unavailable, falling back to TEE")
            }
        }

        // Fallback to TEE
        val teeSpec = KeyGenParameterSpec.Builder(
            config.keystoreAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        kpg.initialize(teeSpec)
        kpg.generateKeyPair()
        isHardwareBacked = true  // TEE is still hardware
        Log.d(TAG, "Key generated (TEE ✅)")
    }

    /** Sign data with private key in Keystore. Returns DER-encoded ECDSA signature. */
    fun sign(data: ByteArray): ByteArray? {
        return try {
            val ks = keyStore ?: return null
            val privateKey = ks.getKey(config.keystoreAlias, null) as? PrivateKey ?: return null

            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(privateKey)
            sig.update(data)
            sig.sign()
        } catch (e: Exception) {
            Log.e(TAG, "Signing failed", e)
            null
        }
    }

    /** Verify a signature against a given public key. */
    fun verify(data: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean {
        return try {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signature)
        } catch (e: Exception) {
            Log.e(TAG, "Verification failed", e)
            false
        }
    }

    /** Get raw public key bytes (X.509 encoded). */
    fun getPublicKeyBytes(): ByteArray? {
        return try {
            val ks = keyStore ?: return null
            ks.getCertificate(config.keystoreAlias)?.publicKey?.encoded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get public key", e)
            null
        }
    }

    /** Get the Java PublicKey object. */
    fun getPublicKey(): PublicKey? {
        return try {
            val ks = keyStore ?: return null
            ks.getCertificate(config.keystoreAlias)?.publicKey
        } catch (e: Exception) {
            null
        }
    }

    // ── Encryption Keypair (SOFTWARE — separate from Keystore signing key) ──
    //
    // Android Keystore keys with PURPOSE_SIGN cannot do ECDH key agreement.
    // So we maintain a separate software P256 keypair for encrypting/decrypting
    // payment channels. The Keystore key is ONLY for signing transactions.
    //
    // This matches iOS where SecureEnclave P256 is for signing, and a separate
    // software key handles the Noise ECDH.

    private var encryptionKeyPair: KeyPair? = null

    /**
     * Get the encryption public key bytes in x963 format (65 bytes: 0x04+X+Y).
     * This is the key embedded in QR codes. x963 format is cross-platform
     * compatible with iOS CryptoKit's P256.KeyAgreement.PublicKey.
     */
    fun getEncryptionPublicKeyBytes(): ByteArray? {
        val pubKey = encryptionKeyPair?.public ?: return null
        return NoiseSession.publicKeyToX963(pubKey)
    }

    /**
     * Get the encryption public key in x963 format (alias for getEncryptionPublicKeyBytes).
     * Used by NoiseSession.decrypt() for HKDF info binding.
     */
    fun getEncryptionPublicKeyX963(): ByteArray? = getEncryptionPublicKeyBytes()

    /**
     * Get the encryption private key (for NoiseSession.decrypt).
     * This is a SOFTWARE key — it CAN do ECDH, unlike the Keystore signing key.
     */
    fun getEncryptionPrivateKey(): PrivateKey? = encryptionKeyPair?.private

    private fun loadOrGenerateEncryptionKeyPair() {
        // Try to load persisted keypair first
        val savedPrivate = prefs?.getString(PREF_ENC_PRIVATE, null)
        val savedPublic = prefs?.getString(PREF_ENC_PUBLIC, null)

        if (savedPrivate != null && savedPublic != null) {
            try {
                val privBytes = Base64.decode(savedPrivate, Base64.NO_WRAP)
                val pubBytes = Base64.decode(savedPublic, Base64.NO_WRAP)
                val keyFactory = KeyFactory.getInstance("EC")
                val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val publicKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes))
                encryptionKeyPair = KeyPair(publicKey, privateKey)
                Log.d(TAG, "Encryption keypair loaded from storage ✅")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load saved encryption keypair, regenerating: ${e.message}")
            }
        }

        // Generate fresh keypair and persist it
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        encryptionKeyPair = kpg.generateKeyPair()

        // Save to SharedPreferences
        encryptionKeyPair?.let { kp ->
            prefs?.edit()
                ?.putString(PREF_ENC_PRIVATE, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
                ?.putString(PREF_ENC_PUBLIC, Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
                ?.apply()
        }
        Log.d(TAG, "Encryption keypair generated and persisted (software P256)")
    }
}

