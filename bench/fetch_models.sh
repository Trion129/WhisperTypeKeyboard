#!/usr/bin/env bash
# Downloads the exact sherpa-onnx Whisper files the app downloads
# (ModelSpec.baseUrl in ModelCatalog.kt) for the given model ids.
#
# Usage: ./bench/fetch_models.sh [dest] [model ids...]
#   ./bench/fetch_models.sh /tmp/whisper-check/models small base.en
set -euo pipefail

DEST="${1:-/tmp/whisper-check/models}"
shift || true
IDS=("$@")
[ ${#IDS[@]} -eq 0 ] && IDS=(small)

for id in "${IDS[@]}"; do
  mkdir -p "$DEST/$id"
  for f in "$id-encoder.int8.onnx" "$id-decoder.int8.onnx" "$id-tokens.txt"; do
    curl -fL --retry 3 -o "$DEST/$id/$f" \
      "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$id/resolve/main/$f" &
  done
done
wait
ls -la "$DEST"/*/
