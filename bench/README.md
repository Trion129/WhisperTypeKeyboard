# ASR quality bench (paired baseline)

Desktop + emulator tooling for plan step 1.4: replay the **same wav files**
through the app's pipeline and through reference decoders, and compare
per-utterance. This replaces the earlier `simulate_app.py` whose inputs
skipped `WavReader.preprocess` — the difference between the app's actual
inputs and the previous simulation is now measured instead of assumed.

**Nothing here uploads anything.** Audio, references and transcripts stay on
the machine; only metrics and token counts are printed.

## Setup (pinned to the app's runtime)

The sherpa-onnx Python package **must match** the app's JNI pin in
`app/build.gradle.kts` (`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`):

```bash
cd /tmp/whisper-check
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python \
    "sherpa-onnx==2.0.1" "soundfile==0.14.0" "numpy==2.5.3" \
    "faster-whisper==1.2.1" "datasets==5.0.1"
../../WhisperTypeKeyboard/bench/fetch_models.sh /tmp/whisper-check/models small tiny base.en oriserve-swift parakeet-ctc-110m
```

faster-whisper (full OpenAI decoder: beam search + temperature fallback) is
the **quality reference**, not an app dependency. It pulls
`Systran/faster-whisper-<size>` on first use.

## Quick host check (real Kotlin pipeline, no emulator)

Runs the app's `WavReader`, `ModelCatalog` descriptor resolution and shared `AsrEngineFactory` on the host JVM using the same sherpa-onnx JNI version as the app. The host replay uses Whisper or NeMo CTC according to the descriptor; token-cap trace columns are emitted only for Whisper.

```bash
bench/host_replay.sh tiny                              # English Whisper
bench/host_replay.sh small hi bench/fixtures
bench/host_replay.sh oriserve-swift hi bench/fixtures
bench/host_replay.sh parakeet-ctc-110m en bench/fixtures
bench/host_replay.sh canary-180m-flash fr bench/fixtures/french
```

The JNI libs and models are cached in `~/.cache/whispertype-host`
(override with `WHISPERTYPE_HOST_CACHE`); the libs are symlinked into
`app/src/test/jniLibs/` (gitignored). JSONL rows include descriptor title/revision, requested and effective language, preprocessing/decode timings, and Whisper-only retry trace fields when applicable (`used_retry`, `padded_up_front`, `detected_language`, `split_decodes`; `score.py` shows them as `RETRY`, `PAD-UP`, `SPLIT`). Other adapters report no synthetic token-cap metrics. Host execution validates the selected runtime path, not Android packaging, phone latency, or memory.

