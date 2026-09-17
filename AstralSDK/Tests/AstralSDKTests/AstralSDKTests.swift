import XCTest
import CryptoKit
@testable import AstralSDK

final class AstralSDKTests: XCTestCase {

    // MARK: - WalletID Tests

    func testWalletIDFromPublicKey() {
        let key = P256.Signing.PrivateKey()
        let walletID = WalletID(publicKey: key.publicKey.x963Representation)
        XCTAssertEqual(walletID.id.count, 16)
        XCTAssertTrue(walletID.isValid)
    }

    func testWalletIDDeterministic() {
        let key = P256.Signing.PrivateKey()
        let pubData = key.publicKey.x963Representation
        let a = WalletID(publicKey: pubData)
        let b = WalletID(publicKey: pubData)
        XCTAssertEqual(a, b, "Same pubkey should produce same WalletID")
    }

    func testWalletIDShort() {
        let id = WalletID(id: "a1b2c3d4e5f6a7b8")
        XCTAssertEqual(id.short, "a1b2c3d4")
    }

    func testWalletIDRoutingDataRoundTrip() {
        let id = WalletID(id: "a1b2c3d4e5f6a7b8")
        let data = id.routingData
        XCTAssertNotNil(data)
        XCTAssertEqual(data?.count, 8)
        let restored = WalletID(data: data!)
        XCTAssertEqual(restored?.id, id.id)
    }

    func testWalletIDCaseInsensitive() {
        let a = WalletID(id: "a1b2c3d4e5f6a7b8")
        let b = WalletID(id: "A1B2C3D4E5F6A7B8")
        XCTAssertEqual(a, b)
    }

    func testWalletIDValidation() {
        XCTAssertTrue(WalletID(id: "a1b2c3d4e5f6a7b8").isValid)
        XCTAssertFalse(WalletID(id: "too_short").isValid)
        XCTAssertFalse(WalletID(id: "zzzzzzzzzzzzzzzz").isValid)
    }

    // MARK: - AstralPacket Tests

