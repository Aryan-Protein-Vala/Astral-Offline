# Contributing to Astral Offline

Thanks for wanting to help build the offline mesh. Here's everything you need to know.

---

## Using the SDK in Your App

### iOS / macOS (Swift Package Manager)

In Xcode: **File → Add Package Dependencies** → paste:

```
https://github.com/Aryan-Protein-Vala/Astral-Offline
```

Or add to your `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/Aryan-Protein-Vala/Astral-Offline", from: "0.1.0")
],
targets: [
    .target(name: "YourApp", dependencies: [
        .product(name: "AstralOfflineSDK", package: "Astral-Offline")
    ])
]
```

Then in Swift:
```swift
import AstralOfflineSDK

let sdk = AstralSDK()
try await sdk.start()
sdk.startListening()

sdk.onPayloadReceived = { data, sender in
    // data = decrypted, signature-verified bytes
    // works for payments, chat, AI prompts — anything
}

// Send
let qr = sdk.parseQR(scannedString)!
try await sdk.sendPayload(myPayload, to: qr)
```

### Android (local Maven)

Until published to Maven Central, add the module to your project:

```kotlin
// settings.gradle.kts
include(":astralsdk")
project(":astralsdk").projectDir = file("path/to/Astral-Offline/android/astralsdk")

// app/build.gradle.kts
dependencies {
    implementation(project(":astralsdk"))
}
```

Then in Kotlin:
```kotlin
val sdk = AstralSDK(context)
sdk.start()
sdk.startListening()

sdk.onPayloadReceived = { data, sender ->
    // fully decrypted + signature-verified
}

sdk.sendPayload(myBytes, recipientQR) { result -> }
```

---

## Building the Rust Core

### Android `.so` libraries
```bash
export ANDROID_NDK_HOME=/path/to/your/ndk
cd astral-core/scripts
chmod +x build-android.sh
./build-android.sh
```

Output: `android/astralsdk/src/main/jniLibs/`

### iOS / macOS `.xcframework`
```bash
cd astral-core/scripts
chmod +x build-ios.sh
./build-ios.sh
```

Output: `ios_app/AstralOfflineSDK/Sources/AstralOfflineSDK/Frameworks/AstralCore.xcframework`

---

## Project Structure

```
astral-core/           # Rust — crypto, routing, BLAKE3 dedup
  src/
    lib.rs             # AstralPaymentEngine — SDK entry point
    crypto/            # ECDH, ChaCha20-Poly1305, BLAKE3
    mesh/              # Spray-and-Wait router, packet format
    state/             # HardwareVault — nonce + balance
  scripts/
    build-android.sh   # cargo-ndk → .so + Kotlin UniFFI bindings
    build-ios.sh       # lipo + xcodebuild → .xcframework + Swift bindings

android/
  astralsdk/           # Android SDK library
    identity/          # KeyManager (StrongBox/TEE), WalletID
    transport/         # BLETransport, WiFiTransport
    wallet/            # AstralTransaction, AstralQRPayload
    storage/           # Room DB — mesh packet + transaction persistence
    core/              # AstralSDK.kt — public API
  app/                 # Demo payment app (Jetpack Compose)

ios_app/
  AstralOfflineSDK/    # Swift Package
    Sources/
      Core/            # AstralSDK, KeyManager, AstralPersistence
      Transport/       # BLETransport, WiFiTransport
      Wallet/          # AstralTypes
      HomeView.swift   # Demo payment UI
      QRScannerView.swift  # AVFoundation QR scanner
```

---

## Security Architecture

```
Android                          iOS / macOS
───────────────────────────────  ──────────────────────────────────
StrongBox (Titan M)              Secure Enclave (T2 / Apple Silicon)
  └─ ECDSA-P256 signing key        └─ ECDSA-P256 signing key
  └─ Private key: NEVER in JVM     └─ Private key: NEVER in Swift

Software P-256                   CryptoKit P-256.KeyAgreement
  └─ ECDH key agreement            └─ ECDH key agreement
  └─ Keychain: encrypted at rest   └─ Keychain: kSecAttrAccessible...

  ↓ HKDF-SHA256(shared_secret)     ↓ HKDF-SHA256(shared_secret)
  ↓                                ↓
  ChaCha20-Poly1305 (AES-GCM fallback)   ChaChaPoly (CryptoKit)
  ↓                                ↓
  BLAKE3 dedup header              SHA-256 dedup header
```

Every packet is:
1. **Signed** by the sender's hardware key
2. **Encrypted** with a per-session ECDH shared key
3. **Authenticated** — receiver verifies signature before decrypting
4. **Deduplicated** — replay attacks rejected by BLAKE3 hash

---

## Issues and PRs

Open an issue before sending a large PR. For security vulnerabilities, email directly — don't open a public issue.

## License

MIT — see [LICENSE](LICENSE).
