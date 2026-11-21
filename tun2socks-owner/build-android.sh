#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds tun2socks-owner for Android (arm64-v8a, armeabi-v7a) with the NDK into out/.
# Does NOT touch app/src/main/jniLibs — copy out/<abi>/libnetstack.so there by hand
# (or run with --install) once the app side (OwnerUidServer) is wired up.
#
#   ./build-android.sh [--test] [--install]
#
# Environment: ANDROID_NDK (default: newest ndk/ under the Android SDK), GO (default: go on
# PATH, then "C:/Program Files/Go/bin/go").
set -euo pipefail
cd "$(dirname "$0")"

GO=${GO:-$(command -v go || echo "/c/Program Files/Go/bin/go")}
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

for a in "$@"; do
  [[ $a == --test ]] && "$GO" test -count=1 ./...
done

build() { # goarch abi cc [goarm]
  mkdir -p "out/$2"
  echo "building out/$2/libnetstack.so"
  CGO_ENABLED=1 GOOS=android GOARCH=$1 GOARM=${4:-} CC="$BIN/$3$EXT" \
    "$GO" build -trimpath -ldflags="-s -w" -o "out/$2/libnetstack.so" .
  ls -l "out/$2/libnetstack.so"
}

build arm64 arm64-v8a   "aarch64-linux-android$API-clang"
build arm   armeabi-v7a "armv7a-linux-androideabi$API-clang" 7

for a in "$@"; do
  if [[ $a == --install ]]; then
    for abi in arm64-v8a armeabi-v7a; do
      cp "out/$abi/libnetstack.so" "../app/src/main/jniLibs/$abi/libnetstack.so"
    done
    echo "installed into app/src/main/jniLibs"
  fi
done
