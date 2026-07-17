#!/usr/bin/env bash
# Build ONNX Runtime + sherpa-onnx JNI from source for F-Droid.
# Run from repo root after checkout.
set -euo pipefail

##############################################################################
# Configuration — bump these when updating native deps
##############################################################################
ORT_VERSION="1.27.0"
SHERPA_VERSION="v1.13.4"
SHERPA_VERSION_STRIP="1.13.4"                # same without leading v

ABIS=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")
MIN_ANDROID_API=26

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JNILIBS="$REPO_ROOT/app/src/main/jniLibs"

WORK="$REPO_ROOT/.native-build"
ORT_SRC="$WORK/onnxruntime-$ORT_VERSION"
SHERPA_SRC="$WORK/sherpa-onnx-$SHERPA_VERSION_STRIP"
ORT_INSTALL="$WORK/ort-install"

##############################################################################
# Prerequisite check
##############################################################################
command -v cmake >/dev/null 2>&1 || { echo "cmake >= 3.28 required"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 required"; exit 1; }
command -v ninja  >/dev/null 2>&1 || { echo "ninja required";  exit 1; }

ANDROID_NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
if [ -z "$ANDROID_NDK" ]; then
  # fallback: SDK-managed NDK
  ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$ANDROID_SDK" ]; then
    CANDIDATE=$(ls -1d "$ANDROID_SDK/ndk/"* 2>/dev/null | sort -V | tail -1)
    [ -n "$CANDIDATE" ] && ANDROID_NDK="$CANDIDATE"
  fi
fi
if [ -z "$ANDROID_NDK" ] || [ ! -f "$ANDROID_NDK/build/cmake/android.toolchain.cmake" ]; then
  echo "Android NDK not found. Set ANDROID_NDK_HOME or ANDROID_NDK."
  exit 1
fi
echo "Using NDK: $ANDROID_NDK"

##############################################################################
# Step 1 — Download ORT source
##############################################################################
mkdir -p "$WORK"
if [ ! -d "$ORT_SRC" ]; then
  echo "Downloading ONNX Runtime $ORT_VERSION..."
  curl -sL "https://github.com/microsoft/onnxruntime/archive/refs/tags/v${ORT_VERSION}.tar.gz" \
    | tar -xz -C "$WORK"
  mv "$WORK/onnxruntime-$ORT_VERSION" "$ORT_SRC" 2>/dev/null || true
fi

##############################################################################
# Step 2 — Build ORT shared lib per ABI
##############################################################################
build_ort() {
  local abi=$1
  local build_dir="$WORK/ort-build-$abi"
  local install_dir="$ORT_INSTALL/$abi"

  if [ -f "$install_dir/lib/libonnxruntime.so" ]; then
    echo "ORT $abi already built, skipping"
    return
  fi

  echo "Building ONNX Runtime for $abi ..."
  rm -rf "$build_dir" "$install_dir"
  mkdir -p "$build_dir" "$install_dir/lib" "$install_dir/include"

  case "$abi" in
    arm64-v8a)   ANDROID_ABI=arm64-v8a ;;
    armeabi-v7a) ANDROID_ABI=armeabi-v7a ;;
    x86)         ANDROID_ABI=x86 ;;
    x86_64)      ANDROID_ABI=x86_64 ;;
  esac

  cmake -S "$ORT_SRC/cmake" -B "$build_dir" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="android-$MIN_ANDROID_API" \
    -DCMAKE_BUILD_TYPE=Release \
    -Donnxruntime_BUILD_SHARED_LIB=ON \
    -Donnxruntime_BUILD_UNIT_TESTS=OFF \
    -Donnxruntime_ENABLE_PYTHON=OFF \
    -Donnxruntime_USE_JEMALLOC=OFF \
    -Donnxruntime_USE_TENSORRT=OFF \
    -Donnxruntime_USE_OPENMP=OFF \
    -Donnxruntime_USE_NNAPI_BUILTIN=OFF \
    -Donnxruntime_USE_DNNL=OFF \
    -Donnxruntime_USE_CUDA=OFF \
    -Donnxruntime_USE_ROCM=OFF \
    -Donnxruntime_DISABLE_RTTI=ON \
    -Donnxruntime_DISABLE_EXCEPTIONS=ON

  cmake --build "$build_dir" --config Release -- "-j$(nproc 2>/dev/null || echo 2)"

  # Copy .so and headers (ORT install target tries /usr, so we do it manually)
  cp "$build_dir/libonnxruntime.so" "$install_dir/lib/"
  cp -r "$ORT_SRC/include/onnxruntime/core/session/"*.h "$install_dir/include/"
}

