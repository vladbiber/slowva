#!/bin/sh
# Cross-compile slowva's librespot helpers for Android and drop them into the app's jniLibs
# as lib<name>.so, the only place Android still lets an app exec a binary from.
set -e

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-$SDK_ROOT/ndk/28.2.13676358}}"
API=26
LIBRESPOT_VERSION=0.8.0
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../app/src/main/jniLibs"

TOOL="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$TOOL/clang" ] || { echo "NDK not found at $NDK"; exit 1; }

export PATH="${CARGO_HOME:-$HOME/.cargo}/bin:$PATH"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOL/aarch64-linux-android$API-clang"
export CC_aarch64_linux_android="$TOOL/aarch64-linux-android$API-clang"
export CXX_aarch64_linux_android="$TOOL/aarch64-linux-android$API-clang++"
export AR_aarch64_linux_android="$TOOL/llvm-ar"
export RANLIB_aarch64_linux_android="$TOOL/llvm-ranlib"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOL/x86_64-linux-android$API-clang"
export CC_x86_64_linux_android="$TOOL/x86_64-linux-android$API-clang"
export AR_x86_64_linux_android="$TOOL/llvm-ar"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$TOOL/armv7a-linux-androideabi$API-clang"
export CC_armv7_linux_androideabi="$TOOL/armv7a-linux-androideabi$API-clang"
export AR_armv7_linux_androideabi="$TOOL/llvm-ar"

build() {
    target=$1; abi=$2
    compiler_target=$target
    [ "$target" = "armv7-linux-androideabi" ] && compiler_target=armv7a-linux-androideabi
    echo "=== $target -> $abi"
    mkdir -p "$OUT/$abi"
    # The token helper reuses the authenticated local librespot session so the
    # Web API can recover when a normal OAuth access token expires.
    ( cd "$HERE/token-helper" && cargo build --release --locked --bins --target "$target" \
        --target-dir "$HERE/build/$abi/token-target" )
    cp "$HERE/build/$abi/token-target/$target/release/slowva-token" \
        "$OUT/$abi/libslowvatoken.so"
    "$TOOL/llvm-strip" "$OUT/$abi/libslowvatoken.so" 2>/dev/null || true
    # Plays and reverses single tracks without a Connect device.
    cp "$HERE/build/$abi/token-target/$target/release/slowva-decode" \
        "$OUT/$abi/libslowvadecode.so"
    "$TOOL/llvm-strip" "$OUT/$abi/libslowvadecode.so" 2>/dev/null || true

    # Prefer IPv4 for consumer routers that advertise unusable IPv6 routes.
    "$TOOL/${compiler_target}${API}-clang" -shared -fPIC -O2 "$HERE/ipv4.c" -ldl \
        -o "$OUT/$abi/libslowvaipv4.so"
    "$TOOL/llvm-strip" "$OUT/$abi/libslowvaipv4.so" 2>/dev/null || true
    ls -la "$OUT/$abi/"
}

build aarch64-linux-android arm64-v8a
# x86_64 is only useful for the emulator; arm64 is what phones run
[ "$1" = "--all" ] && build x86_64-linux-android x86_64
[ "$1" = "--all" ] && build armv7-linux-androideabi armeabi-v7a
echo "done"
