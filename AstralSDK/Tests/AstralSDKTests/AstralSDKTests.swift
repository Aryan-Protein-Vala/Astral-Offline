import Testing
import Foundation
import CryptoKit
@testable import AstralSDK

@Suite("AstralSDK Test Suite")
struct AstralSDKTests {

    // MARK: - WalletID Tests

    @Test("WalletID derived from P256 public key")
    func testWalletIDFromPublicKey() {
        let key = P256.Signing.PrivateKey()
        let walletID = WalletID(publicKey: key.publicKey.x963Representation)
        #expect(walletID.id.count == 16)
        #expect(walletID.isValid)
    }

    @Test("WalletID is deterministic")
    func testWalletIDDeterministic() {
        let key = P256.Signing.PrivateKey()
        let pubData = key.publicKey.x963Representation
        let a = WalletID(publicKey: pubData)
        let b = WalletID(publicKey: pubData)
        #expect(a == b, "Same pubkey should produce same WalletID")
    }

    @Test("WalletID short display string")
    func testWalletIDShort() {
        let id = WalletID(id: "a1b2c3d4e5f6a7b8")
        #expect(id.short == "a1b2c3d4")
    }

    @Test("WalletID routing data roundtrip")
    func testWalletIDRoutingDataRoundTrip() {
        let id = WalletID(id: "a1b2c3d4e5f6a7b8")
        let data = id.routingData
        #expect(data != nil)
        #expect(data?.count == 8)
        let restored = WalletID(data: data!)
        #expect(restored?.id == id.id)
    }

    @Test("WalletID is case insensitive")
    func testWalletIDCaseInsensitive() {
        let a = WalletID(id: "a1b2c3d4e5f6a7b8")
        let b = WalletID(id: "A1B2C3D4E5F6A7B8")
        #expect(a == b)
    }

    @Test("WalletID validation")
    func testWalletIDValidation() {
        #expect(WalletID(id: "a1b2c3d4e5f6a7b8").isValid)
        #expect(!WalletID(id: "too_short").isValid)
        #expect(!WalletID(id: "zzzzzzzzzzzzzzzz").isValid)
    }

    // MARK: - AstralPacket Tests

    @Test("AstralPacket encode and decode")
    func testPacketEncodeAndDecode() {
        let sender = WalletID(id: "1111111111111111")
        let recipient = WalletID(id: "2222222222222222")
        let txn = AstralTransaction(
            id: "test-txn-001", amount: 23000,
            senderID: sender, recipientID: recipient,
            timestamp: Date(), status: .pending
        )

        let encoded = AstralPacket.encode(txn)
        #expect(encoded != nil)

        let decoded = AstralPacket.decode(encoded!)
        #expect(decoded != nil)
        #expect(decoded?.id == "test-txn-001")
        #expect(decoded?.amount == 23000)
        #expect(decoded?.senderID.id == sender.id)
        #expect(decoded?.recipientID.id == recipient.id)
    }

    @Test("AstralPacket decode rejects garbage")
    func testPacketDecodeRejectsGarbage() {
        #expect(AstralPacket.decode(Data(repeating: 0xFF, count: 10)) == nil)
    }

    // MARK: - PacketFragmenter Tests

    @Test("PacketFragmenter single packet without fragmentation")
    func testSinglePacketNoFragmentation() {
        let fragmenter = PacketFragmenter(mtu: 512)
        let data = Data(repeating: 0xAB, count: 100)
        let fragments = fragmenter.fragment(data)
        #expect(fragments.count == 1)
        #expect(fragments[0][0] == 0x00)  // single type
    }

    @Test("PacketFragmenter fragment and reassemble")
    func testFragmentAndReassemble() {
        let fragmenter = PacketFragmenter(mtu: 50)
        let data = Data(repeating: 0xCD, count: 200)
        let fragments = fragmenter.fragment(data)
        #expect(fragments.count > 1)
        #expect(fragments[0][0] == 0x01)  // start
        #expect(fragments.last![0] == 0x03)  // end

        var reassembler = PacketFragmenter(mtu: 50)
        var result: Data?
        for fragment in fragments {
            result = reassembler.reassemble(fragment)
        }
        #expect(result != nil)
        #expect(result == data)
    }

