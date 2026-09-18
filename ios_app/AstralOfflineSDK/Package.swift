// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AstralOfflineSDK",
    platforms: [
        .iOS(.v17),
        .macOS(.v14)
    ],
    products: [
        // Main SDK — what apps import
        .library(name: "AstralOfflineSDK", targets: ["AstralOfflineSDK"]),
    ],
    targets: [
        // Swift SDK target
        .target(
            name: "AstralOfflineSDK",
            path: "Sources/AstralOfflineSDK",
            resources: [],
            swiftSettings: [
                .enableUpcomingFeature("StrictConcurrency")
            ],
            linkerSettings: [
                // CoreData — for mesh packet persistence
                .linkedFramework("CoreData"),
                // CoreBluetooth — BLE mesh transport
                .linkedFramework("CoreBluetooth"),
                // Network.framework — WiFi Bonjour transport
                .linkedFramework("Network"),
                // AVFoundation — QR scanner camera
                .linkedFramework("AVFoundation"),
            ]
        ),
        .testTarget(
            name: "AstralOfflineSDKTests",
            dependencies: ["AstralOfflineSDK"],
            path: "Tests/AstralOfflineSDKTests"
        ),
    ]
)

