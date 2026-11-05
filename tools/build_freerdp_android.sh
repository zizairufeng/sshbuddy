#!/usr/bin/env bash
# 用 NDK 交叉编译 FreeRDP（含 OpenSSL）为 Android 库，产出到
#   FreeRDP-<ver>/client/Android/Studio/freeRDPCore/src/main/jniLibs/<abi>/
#
# 说明：官方 Android 工程要求 AGP 9 / compileSdk 37 / NDK 29，与本机工具链不符，
# 因此绕开它的 gradle，直接用同一份 CMake 脚本 + NDK 手工构建 .so。
#
# 用法：./build_freerdp_android.sh [abi]        默认 arm64-v8a
set -euo pipefail

AB="${AB_BASE:-$HOME/my-server/scan-app-mobile/androidbuild}"
NDK="${ANDROID_NDK_ROOT:-$AB/ndk/android-ndk-r27c}"
SRC_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FREERDP_SRC="$SRC_ROOT/FreeRDP-3.32.2"
JNI_CPP="$FREERDP_SRC/client/Android/Studio/freeRDPCore/src/main/cpp"

ABI="${1:-arm64-v8a}"
BUILD_DIR="$SRC_ROOT/freerdp-build-$ABI"

[ -d "$FREERDP_SRC" ] || { echo "缺少 FreeRDP 源码：$FREERDP_SRC"; exit 1; }
[ -d "$NDK" ] || { echo "缺少 NDK：$NDK"; exit 1; }

# 只编基础能力：OpenSSL(TLS/NLA 必需) + cJSON(AAD) + uriparser(必需)
# 关掉 ffmpeg / openh264 / opus / png / webp / jpeg，先保证链路跑通、体积可控
CMAKE_ARGS=(
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI="$ABI"
  -DANDROID_PLATFORM=android-24
  -DANDROID_STL=c++_shared
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_MAKE_PROGRAM=/usr/bin/make
  -DWITH_OPENSSL=ON
  -DWITH_CJSON=ON
  -DWITH_FFMPEG=OFF
  -DWITH_OPENH264=OFF
  -DWITH_OPUS=OFF
  -DWITH_PNG=OFF
  -DWITH_WEBP=OFF
  -DWITH_JPEG=OFF
  # MediaCodec 的 H.264 解码要 API 28+，本机 minSdk 是 24，只能关掉。
  # 影响：不使用 H.264/AVC444，退回 RemoteFX / NSCodec / 原始位图，普通桌面够用。
  -DWITH_MEDIACODEC=OFF
)

echo "=== configure ($ABI) ==="
cmake -S "$JNI_CPP" -B "$BUILD_DIR" -G "Unix Makefiles" "${CMAKE_ARGS[@]}"

echo "=== build ==="
cmake --build "$BUILD_DIR" --target freerdp-android -j"$(nproc)"

echo "=== 产物 ==="
ls -la "$FREERDP_SRC/client/Android/Studio/freeRDPCore/src/main/jniLibs/$ABI/" 2>/dev/null || true
ls -la "$BUILD_DIR"/*.so 2>/dev/null || true
