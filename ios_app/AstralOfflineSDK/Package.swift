// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AstralOfflineSDK",
    platforms: [
        .iOS(.v17),
        .macOS(.v14)  // macOS 14 Sonoma — full Bonjour + Network.framework support
    ],
    products: [
        .library(
            name: "AstralOfflineSDK",
            targets: ["AstralOfflineSDK"]
        ),
    ],
    targets: [
        .target(
            name: "AstralOfflineSDK",
            path: "Sources/AstralOfflineSDK"
        ),
        .testTarget(
            name: "AstralOfflineSDKTests",
            dependencies: ["AstralOfflineSDK"]
        ),
    ]
)
