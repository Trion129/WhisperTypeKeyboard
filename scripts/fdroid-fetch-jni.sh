#!/usr/bin/env bash
# Fetch FOSS prebuilt Android JNI libraries for F-Droid / CI builds.
# Source: k2-fsa/sherpa-onnx (Apache-2.0) + ONNX Runtime (MIT)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${SHERPA_ONNX_VERSION:-v1.13.4}"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/${VERSION}/sherpa-onnx-${VERSION}-android.tar.bz2"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading $URL"
wget -q -O "$TMP/sherpa-android.tar.bz2" "$URL"
mkdir -p "$TMP/extract" "$ROOT/app/src/main/jniLibs"
tar -xjf "$TMP/sherpa-android.tar.bz2" -C "$TMP/extract"

if [[ -d "$TMP/extract/jniLibs" ]]; then
  cp -a "$TMP/extract/jniLibs/." "$ROOT/app/src/main/jniLibs/"
else
  LIBROOT="$(find "$TMP/extract" -type d -name jniLibs | head -n1)"
  test -n "$LIBROOT"
  cp -a "$LIBROOT/." "$ROOT/app/src/main/jniLibs/"
fi

test -f "$ROOT/app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so"
echo "JNI libraries ready under app/src/main/jniLibs/"
find "$ROOT/app/src/main/jniLibs" -name '*.so' | sort
