#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds ofbridge for Android (arm64-v8a, armeabi-v7a) with the NDK and, with
# --install, copies the binaries into the app as libofbridge.so.
#
#   ./build-android.sh [--install]
#
# Environment: ANDROID_NDK (default: newest ndk/ under the Android SDK), GO
# (default: go on PATH). -checklinkname=0 is required by the OpenFlux core's
# dependencies (go:linkname into the runtime) since Go 1.23.
set -euo pipefail
cd "$(dirname "$0")"

GO=${GO:-go}
API=26
if [[ -z "${ANDROID_NDK:-}" ]]; then
  SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}
  ANDROID_NDK=$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)
fi
[[ -d "$ANDROID_NDK" ]] || { echo "NDK not found (set ANDROID_NDK)" >&2; exit 1; }

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXT=.cmd ;;
  Darwin)               HOST=darwin-x86_64;  EXT= ;;
  *)                    HOST=linux-x86_64;   EXT= ;;
esac
BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$HOST/bin"

LDFLAGS="-s -w -checklinkname=0"

build() { # goarch out cc [goarm]
  echo "building $2"
  CGO_ENABLED=1 GOOS=android GOARCH=$1 GOARM=${4:-} CC="$BIN/$3$EXT" \
    "$GO" build -trimpath -ldflags="$LDFLAGS" -o "$2" .
  ls -l "$2"
}

build arm64 ofbridge-android-arm64 "aarch64-linux-android$API-clang"
build arm   ofbridge-android-arm   "armv7a-linux-androideabi$API-clang" 7

if [[ "${1:-}" == "--install" ]]; then
  JNI=../app/src/main/jniLibs
  cp ofbridge-android-arm64 "$JNI/arm64-v8a/libofbridge.so"
  cp ofbridge-android-arm   "$JNI/armeabi-v7a/libofbridge.so"
  echo "installed into $JNI"
fi