    func testPacketEncodeAndDecode() {
        let sender = WalletID(id: "1111111111111111")
        let recipient = WalletID(id: "2222222222222222")
        let txn = AstralTransaction(
            id: "test-txn-001", amount: 23000,
            senderID: sender, recipientID: recipient,
            timestamp: Date(), status: .pending
        )

        let encoded = AstralPacket.encode(txn)
        XCTAssertNotNil(encoded)

        let decoded = AstralPacket.decode(encoded!)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.id, "test-txn-001")
        XCTAssertEqual(decoded?.amount, 23000)
        XCTAssertEqual(decoded?.senderID.id, sender.id)
        XCTAssertEqual(decoded?.recipientID.id, recipient.id)
    }

    func testPacketDecodeRejectsGarbage() {
        XCTAssertNil(AstralPacket.decode(Data(repeating: 0xFF, count: 10)))
    }

    // MARK: - PacketFragmenter Tests

    func testSinglePacketNoFragmentation() {
        let fragmenter = PacketFragmenter(mtu: 512)
        let data = Data(repeating: 0xAB, count: 100)
        let fragments = fragmenter.fragment(data)
        XCTAssertEqual(fragments.count, 1)
        XCTAssertEqual(fragments[0][0], 0x00)  // single type
    }

    func testFragmentAndReassemble() {
        let fragmenter = PacketFragmenter(mtu: 50)
        let data = Data(repeating: 0xCD, count: 200)
        let fragments = fragmenter.fragment(data)
        XCTAssertTrue(fragments.count > 1)
        XCTAssertEqual(fragments[0][0], 0x01)  // start
        XCTAssertEqual(fragments.last![0], 0x03)  // end

        var reassembler = PacketFragmenter(mtu: 50)
        var result: Data?
        for fragment in fragments {
            result = reassembler.reassemble(fragment)
        }
        XCTAssertNotNil(result)
        XCTAssertEqual(result, data)
    }

    // MARK: - NoiseSession Tests (P256 ECDH + ChaCha20)

    func testNoiseEncryptDecryptRoundTrip() throws {
        // Simulate merchant P256 key pair
        let merchantKey = P256.KeyAgreement.PrivateKey()
        let merchantPubData = merchantKey.publicKey.x963Representation

        // Customer encrypts payment
        let plaintext = "Pay ₹500 to merchant".data(using: .utf8)!
        let wireData = try NoiseSession.encrypt(plaintext: plaintext, merchantPublicKey: merchantPubData)

        // Verify wire format: [0x01][65B key][12B nonce][ciphertext+16B tag]
        XCTAssertEqual(wireData[0], 0x01, "First byte must be version 0x01")
        XCTAssertTrue(wireData.count > 66 + 12 + 16, "Wire data too small")
        XCTAssertEqual(wireData[1], 0x04, "x963 key must start with 0x04")

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
        XCTAssertEqual(decrypted, plaintext)
    }

    func testNoiseWireFormatRoundTrip() throws {
        let merchantKey = P256.KeyAgreement.PrivateKey()
        let plaintext = Data(repeating: 0xAA, count: 64)

        let wireData = try NoiseSession.encrypt(
            plaintext: plaintext,
            merchantPublicKey: merchantKey.publicKey.x963Representation
        )

        // Version byte check
        XCTAssertEqual(wireData[0], 0x01)
        // Key at offset 1 (65 bytes, starts with 0x04)
        XCTAssertEqual(wireData[1], 0x04)
        // Wire data should be: 1 + 65 + 12 + plaintext.count + 16 = 158 bytes
        XCTAssertEqual(wireData.count, 1 + 65 + 12 + 64 + 16)
    }

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
        XCTAssertThrowsError(try ChaChaPoly.open(sealedBox, using: symmetricKey))
    }

    // MARK: - P256 Signature Tests

    func testP256SignAndVerify() throws {
        let key = P256.Signing.PrivateKey()
        let data = "test transaction data".data(using: .utf8)!

        let signature = try key.signature(for: data)
        let valid = TransactionSigner.verify(
            data: data,
            signature: signature.derRepresentation,
            publicKey: key.publicKey.x963Representation
        )
        XCTAssertTrue(valid)
    }

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
        XCTAssertFalse(valid, "Tampered data should fail verification")
    }

    func testFingerprint() {
        let key = P256.Signing.PrivateKey()
        let fp = TransactionSigner.fingerprint(publicKey: key.publicKey.x963Representation)
        XCTAssertEqual(fp.count, 64)  // SHA-256 hex = 64 chars

        let short = TransactionSigner.shortFingerprint(publicKey: key.publicKey.x963Representation)
        XCTAssertEqual(short.count, 16)
    }

    // MARK: - SignedTransaction Wire Format Tests

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
        XCTAssertTrue(KeyManager.verifyTransaction(signed))

        // Wire format round-trip
        let wireData = signed.toWireFormat()
        let decoded = SignedTransaction.fromWireFormat(wireData)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.payload, payload)
        XCTAssertEqual(decoded?.signature, sig.derRepresentation)
        XCTAssertEqual(decoded?.senderPublicKey, key.publicKey.x963Representation)

        // Signature should still verify after wire decode
        XCTAssertTrue(KeyManager.verifyTransaction(decoded!))
    }

    // MARK: - Bloom Filter Tests

    func testBloomFilterBasic() {
        let filter = BloomFilter(capacity: 100, falsePositiveRate: 0.01)
        filter.insert("txn-001")
        filter.insert("txn-002")

        XCTAssertTrue(filter.mightContain("txn-001"))
        XCTAssertTrue(filter.mightContain("txn-002"))
        XCTAssertFalse(filter.mightContain("txn-003"))  // definitely not present
    }

    func testBloomFilterFalsePositiveRate() {
        // Insert 1000 items, test 10000 non-present items
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

        // Should be well under 1% (100 out of 10000)
        // With 0.01 FP rate, expect ~100 but allow margin
        XCTAssertLessThan(falsePositives, 200, "False positive rate too high: \(falsePositives)/10000")
    }

    func testBloomFilterReset() {
        let filter = BloomFilter(capacity: 100, falsePositiveRate: 0.01)
        filter.insert("txn-001")
        XCTAssertTrue(filter.mightContain("txn-001"))

        filter.reset()
        XCTAssertFalse(filter.mightContain("txn-001"))
    }

    // MARK: - DeduplicationService Tests

    func testDedupDetectsDuplicate() {
        let dedup = DeduplicationService()
        XCTAssertFalse(dedup.isDuplicate("txn-001"))
        XCTAssertTrue(dedup.isDuplicate("txn-001"))   // seen!
        XCTAssertFalse(dedup.isDuplicate("txn-002"))  // new
    }

    func testDedupRotation() {
        let dedup = DeduplicationService()
        _ = dedup.isDuplicate("txn-001")
        dedup.forceRotate()
        // After rotation, txn-001 should still be detected (in secondary filter)
        XCTAssertTrue(dedup.isDuplicate("txn-001"))
    }

    func testDedupReset() {
        let dedup = DeduplicationService()
        _ = dedup.isDuplicate("txn-001")
        dedup.reset()
        XCTAssertFalse(dedup.isDuplicate("txn-001"))
    }

    // MARK: - QR Payload Tests

    func testQRPayloadURLRoundTrip() {
        let payload = AstralQRPayload(
            walletID: WalletID(id: "a1b2c3d4e5f6a7b8"),
            publicKey: "dGVzdA==",
            amount: 23000,
            relayEndpoint: "https://relay.astralnetwork.in"
        )

        let url = payload.toURLString()
        XCTAssertTrue(url.hasPrefix("astral://pay"))
        XCTAssertTrue(url.contains("id=a1b2c3d4e5f6a7b8"))
        XCTAssertTrue(url.contains("amount=23000"))

        let parsed = AstralQRPayload.parse(from: url)
        XCTAssertNotNil(parsed)
        XCTAssertEqual(parsed?.walletID.id, "a1b2c3d4e5f6a7b8")
        XCTAssertEqual(parsed?.amount, 23000)
    }

    func testQRPayloadJSONRoundTrip() {
        let payload = AstralQRPayload(
            walletID: WalletID(id: "a1b2c3d4e5f6a7b8"),
            publicKey: "dGVzdA==",
            amount: nil, relayEndpoint: nil
        )

        let json = payload.toJSONString()
        XCTAssertTrue(json.contains("astral_pay"))

        let parsed = AstralQRPayload.parse(from: json)
        XCTAssertNotNil(parsed)
        XCTAssertNil(parsed?.amount)
    }

    // MARK: - AstralConfig Tests

    func testDefaultConfig() {
        let config = AstralConfig.default
        XCTAssertEqual(config.meshTTL, 3)
        XCTAssertTrue(config.useSecureEnclave)
        XCTAssertNil(config.relayEndpoint)
    }

    func testTestnetConfig() {
        let config = AstralConfig.testnet
        XCTAssertEqual(config.meshTTL, 2)
        XCTAssertNotEqual(config.bleServiceUUID, AstralConfig.default.bleServiceUUID)
    }
}
