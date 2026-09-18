package com.astralnetwork.sdk.identity

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.astralnetwork.sdk.crypto.NoiseSession
import com.astralnetwork.sdk.crypto.SignedTransaction
import com.astralnetwork.sdk.wallet.AstralTransaction
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * KeyManager — Hardware-Backed P-256 Key Management & Encrypted Key Storage.
 *
 * Security Architecture:
 *   - Signing Key: Hardware Keystore (StrongBox / TEE). Private key NEVER leaves hardware chip.
 *   - Key Agreement: Software P-256 keypair for ECDH.
 *   - Secure Storage: The ECDH private key is AES-256-GCM encrypted using an Android Keystore
 *     master key before writing to SharedPreferences, preventing extraction even with allowBackup="true".
 */
class KeyManager(private val useStrongBox: Boolean = true) {

    companion object {
        private const val TAG = "AstralSDK/KeyManager"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val SIGNING_KEY_ALIAS = "astral_signing_key_v1"
        private const val STORAGE_AES_ALIAS = "astral_ecdh_storage_aes_v1"
        private const val PREFS_NAME = "astral_sdk_keys_v1"
        private const val PREF_ENC_PRIVATE = "ecdh_private_key_encrypted"
        private const val PREF_ENC_PUBLIC = "ecdh_public_key"
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_LENGTH = 128

        /**
         * Verifies a SignedTransaction using its embedded sender public key and signature.
         */
        fun verifyTransaction(signed: SignedTransaction): Boolean {
            return signed.verify()
        }
    }

    var walletID: WalletID? = null
        private set

    var isHardwareBacked: Boolean = false
        private set

    var isStrongBoxBacked: Boolean = false
        private set

    val publicKeyBase64: String
        get() = getEcdhPublicKeyX963()?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: ""

    private var keyStore: KeyStore? = null
    private var prefs: SharedPreferences? = null

    // Separate software keypair for ECDH (Android Keystore PURPOSE_SIGN cannot do ECDH)
    private var ecdhKeyPair: KeyPair? = null

