#!/usr/bin/env bash
# =============================================================================
# build-android.sh — Compile astral-core Rust library for Android
#
# Prerequisites:
#   1. Android NDK installed (API 26+)
#      export ANDROID_NDK_HOME=/path/to/ndk
#   2. Cargo targets: run this script once and it installs them automatically
#   3. cargo-ndk: installed automatically if missing
#
# Output: android/astralsdk/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libastral_core.so
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$SCRIPT_DIR/.."
ANDROID_JNI_DIR="$SCRIPT_DIR/../../android/astralsdk/src/main/jniLibs"
BINDINGS_OUT="$SCRIPT_DIR/../../android/astralsdk/src/main/java/uniffi"

echo "🦀 Building astral-core for Android..."

# ── Install cargo-ndk if missing ──────────────────────────────────────────────
if ! command -v cargo-ndk &> /dev/null; then
    echo "📦 Installing cargo-ndk..."
    cargo install cargo-ndk
fi

# ── Add Android targets if missing ────────────────────────────────────────────
TARGETS=(
    "aarch64-linux-android"     # ARM64 — modern phones (Pixel, Samsung, OnePlus)
    "armv7-linux-androideabi"   # ARMv7 — older phones
    "x86_64-linux-android"      # x86_64 — emulator
)

for target in "${TARGETS[@]}"; do
    rustup target add "$target" 2>/dev/null || true
done

# ── Compile for all targets ───────────────────────────────────────────────────
echo "🔨 Compiling for all Android architectures..."
cd "$CORE_DIR"

cargo ndk \
    --target aarch64-linux-android \
    --target armv7-linux-androideabi \
    --target x86_64-linux-android \
    --android-platform 26 \
    -o "$ANDROID_JNI_DIR" \
    build --release

echo "✅ Native .so files written to $ANDROID_JNI_DIR"

# ── Generate Kotlin UniFFI bindings ───────────────────────────────────────────
echo "📋 Generating Kotlin UniFFI bindings..."
mkdir -p "$BINDINGS_OUT"

cargo run --bin uniffi-bindgen generate \
    --library "$CORE_DIR/target/aarch64-linux-android/release/libastral_core.so" \
    --language kotlin \
    --out-dir "$BINDINGS_OUT"

echo "✅ Kotlin bindings written to $BINDINGS_OUT"
echo ""
echo "🎉 Android build complete!"
echo "   .so files:       $ANDROID_JNI_DIR"
echo "   Kotlin bindings: $BINDINGS_OUT"
echo ""
echo "Next: Open android/ in Android Studio and run the app."
