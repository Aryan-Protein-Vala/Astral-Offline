//
//  AstralNetworkApp.swift  (SDK-based)
//  Astral Network
//
//  Entry point — uses AstralSDK instead of raw BLEManager + SecurityManager.
//  The SDK handles: keys (SecureEnclave), BLE transport, encryption, dedup.
//

import SwiftUI
import AstralSDK

@main
struct AstralNetworkApp: App {
    @StateObject private var sdk = AstralSDK()

    var body: some Scene {
        WindowGroup {
            AstralWalletView(userName: "Aryan")
                .environmentObject(sdk)
                .task {
                    do {
                        // Register BLE transport
                        let ble = BLETransport()
                        sdk.registerTransport(ble)

                        // Start SDK (initializes Secure Enclave keys + BLE)
                        try sdk.start()
                        print("✅ AstralSDK started — wallet: \(sdk.walletID?.short ?? "none")")
                        print("🔐 Hardware-backed: \(sdk.isHardwareBacked)")
                    } catch {
                        print("❌ SDK start failed: \(error)")
                    }
                }
        }
    }
}
