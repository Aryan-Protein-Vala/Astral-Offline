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
            path: "Tests/AstralSDKTests",
            swiftSettings: [
                .unsafeFlags([
                    "-load-resolved-plugin",
                    "/Library/Developer/CommandLineTools/usr/lib/swift/host/plugins/testing/libTestingMacros.dylib##TestingMacros"
                ])
            ],
            linkerSettings: [
                .unsafeFlags([
                    "-Xlinker", "-rpath", "-Xlinker", "/Library/Developer/CommandLineTools/Library/Developer/Frameworks",
                    "-Xlinker", "-rpath", "-Xlinker", "/Library/Developer/CommandLineTools/Library/Developer/usr/lib"
                ])
            ]
        )
    ]
)
