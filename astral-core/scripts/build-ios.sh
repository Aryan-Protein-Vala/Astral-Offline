#!/usr/bin/env bash
set -e

OUT_DIR="../ios/AstralCoreFramework"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

echo "==> Compiling iOS Targets..."
cargo build --release --target aarch64-apple-ios
cargo build --release --target aarch64-apple-ios-sim
cargo build --release --target x86_64-apple-ios

echo "==> Generating Universal Binary for Simulators (Lipo)..."
mkdir -p target/ios-sim-universal
lipo -create \
    target/aarch64-apple-ios-sim/release/libastral_core.a \
    target/x86_64-apple-ios/release/libastral_core.a \
    -output target/ios-sim-universal/libastral_core.a

echo "==> Generating Swift Bindings via uniffi-bindgen..."
cargo run --features=uniffi/cli --bin uniffi-bindgen generate \
    target/aarch64-apple-ios/release/libastral_core.dylib \
    --language swift \
    --out-dir "$OUT_DIR/Swift"

# Locate the generated module map and rename C-headers correctly
mv "$OUT_DIR/Swift/astral_coreFFI.modulemap" "$OUT_DIR/Swift/module.modulemap"

echo "==> Constructing XCFramework..."
xcodebuild -create-xcframework \
    -library target/aarch64-apple-ios/release/libastral_core.a \
    -headers "$OUT_DIR/Swift" \
    -library target/ios-sim-universal/libastral_core.a \
    -headers "$OUT_DIR/Swift" \
    -output "$OUT_DIR/AstralCore.xcframework"

echo "==> iOS XCFramework Complete at $OUT_DIR/AstralCore.xcframework"