    // ── Initialization ─────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (keyStore?.containsAlias(SIGNING_KEY_ALIAS) == false) {
            generateSigningKey()
        } else {
            isHardwareBacked = true
        }

        // Derive WalletID from signing public key
        val pubKeyBytes = getSigningPublicKeyBytes()
            ?: throw IllegalStateException("Failed to read public key from Keystore")
        walletID = WalletID.fromPublicKey(pubKeyBytes)

        // Load or generate the ECDH keypair with Keystore AES-GCM encrypted persistence
        loadOrGenerateEcdhKeyPair()

        Log.d(TAG, "Initialized — wallet: ${walletID?.short} | StrongBox: $isStrongBoxBacked | HW: $isHardwareBacked")
    }

    // ── Signing Key (TEE / StrongBox) ──────────────────────────────────────────

    private fun generateSigningKey() {
        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE_PROVIDER
        )

        // Try StrongBox first — Titan M / SE chip, physically isolated
        if (useStrongBox) {
            try {
                val spec = KeyGenParameterSpec.Builder(
                    SIGNING_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setIsStrongBoxBacked(true)
                    .setUserAuthenticationRequired(false)
                    .build()

                kpg.initialize(spec)
                kpg.generateKeyPair()
                isHardwareBacked = true
                isStrongBoxBacked = true
                Log.d(TAG, "Signing key generated in StrongBox ✅")
                return
            } catch (e: Exception) {
                Log.w(TAG, "StrongBox unavailable, falling back to TEE: ${e.message}")
            }
        }

        // Fallback: TEE (hardware-isolated coprocessor)
        val teeSpec = KeyGenParameterSpec.Builder(
            SIGNING_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()

        kpg.initialize(teeSpec)
        kpg.generateKeyPair()
        isHardwareBacked = true
        isStrongBoxBacked = false
        Log.d(TAG, "Signing key generated in TEE ✅")
    }

    /**
     * Signs [data] using the hardware-backed Keystore key (ECDSA-P256-SHA256).
     * The private key operation happens inside the hardware secure element.
     */
    fun sign(data: ByteArray): ByteArray {
        val ks = keyStore ?: error("KeyManager not initialized")
        val privateKey = ks.getKey(SIGNING_KEY_ALIAS, null) as? PrivateKey
            ?: error("Signing key not found in Keystore")
        return Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(data)
        }.sign()
    }

    fun signTransaction(txn: AstralTransaction): SignedTransaction {
        val payload = txn.encode()
        return SignedTransaction.sign(payload, this)
    }

    fun verifySignature(data: ByteArray, signature: ByteArray, signerPublicKey: PublicKey): Boolean {
        return try {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(signerPublicKey)
                update(data)
            }.verify(signature)
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification exception", e)
            false
        }
    }

    /** Returns the X.509-encoded signing public key bytes. */
    fun getSigningPublicKeyBytes(): ByteArray? {
        return keyStore?.getCertificate(SIGNING_KEY_ALIAS)?.publicKey?.encoded
    }

    /** Returns the raw PublicKey object for signing. */
    fun getSigningPublicKey(): PublicKey? {
        return keyStore?.getCertificate(SIGNING_KEY_ALIAS)?.publicKey
    }

    /** Returns the signing public key in X9.63 uncompressed format (65 bytes: 0x04 || X || Y). */
    fun getSigningPublicKeyX963(): ByteArray? {
        val pub = getSigningPublicKey() ?: return null
        return NoiseSession.publicKeyToX963(pub)
    }

    // ── ECDH Keypair (Software P-256 with Hardware-Encrypted Storage) ──────────

    /**
     * Returns the ECDH public key in X9.63 uncompressed format (0x04 || X || Y, 65 bytes).
     * Embedded in QR codes for incoming payment channels.
     */
    fun getEcdhPublicKeyX963(): ByteArray? {
        val pub = ecdhKeyPair?.public as? ECPublicKey ?: return null
        return NoiseSession.publicKeyToX963(pub)
    }

    fun getEcdhPrivateKey(): PrivateKey? = ecdhKeyPair?.private

    fun getEcdhPublicKey(): PublicKey? = ecdhKeyPair?.public

    // ── Keystore-Encrypted Private Key Storage ─────────────────────────────────

    private fun getOrCreateStorageKey(): SecretKey {
        val ks = keyStore ?: error("KeyStore not initialized")
        if (!ks.containsAlias(STORAGE_AES_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )
            val builder = KeyGenParameterSpec.Builder(
                STORAGE_AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)

            if (useStrongBox) {
                try {
                    builder.setIsStrongBoxBacked(true)
                    keyGenerator.init(builder.build())
                    return keyGenerator.generateKey()
                } catch (e: Exception) {
                    Log.w(TAG, "StrongBox unavailable for storage key, falling back to standard TEE")
                    builder.setIsStrongBoxBacked(false)
                }
            }

            keyGenerator.init(builder.build())
            return keyGenerator.generateKey()
        }

        val entry = ks.getEntry(STORAGE_AES_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    private fun encryptPrivateKey(privKeyBytes: ByteArray): String {
        val secretKey = getOrCreateStorageKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(privKeyBytes)
        val combined = iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptPrivateKey(encodedData: String): ByteArray {
        val combined = Base64.decode(encodedData, Base64.NO_WRAP)
        require(combined.size > GCM_IV_SIZE) { "Encrypted data too short" }
        val iv = combined.copyOfRange(0, GCM_IV_SIZE)
        val ciphertext = combined.copyOfRange(GCM_IV_SIZE, combined.size)

        val secretKey = getOrCreateStorageKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }

    private fun loadOrGenerateEcdhKeyPair() {
        val savedEncryptedPriv = prefs?.getString(PREF_ENC_PRIVATE, null)
        val savedPub = prefs?.getString(PREF_ENC_PUBLIC, null)

        if (savedEncryptedPriv != null && savedPub != null) {
            try {
                val decryptedPrivBytes = decryptPrivateKey(savedEncryptedPriv)
                val keyFactory = KeyFactory.getInstance("EC")
                val priv = keyFactory.generatePrivate(PKCS8EncodedKeySpec(decryptedPrivBytes))
                val pub = keyFactory.generatePublic(X509EncodedKeySpec(Base64.decode(savedPub, Base64.NO_WRAP)))
                ecdhKeyPair = KeyPair(pub, priv)
                Log.d(TAG, "ECDH keypair decrypted and loaded from secure Keystore storage ✅")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Could not load secured ECDH keypair, regenerating: ${e.message}")
            }
        }

        // Generate fresh EC keypair
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val newKeyPair = kpg.generateKeyPair()
        ecdhKeyPair = newKeyPair

        try {
            // Encrypt private key with Keystore AES-256-GCM before saving
            val encryptedPriv = encryptPrivateKey(newKeyPair.private.encoded)
            prefs?.edit()
                ?.putString(PREF_ENC_PRIVATE, encryptedPriv)
                ?.putString(PREF_ENC_PUBLIC, Base64.encodeToString(newKeyPair.public.encoded, Base64.NO_WRAP))
                ?.apply()
            Log.d(TAG, "ECDH keypair generated and saved with Keystore AES-GCM encryption ✅")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to securely persist ECDH keypair", e)
        }
    }
}
