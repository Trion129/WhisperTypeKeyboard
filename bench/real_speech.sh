#!/usr/bin/env bash
# Real-speech quality bench, paired against whisper.cpp on the same clips.
#
# Runs the app's full VAD pipeline (as on the phone) over cached real
# recordings with reference text, plus a whisper.cpp reference decode, and
# prints per-dataset error/repeat summary. Findings that produced fixes are
# kept alongside the results JSONL files.
#
# Usage: bench/real_speech.sh <model_id> <language> [dataset]
#   bench/real_speech.sh canary-180m-flash es es-all
#   bench/real_speech.sh small hi hi-all
#   bench/real_speech.sh small "" hi-all        # auto-detect
# Environment: WHISPERTYPE_REAL_DATA (default ~/.cache/whispertype-host/data)
# Deps: the whisper.cpp reference needs `pip install pywhispercpp soundfile`
# and ggml-small.bin (downloaded on first use).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT=$(pwd)

MODEL=${1:?model id, e.g. small}
SPEECH_LANG=${2:-}
DATASET=${3:?dataset dir, e.g. es-all or hi-all}
CACHE=${WHISPERTYPE_HOST_CACHE:-$HOME/.cache/whispertype-host}
DATA=${WHISPERTYPE_REAL_DATA:-$CACHE/data}
DIR="$DATA/$DATASET"

[[ -f "$DIR/refs.tsv" ]] || { echo "no refs in $DIR; run: python3 bench/fetch_real.py"; exit 1; }
[[ -d "$CACHE/models/$MODEL" ]] || bench/fetch_models.sh "$CACHE/models" "$MODEL"

mkdir -p bench/results
TAG=$(
  printf '%s' "$MODEL-$SPEECH_LANG-$DATASET" | tr -d "'"
)
OUT="$ROOT/bench/results/real-$TAG-$(date +%Y%m%d-%H%M%S).jsonl"
JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" ./gradlew --no-daemon :app:testDebugUnitTest \
    --tests 'me.trion.whispertype.voice.VadHostPipelineReplayTest' --rerun -q \
    "-Pwhispertype.host.model=$MODEL" \
    "-Pwhispertype.host.modelDir=$CACHE/models/$MODEL" \
    "-Pwhispertype.host.language=$SPEECH_LANG" \
    "-Pwhispertype.host.fixtures=$DIR" \
    "-Pwhispertype.host.out=$OUT"

# whisper.cpp reference (the runtime used by consumer apps like Handy).
PY=${WHISPERTYPE_REAL_PY:-python3}
GGML="$CACHE/ggml-small.bin"
[[ -f "$GGML" ]] || curl -sfL -o "$GGML" \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin
REF="$OUT.ref.jsonl"
WHISPERTYPE_GGML="$GGML" "$PY" -I bench/real_refs.py whispercpp "${SPEECH_LANG:-auto}" "$DIR" "$REF"

python3 bench/real_speech_score.py "$DIR/refs.tsv" "$OUT" "$REF"
echo "results: $OUT / $REF"
