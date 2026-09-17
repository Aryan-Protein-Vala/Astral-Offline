<div align="center">
  <img src="logo.jpg" alt="Astral Offline Logo" width="200" />

  # Astral Offline

  **A cross-platform SDK for building delay-tolerant, offline-first mesh applications.**
</div>

## What is Astral?

Astral is an open-source SDK that lets you build apps that communicate **without the internet, without cell service, and without SIM cards**. 

Instead of relying on centralized servers, Astral turns every mobile phone into a roaming router. It uses a concept called **Delay-Tolerant Networking (DTN)** and **Opportunistic Routing**. 

Imagine you want to send a secure chat message from Delhi to New York, but neither of you has internet:
1. You hit "Send" in Delhi. Your phone encrypts the message.
2. The message hops to a stranger's phone as they walk past you using Bluetooth.
3. That stranger boards a flight to New York.
4. When they walk past your friend in New York, the message silently hops to their phone and decrypts.

With Astral, you can build offline messaging apps, disaster relief coordination tools, secure peer-to-peer data transfers, or disconnected AI tools.

## Features

- 🔋 **Battery-Friendly Mesh:** Designed specifically for mobile phones. The heavy lifting is done by a lightweight Rust core, while the native OS handles the Bluetooth/Wi-Fi scanning.
- 🔒 **End-to-End Encryption:** Your data is secured using P-256 ECDH and ChaCha20-Poly1305. Intermediate nodes (the strangers carrying your data) cannot read it.
- 📡 **Cross-Platform:** Works seamlessly between iOS and Android.

## Tech Stack

Astral is built for maximum performance and memory safety:
- **Core Engine:** Pure Rust (`astral-core`). Handles all the cryptography, MTU slicing, and "Spray-and-Wait" routing algorithms.
- **FFI Bindings:** Mozilla UniFFI generates the bridges so the Rust engine can talk to the native mobile apps seamlessly.
- **iOS Wrapper:** Swift (compiled as an XCFramework).
- **Android Wrapper:** Kotlin (compiled via JNI).

## Open Source

Astral is entirely open source. Our goal is to empower developers to build resilient communication networks that survive internet outages, natural disasters, and censorship. Feel free to use this SDK to build your own offline apps!

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
