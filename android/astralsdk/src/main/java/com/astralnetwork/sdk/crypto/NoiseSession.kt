package com.astralnetwork.sdk.crypto

import com.astralnetwork.sdk.core.AstralError
import com.astralnetwork.sdk.identity.KeyManager
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NoiseSession — P256 ECDH + HKDF-SHA256 + ChaCha20-Poly1305 encryption channel.
 *
 * Matches the iOS wire format:
 *   [Version: 1B = 0x01][EphemeralPubKey: 65B X9.63][Nonce: 12B][Ciphertext + 16B Poly1305 Tag]
 *
 * Wire payload is verified and decrypted before any transaction processing.
 */
object NoiseSession {

    private const val PROTOCOL_VERSION: Byte = 0x01
    private const val X963_KEY_SIZE = 65
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private const val HEADER_SIZE = 1 + X963_KEY_SIZE // 66 bytes: version + pubKey
    private const val SALT = "AstralPayment-P256-v1"

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Encrypts plaintext for a recipient using their X9.63 uncompressed P-256 public key.
     *
     * Wire format output:
     *   [0x01 (1B)][ephemeralPubKey (65B)][nonce (12B)][ciphertext + tag (16B)]
     */
    fun encrypt(plaintext: ByteArray, merchantPublicKey: ByteArray): ByteArray {
        require(merchantPublicKey.size == X963_KEY_SIZE && merchantPublicKey[0] == 0x04.toByte()) {
            "Invalid recipient public key: expected 65-byte X9.63 uncompressed key"
        }

        // 1. Generate ephemeral P-256 keypair
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val ephemeralKeyPair = kpg.generateKeyPair()
        val ephemeralPubKeyBytes = publicKeyToX963(ephemeralKeyPair.public)

        // 2. Perform ECDH to derive shared secret
        val remotePubKey = x963ToPublicKey(merchantPublicKey)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ephemeralKeyPair.private)
        ka.doPhase(remotePubKey, true)
        val sharedSecret = ka.generateSecret()

        // 3. HKDF-SHA256 derivation with salt and shared info: ephemeralPub || recipientPub
        val saltBytes = SALT.toByteArray(Charsets.UTF_8)
        val sharedInfo = ephemeralPubKeyBytes + merchantPublicKey
        val symmetricKey = hkdfSha256(sharedSecret, saltBytes, sharedInfo, 32)

