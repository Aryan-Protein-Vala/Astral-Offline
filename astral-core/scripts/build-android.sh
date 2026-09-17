#!/usr/bin/env bash
set -e

# Export your Android SDK and NDK paths
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/26.1.10909125}"
TARGET_DIR="target"
JNI_LIBS_DIR="../android/astralsdk/src/main/jniLibs"
KOTLIN_OUT_DIR="../android/astralsdk/src/main/java/com/astralnetwork/core"

echo "==> Building Native Binaries for Android via cargo-ndk..."

cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o "$JNI_LIBS_DIR" build --release

echo "==> Generating Kotlin Bindings via uniffi-bindgen..."

mkdir -p "$KOTLIN_OUT_DIR"
cargo run --features=uniffi/cli --bin uniffi-bindgen generate \
    --library target/aarch64-linux-android/release/libastral_core.so \
    --language kotlin \
    --out-dir "$KOTLIN_OUT_DIR"

echo "==> Android Build Complete."