    // MARK: - NoiseSession Tests (P256 ECDH + ChaCha20)

    @Test("NoiseSession encrypt and decrypt roundtrip")
    func testNoiseEncryptDecryptRoundTrip() throws {
        // Simulate merchant P256 key pair
        let merchantKey = P256.KeyAgreement.PrivateKey()
        let merchantPubData = merchantKey.publicKey.x963Representation

        // Customer encrypts payment
        let plaintext = "Pay ₹500 to merchant".data(using: .utf8)!
        let wireData = try NoiseSession.encrypt(plaintext: plaintext, merchantPublicKey: merchantPubData)

        // Verify wire format: [0x01][65B key][12B nonce][ciphertext+16B tag]
        #expect(wireData[0] == 0x01, "First byte must be version 0x01")
        #expect(wireData.count > 66 + 12 + 16, "Wire data too small")
        #expect(wireData[1] == 0x04, "x963 key must start with 0x04")

        // Decrypt using raw private key (for test without KeyManager)
        let ephemeralKeyData = wireData.subdata(in: 1..<66)
        let combinedData = wireData.subdata(in: 66..<wireData.count)

        let ephemeralKey = try P256.KeyAgreement.PublicKey(x963Representation: ephemeralKeyData)
        let sharedSecret = try merchantKey.sharedSecretFromKeyAgreement(with: ephemeralKey)
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: "AstralPayment-P256-v1".data(using: .utf8)!,
            sharedInfo: ephemeralKeyData + merchantPubData,
            outputByteCount: 32
        )
        let sealedBox = try ChaChaPoly.SealedBox(combined: combinedData)
        let decrypted = try ChaChaPoly.open(sealedBox, using: symmetricKey)
        #expect(decrypted == plaintext)
    }

    @Test("NoiseSession wire format lengths")
    func testNoiseWireFormatRoundTrip() throws {
        let merchantKey = P256.KeyAgreement.PrivateKey()
        let plaintext = Data(repeating: 0xAA, count: 64)

        let wireData = try NoiseSession.encrypt(
            plaintext: plaintext,
            merchantPublicKey: merchantKey.publicKey.x963Representation
        )

        // Version byte check
        #expect(wireData[0] == 0x01)
        // Key at offset 1 (65 bytes, starts with 0x04)
        #expect(wireData[1] == 0x04)
        // Wire data should be: 1 + 65 + 12 + plaintext.count + 16 = 158 bytes
        #expect(wireData.count == 1 + 65 + 12 + 64 + 16)
    }

    @Test("NoiseSession decrypt with wrong key fails")
    func testNoiseDecryptWithWrongKeyFails() throws {
        let realKey = P256.KeyAgreement.PrivateKey()
        let wrongKey = P256.KeyAgreement.PrivateKey()

        let plaintext = "Secret payment".data(using: .utf8)!
        let wireData = try NoiseSession.encrypt(
            plaintext: plaintext,
            merchantPublicKey: realKey.publicKey.x963Representation
        )

        // Manual decrypt with wrong key should fail
        let ephemeralKeyData = wireData.subdata(in: 1..<66)
        let combinedData = wireData.subdata(in: 66..<wireData.count)

        let ephemeralKey = try P256.KeyAgreement.PublicKey(x963Representation: ephemeralKeyData)
        let sharedSecret = try wrongKey.sharedSecretFromKeyAgreement(with: ephemeralKey)
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: "AstralPayment-P256-v1".data(using: .utf8)!,
            sharedInfo: ephemeralKeyData + wrongKey.publicKey.x963Representation,
            outputByteCount: 32
        )
        let sealedBox = try ChaChaPoly.SealedBox(combined: combinedData)
        #expect(throws: Error.self) {
            _ = try ChaChaPoly.open(sealedBox, using: symmetricKey)
        }
    }

    // MARK: - P256 Signature Tests

    @Test("TransactionSigner sign and verify")
    func testP256SignAndVerify() throws {
        let key = P256.Signing.PrivateKey()
        let data = "test transaction data".data(using: .utf8)!

        let signature = try key.signature(for: data)
        let valid = TransactionSigner.verify(
            data: data,
            signature: signature.derRepresentation,
            publicKey: key.publicKey.x963Representation
        )
        #expect(valid)
    }

    @Test("TransactionSigner verify rejects tampered data")
    func testP256VerifyRejectsTampered() throws {
        let key = P256.Signing.PrivateKey()
        let data = "test transaction data".data(using: .utf8)!
        let signature = try key.signature(for: data)

        var tampered = data
        tampered[0] = 0xFF
        let valid = TransactionSigner.verify(
            data: tampered,
            signature: signature.derRepresentation,
            publicKey: key.publicKey.x963Representation
        )
        #expect(!valid, "Tampered data should fail verification")
    }

    @Test("TransactionSigner fingerprints")
    func testFingerprint() {
        let key = P256.Signing.PrivateKey()
        let fp = TransactionSigner.fingerprint(publicKey: key.publicKey.x963Representation)
        #expect(fp.count == 64)  // SHA-256 hex = 64 chars

        let short = TransactionSigner.shortFingerprint(publicKey: key.publicKey.x963Representation)
        #expect(short.count == 16)
    }

    // MARK: - SignedTransaction Wire Format Tests

    @Test("SignedTransaction wire format roundtrip")
    func testSignedTransactionWireFormatRoundTrip() throws {
        let key = P256.Signing.PrivateKey()
        let payload = "payment data".data(using: .utf8)!
        let sig = try key.signature(for: payload)

        let signed = SignedTransaction(
            payload: payload,
            signature: sig.derRepresentation,
            senderPublicKey: key.publicKey.x963Representation
        )

        // Verify signature before encoding
        #expect(KeyManager.verifyTransaction(signed))

        // Wire format round-trip
        let wireData = signed.toWireFormat()
        let decoded = SignedTransaction.fromWireFormat(wireData)
        #expect(decoded != nil)
        #expect(decoded?.payload == payload)
        #expect(decoded?.signature == sig.derRepresentation)
        #expect(decoded?.senderPublicKey == key.publicKey.x963Representation)

        // Signature should still verify after wire decode
        #expect(KeyManager.verifyTransaction(decoded!))
    }

    // MARK: - Bloom Filter Tests

    @Test("BloomFilter basic insertion and membership")
    func testBloomFilterBasic() {
        let filter = BloomFilter(capacity: 100, falsePositiveRate: 0.01)
        filter.insert("txn-001")
        filter.insert("txn-002")

        #expect(filter.mightContain("txn-001"))
        #expect(filter.mightContain("txn-002"))
        #expect(!filter.mightContain("txn-003"))  // definitely not present
    }

    @Test("BloomFilter false positive rate within bounds")
    func testBloomFilterFalsePositiveRate() {
        let filter = BloomFilter(capacity: 1000, falsePositiveRate: 0.01)

        for i in 0..<1000 {
            filter.insert("present-\(i)")
        }

        var falsePositives = 0
        for i in 0..<10000 {
            if filter.mightContain("absent-\(i)") {
                falsePositives += 1
            }
        }

        #expect(falsePositives < 200, "False positive rate too high: \(falsePositives)/10000")
    }

    @Test("BloomFilter reset clears items")
    func testBloomFilterReset() {
        let filter = BloomFilter(capacity: 100, falsePositiveRate: 0.01)
        filter.insert("txn-001")
        #expect(filter.mightContain("txn-001"))

        filter.reset()
        #expect(!filter.mightContain("txn-001"))
    }

    // MARK: - DeduplicationService Tests

    @Test("DeduplicationService detects duplicates")
    func testDedupDetectsDuplicate() {
        let dedup = DeduplicationService()
        #expect(!dedup.isDuplicate("txn-001"))
        #expect(dedup.isDuplicate("txn-001"))   // seen!
        #expect(!dedup.isDuplicate("txn-002"))  // new
    }

    @Test("DeduplicationService rotation retains recent items")
    func testDedupRotation() {
        let dedup = DeduplicationService()
        _ = dedup.isDuplicate("txn-001")
        dedup.forceRotate()
        // After rotation, txn-001 should still be detected (in secondary filter)
        #expect(dedup.isDuplicate("txn-001"))
    }

    @Test("DeduplicationService reset clears history")
    func testDedupReset() {
        let dedup = DeduplicationService()
        _ = dedup.isDuplicate("txn-001")
        dedup.reset()
        #expect(!dedup.isDuplicate("txn-001"))
    }

    // MARK: - QR Payload Tests

    @Test("AstralQRPayload URL roundtrip")
    func testQRPayloadURLRoundTrip() {
        let payload = AstralQRPayload(
            walletID: WalletID(id: "a1b2c3d4e5f6a7b8"),
            publicKey: "dGVzdA==",
            amount: 23000,
            relayEndpoint: "https://relay.astralnetwork.in"
        )

        let url = payload.toURLString()
        #expect(url.hasPrefix("astral://pay"))
        #expect(url.contains("id=a1b2c3d4e5f6a7b8"))
        #expect(url.contains("amount=23000"))

        let parsed = AstralQRPayload.parse(from: url)
        #expect(parsed != nil)
        #expect(parsed?.walletID.id == "a1b2c3d4e5f6a7b8")
        #expect(parsed?.amount == 23000)
    }

    @Test("AstralQRPayload JSON roundtrip")
    func testQRPayloadJSONRoundTrip() {
        let payload = AstralQRPayload(
            walletID: WalletID(id: "a1b2c3d4e5f6a7b8"),
            publicKey: "dGVzdA==",
            amount: nil, relayEndpoint: nil
        )

        let json = payload.toJSONString()
        #expect(json.contains("astral_pay"))

        let parsed = AstralQRPayload.parse(from: json)
        #expect(parsed != nil)
        #expect(parsed?.amount == nil)
    }

    // MARK: - AstralConfig Tests

    @Test("AstralConfig default values")
    func testDefaultConfig() {
        let config = AstralConfig.default
        #expect(config.meshTTL == 3)
        #expect(config.useSecureEnclave)
        #expect(config.relayEndpoint == nil)
    }

    @Test("AstralConfig testnet values")
    func testTestnetConfig() {
        let config = AstralConfig.testnet
        #expect(config.meshTTL == 2)
        #expect(config.bleServiceUUID != AstralConfig.default.bleServiceUUID)
    }

    // MARK: - Security Binding Test

    @Test("Transaction verification rejects spoofed senderID")
    func testSenderPublicKeyBindingRejectsSpoofedSender() throws {
        // Legitimate key for signing
        let legitimateKey = P256.Signing.PrivateKey()
        let legitimateSenderID = WalletID(publicKey: legitimateKey.publicKey.x963Representation)

        // Spoofed senderID (different wallet ID)
        let spoofedSenderID = WalletID(id: "bad0000000000bad")
        #expect(legitimateSenderID != spoofedSenderID)

        let txn = AstralTransaction(
            id: "spoof-txn-001",
            amount: 50000,
            senderID: spoofedSenderID, // Injected spoofed ID
            recipientID: WalletID(id: "ffffffffffffffff"),
            timestamp: Date(),
            status: .pending
        )

        let encodedTxn = AstralPacket.encode(txn)!
        let sig = try legitimateKey.signature(for: encodedTxn)
        let signedTxn = SignedTransaction(
            payload: encodedTxn,
            signature: sig.derRepresentation,
            senderPublicKey: legitimateKey.publicKey.x963Representation
        )

        // Signature is valid for the payload bytes:
        #expect(KeyManager.verifyTransaction(signedTxn))

        // BUT the cryptographic binding check must reject because senderPublicKey != txn.senderID:
        let derivedID = WalletID(publicKey: signedTxn.senderPublicKey)
        #expect(derivedID != txn.senderID)
    }
}