for abi in "${ABIS[@]}"; do
  build_ort "$abi"
done

##############################################################################
# Step 3 — Download sherpa-onnx source
##############################################################################
if [ ! -d "$SHERPA_SRC" ]; then
  echo "Downloading sherpa-onnx $SHERPA_VERSION..."
  curl -sL "https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/${SHERPA_VERSION}.tar.gz" \
    | tar -xz -C "$WORK"
fi

##############################################################################
# Step 4 — Build sherpa-onnx JNI per ABI
##############################################################################
build_sherpa() {
  local abi=$1
  local build_dir="$WORK/sherpa-build-$abi"
  local ort_lib="$ORT_INSTALL/$abi"
  local jni_target="$JNILIBS/$abi"

  if [ -f "$jni_target/libsherpa-onnx-jni.so" ] && [ -f "$jni_target/libonnxruntime.so" ]; then
    echo "sherpa-onnx $abi already built, skipping"
    return
  fi

  echo "Building sherpa-onnx JNI for $abi ..."
  rm -rf "$build_dir"
  mkdir -p "$build_dir" "$jni_target"

  case "$abi" in
    arm64-v8a)   ANDROID_ABI=arm64-v8a ;;
    armeabi-v7a) ANDROID_ABI=armeabi-v7a ;;
    x86)         ANDROID_ABI=x86 ;;
    x86_64)      ANDROID_ABI=x86_64 ;;
  esac

  export SHERPA_ONNXRUNTIME_LIB_DIR="$ort_lib/lib"
  export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ort_lib/include"

  cmake -S "$SHERPA_SRC" -B "$build_dir" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="android-$MIN_ANDROID_API" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DSHERPA_ONNX_ENABLE_TTS=OFF \
    -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
    -DSHERPA_ONNX_ENABLE_BINARY=OFF \
    -DSHERPA_ONNX_ENABLE_C_API=OFF \
    -DSHERPA_ONNX_ENABLE_JNI=ON \
    -DSHERPA_ONNX_ENABLE_PYTHON=OFF \
    -DSHERPA_ONNX_ENABLE_TESTS=OFF \
    -DSHERPA_ONNX_ENABLE_CHECK=OFF \
    -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
    -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF

  cmake --build "$build_dir" --config Release -- "-j$(nproc 2>/dev/null || echo 2)"

  # Strip and copy .so files
  STRIP="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
  if [ -f "$build_dir/install/lib/libsherpa-onnx-jni.so" ]; then
    "$STRIP" --strip-all "$build_dir/install/lib/libsherpa-onnx-jni.so"
    cp "$build_dir/install/lib/libsherpa-onnx-jni.so" "$jni_target/"
  elif [ -f "$build_dir/lib/libsherpa-onnx-jni.so" ]; then
    "$STRIP" --strip-all "$build_dir/lib/libsherpa-onnx-jni.so"
    cp "$build_dir/lib/libsherpa-onnx-jni.so" "$jni_target/"
  else
    echo "ERROR: libsherpa-onnx-jni.so not found"
    exit 1
  fi
  "$STRIP" --strip-all "$ort_lib/lib/libonnxruntime.so"
  cp "$ort_lib/lib/libonnxruntime.so" "$jni_target/"
  echo "Copied to $jni_target"
}

mkdir -p "$JNILIBS"
for abi in "${ABIS[@]}"; do
  build_sherpa "$abi"
done

##############################################################################
# Step 5 — Verify
##############################################################################
echo "=== Verification ==="
for abi in "${ABIS[@]}"; do
  for lib in libsherpa-onnx-jni.so libonnxruntime.so; do
    f="$JNILIBS/$abi/$lib"
    if [ -f "$f" ]; then
      echo "OK  $f ($(du -h "$f" | cut -f1))"
    else
      echo "MISSING  $f"
    fi
  done
done
echo "=== Done ==="
