#!/usr/bin/env bash
# Fetch artifacts through the app's real Kotlin catalog and downloader.
# Usage: bash bench/fetch_models.sh [dest] [model ids...]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
DEST=${1:-/tmp/whisper-check/models}
shift || true
IDS=("$@")
[ ${#IDS[@]} -eq 0 ] && IDS=(small)
mkdir -p "$DEST"
DEST=$(realpath "$DEST")
for id in "${IDS[@]}"; do
  WHISPERTYPE_FETCH_ARTIFACTS=1 WHISPERTYPE_FETCH_ONLY=1 \
    JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.net.preferIPv4Stack=true" \
    ./gradlew :app:testDebugUnitTest --no-daemon --rerun -q \
      --tests 'me.trion.whispertype.voice.HostPipelineReplayTest' \
      "-Pwhispertype.host.model=$id" "-Pwhispertype.host.modelDir=$DEST/$id"
done
