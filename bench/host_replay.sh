#!/usr/bin/env bash
# Run the app's real Kotlin ASR pipeline on the host JVM (no emulator) and
# score it. Usage: bench/host_replay.sh [model_id=tiny] [language=""] [fixtures_dir=bench/fixtures]
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT=$(pwd)

MODEL=${1:-tiny}
LANG_ARG=${2:-}
FIXTURES=${3:-bench/fixtures}
CACHE=${WHISPERTYPE_HOST_CACHE:-$HOME/.cache/whispertype-host}

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
    echo "host_replay.sh: only linux-x64 is wired (sherpa-onnx also ships osx-arm64 etc. JNI tarballs)" >&2
    exit 1
fi

VERSION=$(grep -o 'sherpa-onnx:v[0-9.]*' app/build.gradle.kts | head -n1 | sed 's/.*:v//')
[[ -n "$VERSION" ]] || { echo "cannot read sherpa-onnx version from app/build.gradle.kts" >&2; exit 1; }
JNI_NAME="sherpa-onnx-v${VERSION}-linux-x64-jni"
JNI_LIB="$CACHE/$JNI_NAME/lib"

mkdir -p "$CACHE"
if [[ ! -f "$JNI_LIB/libsherpa-onnx-jni.so" ]]; then
    echo "downloading $JNI_NAME ..."
    curl -fL "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/${JNI_NAME}.tar.bz2" \
        | tar -xj -C "$CACHE"
fi

mkdir -p "$CACHE/models/$MODEL"

mkdir -p app/src/test/jniLibs
ln -sf "$JNI_LIB/libsherpa-onnx-jni.so" app/src/test/jniLibs/
ln -sf "$JNI_LIB/libonnxruntime.so" app/src/test/jniLibs/

FIXTURES_ABS=$(cd "$FIXTURES" && pwd)
mkdir -p bench/results
OUT="$ROOT/bench/results/host-${MODEL}-${LANG_ARG:-auto}-$(date +%Y%m%d-%H%M%S).jsonl"

if [[ "${WHISPERTYPE_HOST_VAD:-0}" == "1" ]]; then
    JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.net.preferIPv4Stack=true" ./gradlew --no-daemon :app:testDebugUnitTest \
        --tests 'me.trion.whispertype.voice.VadHostPipelineReplayTest' --rerun -q \
        "-Pwhispertype.host.model=$MODEL" \
        "-Pwhispertype.host.modelDir=$CACHE/models/$MODEL" \
        "-Pwhispertype.host.language=$LANG_ARG" \
        "-Pwhispertype.host.fixtures=$FIXTURES_ABS" \
        "-Pwhispertype.host.out=$OUT"
    echo "results: $OUT"
    exit 0
fi

if [[ "${WHISPERTYPE_HOST_SEGMENTED:-0}" == "1" ]]; then
    [[ -f "$FIXTURES_ABS/segmented-over-60s.wav" ]] || {
        echo "segmented replay expects $FIXTURES_ABS/segmented-over-60s.wav" >&2
        exit 1
    }
    JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.net.preferIPv4Stack=true" ./gradlew --no-daemon :app:testDebugUnitTest \
        --tests 'me.trion.whispertype.voice.SegmentedHostPipelineReplayTest' --rerun -q \
        "-Pwhispertype.host.model=$MODEL" \
        "-Pwhispertype.host.modelDir=$CACHE/models/$MODEL" \
        "-Pwhispertype.host.language=$LANG_ARG" \
        "-Pwhispertype.host.fixtures=$FIXTURES_ABS"
    exit 0
fi

WHISPERTYPE_FETCH_ARTIFACTS=1 JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.net.preferIPv4Stack=true" ./gradlew --no-daemon :app:testDebugUnitTest \
    --tests 'me.trion.whispertype.voice.HostPipelineReplayTest' --rerun -q \
    "-Pwhispertype.host.model=$MODEL" \
    "-Pwhispertype.host.modelDir=$CACHE/models/$MODEL" \
    "-Pwhispertype.host.language=$LANG_ARG" \
    "-Pwhispertype.host.fixtures=$FIXTURES_ABS" \
    "-Pwhispertype.host.out=$OUT"

if [[ -n "${WHISPERTYPE_REFS:-}" ]]; then
    python3 bench/score.py "$OUT" --refs "$WHISPERTYPE_REFS"
else
    python3 bench/score.py "$OUT"
fi
python3 - "$OUT" <<'PY'
import json, sys
rows = [json.loads(line) for line in open(sys.argv[1], encoding="utf-8") if line.strip()]
padded = sum(1 for r in rows if r.get("padded_up_front"))
retried = sum(1 for r in rows if r.get("used_retry"))
langs = sorted({r["detected_language"] for r in rows if r.get("detected_language")})
print(f"trace: padded-up-front {padded}/{len(rows)}  retried {retried}  "
      f"detected {','.join(langs) or '-'}")
PY
echo "results: $OUT"
