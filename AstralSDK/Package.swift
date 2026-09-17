// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AstralSDK",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(name: "AstralSDK", targets: ["AstralSDK"])
    ],
    targets: [
        .target(
            name: "AstralSDK",
            path: "Sources/AstralSDK"
        ),
        .testTarget(
            name: "AstralSDKTests",
            dependencies: ["AstralSDK"],
            path: "Tests/AstralSDKTests"
        )
    ]
)
