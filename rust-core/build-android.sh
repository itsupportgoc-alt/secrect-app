#!/usr/bin/env bash
# Cross-compiles the Rust core for Android and copies the .so files into the
# Android app. Requires the Android NDK and `cargo install cargo-ndk`.
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "cargo-ndk is missing. Install it with: cargo install cargo-ndk" >&2
  exit 1
fi

rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o ../android/app/src/main/jniLibs \
  build --release

echo "Rust core built into android/app/src/main/jniLibs"
