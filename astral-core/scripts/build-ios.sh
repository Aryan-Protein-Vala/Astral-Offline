#!/usr/bin/env bash
# =============================================================================
# build-ios.sh — Compile astral-core Rust library for iOS + macOS
#
# Prerequisites:
#   1. Xcode installed (for lipo, xcodebuild, xcrun)
#   2. iOS targets installed (script does this automatically)
#   3. cargo-lipo: installed automatically if missing
#
# Output:
#   ios_app/AstralOfflineSDK/Sources/AstralOfflineSDK/Frameworks/AstralCore.xcframework
#   ios_app/AstralOfflineSDK/Sources/AstralOfflineSDK/astral_coreFFI.swift  (UniFFI bindings)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$SCRIPT_DIR/.."
XCFW_OUT="$SCRIPT_DIR/../../ios_app/AstralOfflineSDK/Sources/AstralOfflineSDK/Frameworks"
BINDINGS_OUT="$SCRIPT_DIR/../../ios_app/AstralOfflineSDK/Sources/AstralOfflineSDK"

echo "🦀 Building astral-core for iOS + macOS..."

# ── Add Apple targets if missing ──────────────────────────────────────────────
TARGETS=(
    "aarch64-apple-ios"           # iOS device — ARM64
    "aarch64-apple-ios-sim"       # iOS Simulator on Apple Silicon
    "x86_64-apple-ios"            # iOS Simulator on Intel Mac
    "aarch64-apple-darwin"        # macOS Apple Silicon
    "x86_64-apple-darwin"         # macOS Intel
)

for target in "${TARGETS[@]}"; do
    rustup target add "$target" 2>/dev/null || true
done

cd "$CORE_DIR"

# ── Compile for all targets ───────────────────────────────────────────────────
echo "🔨 Compiling for iOS device (ARM64)..."
cargo build --release --target aarch64-apple-ios

echo "🔨 Compiling for iOS Simulator (ARM64 — Apple Silicon Mac)..."
cargo build --release --target aarch64-apple-ios-sim

echo "🔨 Compiling for iOS Simulator (x86_64 — Intel Mac)..."
cargo build --release --target x86_64-apple-ios

echo "🔨 Compiling for macOS (Apple Silicon)..."
cargo build --release --target aarch64-apple-darwin

echo "🔨 Compiling for macOS (Intel)..."
cargo build --release --target x86_64-apple-darwin

# ── Create fat binary for Simulator (lipo ARM64 + x86_64) ────────────────────
echo "🍕 Creating universal Simulator binary..."
mkdir -p target/universal-ios-sim/release
lipo -create \
    target/aarch64-apple-ios-sim/release/libastral_core.a \
    target/x86_64-apple-ios/release/libastral_core.a \
    -output target/universal-ios-sim/release/libastral_core.a

# ── Create universal macOS binary ─────────────────────────────────────────────
echo "🍕 Creating universal macOS binary..."
mkdir -p target/universal-macos/release
lipo -create \
    target/aarch64-apple-darwin/release/libastral_core.a \
    target/x86_64-apple-darwin/release/libastral_core.a \
    -output target/universal-macos/release/libastral_core.a

# ── Generate Swift UniFFI bindings ────────────────────────────────────────────
echo "📋 Generating Swift UniFFI bindings..."
mkdir -p "$BINDINGS_OUT"

cargo run --bin uniffi-bindgen generate \
    src/lib.rs \
    --language swift \
    --out-dir "$BINDINGS_OUT"

# ── Bundle as XCFramework ─────────────────────────────────────────────────────
echo "📦 Creating AstralCore.xcframework..."
mkdir -p "$XCFW_OUT"
rm -rf "$XCFW_OUT/AstralCore.xcframework"

# Generate modulemap for the C header
HEADER_DIR="$CORE_DIR/target/astral_core_headers"
mkdir -p "$HEADER_DIR"
cp "$BINDINGS_OUT/astral_coreFFI.h" "$HEADER_DIR/"
cat > "$HEADER_DIR/module.modulemap" << EOF
framework module AstralCoreFFI {
    umbrella header "astral_coreFFI.h"
    export *
    module * { export * }
}
EOF

xcodebuild -create-xcframework \
    -library "target/aarch64-apple-ios/release/libastral_core.a" \
    -headers "$HEADER_DIR" \
    -library "target/universal-ios-sim/release/libastral_core.a" \
    -headers "$HEADER_DIR" \
    -library "target/universal-macos/release/libastral_core.a" \
    -headers "$HEADER_DIR" \
    -output "$XCFW_OUT/AstralCore.xcframework"

echo ""
echo "🎉 iOS/macOS build complete!"
echo "   XCFramework:      $XCFW_OUT/AstralCore.xcframework"
echo "   Swift bindings:   $BINDINGS_OUT/astral_core.swift"
echo ""
echo "Next steps:"
echo "  1. Open ios_app/AstralOfflineSDK in Xcode"
echo "  2. Add AstralCore.xcframework to the target's Frameworks"
echo "  3. Run on device — Secure Enclave activated automatically"