VAD path (the app's production segmenter):

```bash
WHISPERTYPE_HOST_VAD=1 bench/host_replay.sh small hi bench/fixtures
WHISPERTYPE_HOST_VAD=1 WHISPERTYPE_HOST_VAD_FILE=hi_in_1766.wav bench/host_replay.sh small hi bench/fixtures
```

Rows include the segment count, each segment length, the speech length, and the
joined transcript. The test asserts that at least one segment exists and that no
segment exceeds the model's `maxSegmentMs`.

For recordings longer than 60 seconds, concatenate fixture WAVs into
`segmented-over-60s.wav` in a fixture directory, then run the same Kotlin
adapter/segmenter path against each installed backend:

```bash
WHISPERTYPE_HOST_SEGMENTED=1 bench/host_replay.sh base en /tmp/long-fixture
WHISPERTYPE_HOST_SEGMENTED=1 bench/host_replay.sh parakeet-ctc-110m en /tmp/long-fixture
```

The directory must contain `segmented-over-60s.wav`. Output reports each
segment's sample boundaries, duration, transcript character count, and total
transcript length. Whisper segments are held below its model window; the CTC
segment size uses the catalog's conservative model-specific bound.

Artifact fetching calls the app's descriptor-based installer helper: there is
no second URL/file-name catalog in the shell scripts. The new Swift and Parakeet
artifacts use pinned revisions, byte sizes, and SHA-256 verification.

Scoring is opt-in via `WHISPERTYPE_REFS=/path/to/refs.tsv`. Use recordings and
references appropriate to the selected model. In particular, **do not score
Swift's Romanized output against Devanagari references**; that is a script
mismatch, not an accuracy comparison. An unset reference path prints transcripts
and timings only.


## Fixtures

```bash
.venv/bin/python bench/fetch_fixtures.py --out bench/fixtures \
    --langs hi_in,es_419,en_us --per-lang 3
```

FLEURS scripted studio clips (CC-BY) with written references in
`refs.tsv`. They are a *pipeline* baseline, not the phone-mic baseline —
for that, record per `docs/quality/capture-protocol.md`.

## Desktop paired run
The Python paired harness below is Whisper-specific. Use the Kotlin replay
above for the adapter-based Swift and NeMo CTC paths.


```bash
.venv/bin/python bench/harness.py \
    --wav-dir bench/fixtures --references bench/fixtures/refs.tsv \
    --model small --sherpa-model-dir /tmp/whisper-check/models/small \
    --language "" --arms app,raw,app-nopad,faster \
    --out bench/results/small-auto
```

Arms, all on the same audio and same model files:

| arm | preprocessing | decoder | isolates |
|---|---|---|---|
| `app` | `WavReader` mirror (resample + peak norm) | sherpa 2.0.1 greedy + ~29.5 s pad retry | the installed app |
| `raw` | resample only, **no peak norm** | same | what normalization does |
| `app-nopad` | `app` without the pad retry | same | what the retry does |
| `faster` | `app` samples | faster-whisper, beam 5 | the decoder-quality gap |
| `whispercpp` | `app` samples (PCM16 wav) | ggml-org/whisper.cpp, beam 5 + best-of 5 | the on-device candidate runtime |

The `app` mirror reproduces, exactly:
`WavReader.decodePcm` (float channel mean, pinned in `WavReaderTest`),
`WavReader.resample` linear interpolation and
truncating output length, `WavReader.preprocess` peak division,
`SherpaWhisperEngine`'s greedy_search / 2 threads / cpu / transcribe config
and its frame-based token-cap check with a pad-to-~29.5 s retry. One documented divergence:
the Python parser skips WAV pad bytes after odd-sized chunks and the Kotlin
parser does not — irrelevant for app-written recordings, relevant if you
feed third-party wavs to both.

Never compare different model sizes across runs and attribute the
difference to a runtime; hold size, weights, audio and language constant
and vary one thing. Desktop timings say nothing about phone timings.

## Emulator/on-device replay (real Kotlin pipeline)

`PipelineReplayTest` (androidTest) resolves the manifest's catalog model descriptor and dispatches through the same factory as dictation:

```bash
adb push bench/fixtures /data/local/tmp/whispertype-bench/fixtures
adb push /tmp/whisper-check/models/oriserve-swift /data/local/tmp/whispertype-bench/models/oriserve-swift
cat > /tmp/whisper-check/manifest.json <<'EOF'
{"model_id":"oriserve-swift","language":"hi","fixtures_dir":"/data/local/tmp/whispertype-bench/fixtures","model_dir":"/data/local/tmp/whispertype-bench/models/oriserve-swift"}
EOF
adb push /tmp/whisper-check/manifest.json /data/local/tmp/whispertype-bench/manifest.json
adb shell chmod -R a+rX /data/local/tmp/whispertype-bench
JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" ./gradlew --no-daemon connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=me.trion.whispertype.voice.PipelineReplayTest
adb shell run-as me.trion.whispertype cat files/bench-out/results.jsonl > bench/results/device-oriserve-swift.jsonl
```

Fetch paths are the descriptor's declared artifact paths: Whisper files retain their canonical names; Swift artifacts live under `hi-hinglish-swift/`; Parakeet CTC uses `model.int8.onnx` plus `tokens.txt`. Imported ZIP manifests are data-only (`version=1`, backend/layout roles, relative artifacts, and language policy), validated by the installer; packages do not select executable adapters.

Whisper rows include optional token-cap trace fields; CTC rows omit them.
`empty_tokens` counts tokens the runtime returned as empty strings — sherpa-onnx
1.13.x dropped Whisper byte-fallback tokens this way, deleting most Devanagari,
CJK and Arabic characters. `score.py` flags any non-zero row as `BYTE-LOSS`; the
app is pinned to 2.0.1, where the count is 0. Compare transcript and common timing fields for matching fixtures. Emulator timings are not phone timings; the ARM64 resource gate still requires a target device.

## whisper.cpp candidate arm (Phase 2 prep)

The `whispercpp` arm runs the on-device candidate runtime on the same
normalized samples, so its quality can be compared with the `app` and
`faster` arms before any Android integration. Setup used here:

```bash
git clone --depth 1 https://github.com/ggml-org/whisper.cpp ~/.cache/whisper-bench/whisper.cpp
# revision 60c0be6ac8fa71b1a2ae2dd938a31a34a508e774 (2026-10-02)
cmake -B build -DCMAKE_BUILD_TYPE=Release -DWHISPER_BUILD_TESTS=OFF
cmake --build build -j
# ggml-small-q8_0.bin (8-bit, closest to the app's int8 ONNX weights),
# sha256 49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f

.venv/bin/python bench/harness.py ... --arms app,app-nopad,faster,whispercpp \
    --whispercpp-cli ~/.cache/whisper-bench/whisper.cpp/build/bin/whisper-cli \
    --whispercpp-model ~/.cache/whisper-bench/models/ggml-small-q8_0.bin \
    --out bench/results/small-auto-v2
```

Decoding settings: `-l auto` (or the forced language), threads from
`--whispercpp-threads` (default 8), beam 5 with best-of 5, no timestamps.
The audio goes through a 16 kHz mono PCM16 wav because that is the CLI's
input format; quantization noise is far below the model's own variance.
Compare only same-size, same-language runs — `ggml-small` against the
app's `small-{encoder,decoder}.int8.onnx`, not against `tiny`.