        // 4. Generate random 12-byte nonce
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }

        // 5. Encrypt with ChaCha20-Poly1305
        val ciphertextAndTag = chacha20Poly1305Encrypt(plaintext, symmetricKey, nonce)

        // 6. Build wire packet: [0x01][ephemeralPubKey: 65B][nonce: 12B][ciphertextAndTag]
        val result = ByteBuffer.allocate(HEADER_SIZE + NONCE_SIZE + ciphertextAndTag.size)
            .put(PROTOCOL_VERSION)
            .put(ephemeralPubKeyBytes)
            .put(nonce)
            .put(ciphertextAndTag)
            .array()

        return result
    }

    /**
     * Decrypts wire format data using the receiver's KeyManager.
     */
    fun decrypt(wireData: ByteArray, keyManager: KeyManager): ByteArray {
        val privKey = keyManager.getEcdhPrivateKey()
            ?: throw AstralError.PaymentFailed("ECDH private key unavailable in KeyManager")
        val localPubBytes = keyManager.getEcdhPublicKeyX963()
            ?: throw AstralError.PaymentFailed("ECDH public key unavailable in KeyManager")
        return decrypt(wireData, privKey, localPubBytes)
    }

    /**
     * Decrypts wire format data using a P-256 private key and local X9.63 public key.
     */
    fun decrypt(wireData: ByteArray, privateKey: PrivateKey, localPublicKeyX963: ByteArray): ByteArray {
        if (wireData.size < HEADER_SIZE + NONCE_SIZE + TAG_SIZE) {
            throw AstralError.PacketDecodingFailed
        }
        if (wireData[0] != PROTOCOL_VERSION) {
            throw AstralError.PacketDecodingFailed
        }

        val ephemeralPubBytes = wireData.copyOfRange(1, 1 + X963_KEY_SIZE)
        val nonce = wireData.copyOfRange(HEADER_SIZE, HEADER_SIZE + NONCE_SIZE)
        val ciphertextAndTag = wireData.copyOfRange(HEADER_SIZE + NONCE_SIZE, wireData.size)

        val ephemeralPub = try {
            x963ToPublicKey(ephemeralPubBytes)
        } catch (e: Exception) {
            throw AstralError.PacketDecodingFailed
        }

        // ECDH with local private key + ephemeral public key
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(ephemeralPub, true)
        val sharedSecret = ka.generateSecret()

        // HKDF-SHA256 symmetric key derivation
        val saltBytes = SALT.toByteArray(Charsets.UTF_8)
        val sharedInfo = ephemeralPubBytes + localPublicKeyX963
        val symmetricKey = hkdfSha256(sharedSecret, saltBytes, sharedInfo, 32)

        return try {
            chacha20Poly1305Decrypt(ciphertextAndTag, symmetricKey, nonce)
        } catch (e: Exception) {
            throw AstralError.VerificationFailed
        }
    }

    // ── X9.63 Public Key Conversions ──────────────────────────────────────────

    /**
     * Converts a 65-byte uncompressed X9.63 (or X9.62) EC public key (0x04 || X || Y) into a JCE PublicKey.
     */
    fun x963ToPublicKey(bytes: ByteArray): PublicKey {
        require(bytes.size == X963_KEY_SIZE && bytes[0] == 0x04.toByte()) {
            "Invalid X9.63 key: expected 65 bytes starting with 0x04"
        }

        // Standard X.509 SubjectPublicKeyInfo prefix for secp256r1
        val x509Prefix = byteArrayOf(
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
            0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00
        )
        val x509Bytes = x509Prefix + bytes

        return try {
            val keyFactory = KeyFactory.getInstance("EC")
            keyFactory.generatePublic(X509EncodedKeySpec(x509Bytes))
        } catch (e: Exception) {
            // Fallback via ECPublicKeySpec
            val x = BigInteger(1, bytes.copyOfRange(1, 33))
            val y = BigInteger(1, bytes.copyOfRange(33, 65))
            val point = ECPoint(x, y)
            val params = AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec("secp256r1"))
            }
            val ecSpec = params.getParameterSpec(ECParameterSpec::class.java)
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, ecSpec))
        }
    }

    /**
     * Converts a JCE EC PublicKey into a 65-byte uncompressed X9.63 key (0x04 || X || Y).
     */
    fun publicKeyToX963(pubKey: PublicKey): ByteArray {
        val ecPub = pubKey as? ECPublicKey
            ?: throw IllegalArgumentException("Expected an ECPublicKey")

        val x = normalizeCoordinate(ecPub.w.affineX.toByteArray(), 32)
        val y = normalizeCoordinate(ecPub.w.affineY.toByteArray(), 32)
        return byteArrayOf(0x04) + x + y
    }

    private fun normalizeCoordinate(coord: ByteArray, size: Int): ByteArray {
        return when {
            coord.size == size -> coord
            coord.size > size -> coord.copyOfRange(coord.size - size, coord.size)
            else -> ByteArray(size - coord.size) + coord
        }
    }

    // ── HKDF-SHA256 (RFC 5869) ────────────────────────────────────────────────

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // Step 1: Extract PRK = HMAC-SHA256(salt, ikm)
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = extractMac.doFinal(ikm)

        // Step 2: Expand OKM = HMAC-SHA256(PRK, info || 0x01) for length <= 32
        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        expandMac.update(info)
        expandMac.update(0x01.toByte())
        val okm = expandMac.doFinal()
        return okm.copyOf(length)
    }

    // ── ChaCha20-Poly1305 AEAD (RFC 7539 / RFC 8439) ──────────────────────────

    private fun chacha20Poly1305Encrypt(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        // Try platform JCE first
        try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(key, "ChaCha20")
            val paramSpec = try {
                Class.forName("javax.crypto.spec.GCMParameterSpec")
                    .getConstructor(Int::class.javaPrimitiveType, ByteArray::class.java)
                    .newInstance(128, nonce) as java.security.spec.AlgorithmParameterSpec
            } catch (_: Exception) {
                IvParameterSpec(nonce)
            }
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec)
            return cipher.doFinal(plaintext)
        } catch (_: Throwable) {
            // Pure Kotlin RFC 7539 AEAD fallback
            return rfc7539AeadEncrypt(plaintext, key, nonce)
        }
    }

    private fun chacha20Poly1305Decrypt(ciphertextAndTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        if (ciphertextAndTag.size < TAG_SIZE) {
            throw IllegalArgumentException("Ciphertext too short for Poly1305 tag")
        }

        // Try platform JCE first
        try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(key, "ChaCha20")
            val paramSpec = try {
                Class.forName("javax.crypto.spec.GCMParameterSpec")
                    .getConstructor(Int::class.javaPrimitiveType, ByteArray::class.java)
                    .newInstance(128, nonce) as java.security.spec.AlgorithmParameterSpec
            } catch (_: Exception) {
                IvParameterSpec(nonce)
            }
            cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec)
            return cipher.doFinal(ciphertextAndTag)
        } catch (_: Throwable) {
            // Pure Kotlin RFC 7539 AEAD fallback
            return rfc7539AeadDecrypt(ciphertextAndTag, key, nonce)
        }
    }

    // ── Pure RFC 7539 Implementation ──────────────────────────────────────────

    private fun rfc7539AeadEncrypt(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        // 1. One-time Poly1305 key generated from block 0
        val polyKey = generatePoly1305Key(key, nonce)

        // 2. Encrypt plaintext using ChaCha20 starting at counter = 1
        val ciphertext = chacha20Process(plaintext, key, nonce, initialCounter = 1)

        // 3. Poly1305 MAC over (ciphertext || pad || 0L (aad len) || ciphertext.len)
        val macData = buildAeadMacData(ciphertext)
        val tag = poly1305Mac(macData, polyKey)

        return ciphertext + tag
    }

    private fun rfc7539AeadDecrypt(ciphertextAndTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipherLen = ciphertextAndTag.size - TAG_SIZE
        val ciphertext = ciphertextAndTag.copyOfRange(0, cipherLen)
        val receivedTag = ciphertextAndTag.copyOfRange(cipherLen, ciphertextAndTag.size)

        val polyKey = generatePoly1305Key(key, nonce)
        val macData = buildAeadMacData(ciphertext)
        val calculatedTag = poly1305Mac(macData, polyKey)

        // Constant time comparison
        if (!constantTimeEquals(calculatedTag, receivedTag)) {
            throw SecurityException("Poly1305 tag verification failed")
        }

        return chacha20Process(ciphertext, key, nonce, initialCounter = 1)
    }

    private fun buildAeadMacData(ciphertext: ByteArray): ByteArray {
        val padLen = if (ciphertext.size % 16 != 0) 16 - (ciphertext.size % 16) else 0
        val totalSize = ciphertext.size + padLen + 16
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(ciphertext)
        if (padLen > 0) buf.put(ByteArray(padLen))
        buf.putLong(0L) // AAD length = 0
        buf.putLong(ciphertext.size.toLong())
        return buf.array()
    }

    private fun generatePoly1305Key(key: ByteArray, nonce: ByteArray): ByteArray {
        val block0 = chacha20Block(key, nonce, counter = 0)
        return block0.copyOfRange(0, 32)
    }

    private fun chacha20Process(input: ByteArray, key: ByteArray, nonce: ByteArray, initialCounter: Int): ByteArray {
        val output = ByteArray(input.size)
        var counter = initialCounter
        var offset = 0
        while (offset < input.size) {
            val keystream = chacha20Block(key, nonce, counter)
            val toXor = minOf(64, input.size - offset)
            for (i in 0 until toXor) {
                output[offset + i] = (input[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += toXor
            counter++
        }
        return output
    }

    private fun chacha20Block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val state = IntArray(16)
        // Constants: "expand 32-byte k"
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574

        val keyBuf = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 4..11) state[i] = keyBuf.int

        state[12] = counter

        val nonceBuf = ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN)
        state[13] = nonceBuf.int
        state[14] = nonceBuf.int
        state[15] = nonceBuf.int

        val working = state.clone()
        for (i in 0 until 10) {
            // Column rounds
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            // Diagonal rounds
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }

        val outBuf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 16) {
            outBuf.putInt(working[i] + state[i])
        }
        return outBuf.array()
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    private fun poly1305Mac(data: ByteArray, key: ByteArray): ByteArray {
        // Clamp r
        val rBytes = key.copyOfRange(0, 16)
        rBytes[3] = (rBytes[3].toInt() and 15).toByte()
        rBytes[7] = (rBytes[7].toInt() and 15).toByte()
        rBytes[11] = (rBytes[11].toInt() and 15).toByte()
        rBytes[15] = (rBytes[15].toInt() and 15).toByte()
        rBytes[4] = (rBytes[4].toInt() and 252).toByte()
        rBytes[8] = (rBytes[8].toInt() and 252).toByte()
        rBytes[12] = (rBytes[12].toInt() and 252).toByte()

        // Little-endian BigIntegers
        val r = BigInteger(1, rBytes.reversedArray())
        val s = BigInteger(1, key.copyOfRange(16, 32).reversedArray())
        val p = BigInteger.valueOf(2).pow(130).subtract(BigInteger.valueOf(5))

        var a = BigInteger.ZERO

        var offset = 0
        while (offset < data.size) {
            val chunkLen = minOf(16, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + chunkLen)
            // Append 0x01 byte to chunk and convert from little-endian
            val chunkWithOne = chunk + byteArrayOf(0x01)
            val n = BigInteger(1, chunkWithOne.reversedArray())
            a = a.add(n).multiply(r).mod(p)
            offset += chunkLen
        }

        a = a.add(s).mod(BigInteger.valueOf(2).pow(128))
        val aBytes = a.toByteArray()
        val tagLE = ByteArray(16)
        val cleanBytes = if (aBytes.size > 1 && aBytes[0] == 0.toByte()) aBytes.copyOfRange(1, aBytes.size) else aBytes
        val reversed = cleanBytes.reversedArray()
        System.arraycopy(reversed, 0, tagLE, 0, minOf(16, reversed.size))
        return tagLE
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
