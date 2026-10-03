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
`app/build.gradle.kts` (`com.github.k2-fsa:sherpa-onnx:v1.13.4`):

```bash
cd /tmp/whisper-check
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python \
    "sherpa-onnx==1.13.4" "soundfile==0.14.0" "numpy==2.5.3" \
    "faster-whisper==1.2.1" "datasets==5.0.1"
../..../WhisperTypeKeyboard/bench/fetch_models.sh /tmp/whisper-check/models small tiny base.en
```

faster-whisper (full OpenAI decoder: beam search + temperature fallback) is
the **quality reference**, not an app dependency. It pulls
`Systran/faster-whisper-<size>` on first use.

## Fixtures

```bash
.venv/bin/python bench/fetch_fixtures.py --out bench/fixtures \
    --langs hi_in,es_419,en_us --per-lang 3
```

FLEURS scripted studio clips (CC-BY) with written references in
`refs.tsv`. They are a *pipeline* baseline, not the phone-mic baseline —
for that, record per `docs/quality/capture-protocol.md`.

## Desktop paired run

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
| `app` | `WavReader` mirror (resample + peak norm) | sherpa 1.13.4 greedy + 30 s pad retry | the installed app |
| `raw` | resample only, **no peak norm** | same | what normalization does |
| `app-nopad` | `app` without the pad retry | same | what the retry does |
| `faster` | `app` samples | faster-whisper, beam 5 | the decoder-quality gap |
| `whispercpp` | `app` samples (PCM16 wav) | ggml-org/whisper.cpp, beam 5 + best-of 5 | the on-device candidate runtime |

The `app` mirror reproduces, exactly:
`WavReader.decodePcm` (float channel mean, pinned in `WavReaderTest`),
`WavReader.resample` linear interpolation and
truncating output length, `WavReader.preprocess` peak division,
`SherpaWhisperEngine`'s greedy_search / 2 threads / cpu / transcribe config
and its `tokens >= floor(s*6)` pad-to-30 s retry. One documented divergence:
the Python parser skips WAV pad bytes after odd-sized chunks and the Kotlin
parser does not — irrelevant for app-written recordings, relevant if you
feed third-party wavs to both.

Never compare different model sizes across runs and attribute the
difference to a runtime; hold size, weights, audio and language constant
and vary one thing. Desktop timings say nothing about phone timings.

## Emulator/on-device replay (real Kotlin pipeline)

`PipelineReplayTest` (androidTest) pushes the same fixtures through the
build's real `WavReader` + `SherpaWhisperEngine`:

```bash
adb push bench/fixtures /data/local/tmp/whispertype-bench/fixtures
adb push /tmp/whisper-check/models/small /data/local/tmp/whispertype-bench/models/small
cat > /tmp/whisper-check/manifest.json <<'EOF'
{"model_id":"small","language":"","fixtures_dir":"/data/local/tmp/whispertype-bench/fixtures","model_dir":"/data/local/tmp/whispertype-bench/models/small"}
EOF
adb push /tmp/whisper-check/manifest.json /data/local/tmp/whispertype-bench/manifest.json
adb shell chmod -R a+rX /data/local/tmp/whispertype-bench
./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=me.trion.whispertype.voice.PipelineReplayTest
adb shell run-as me.trion.whispertype cat files/bench-out/results.jsonl > bench/results/emulator-small.jsonl
```

Compare `results.jsonl` rows against the desktop `app` arm per file:
transcripts, `first_pass_tokens` vs `token_cap`, and `hit_token_cap`
should match; timings depend on the host and are not comparable.
Emulator x86_64 is a stand-in for the *pipeline*, not for phone CPU
performance — Phase 2's resource gate still needs the target phone.

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
