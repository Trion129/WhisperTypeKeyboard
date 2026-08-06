# Sherpa-onnx via JitPack (full ASR replace)

**Date:** 2026-08-07  
**Status:** Approved for implementation planning  
**App:** WhisperType Keyboard (`me.trion.whispertype`)

## Goal

Replace the RTranslator-style multi-session ONNX Whisper pipeline with **k2-fsa sherpa-onnx** loaded through **JitPack**, so the user can download and switch **Whisper tiny.en / base.en / small.en** (int8) on device, and still **import** a compatible local package.

## Non-goals

- Keep dual engines (no RTranslator path beside sherpa)
- medium / large / turbo models in v1 of this migration
- Multilingual non-`.en` models as first-class catalog entries
- F-Droid NDK source-build of sherpa JNI (JitPack AAR only for this version)
- Building or hosting custom RTranslator-format exports

## Decisions

| Decision | Choice |
|----------|--------|
| Engine | Full replace with sherpa-onnx |
| Dependency | JitPack `com.github.k2-fsa:sherpa-onnx:<pinned-tag>` |
| Remove | `onnxruntime-android`, `onnxruntime-extensions-android`, `WhisperEngine`, `OnnxRuntime`, RTranslator 6-file layout |
| Catalog sizes | tiny.en, base.en, small.en (int8) |
| Default model id | `base.en` |
| Import | Yes - sherpa 3-file Whisper package |
| Active storage | One active model id; each size in its own directory under `filesDir/models/` |
| Upgrade from 1.3.x | Old `whisper_small_int8` RTranslator install is not usable; delete or ignore and require new install |
| App version | Bump to 1.4.0 (breaking ASR backend) |
| F-Droid | Plain Gradle recipe; JitPack on trusted Maven list (same class of approach as SherpaTTS). No `fdroid-fetch-jni` / committed jniLibs |

## Architecture

```
[Settings]
  Select: Tiny EN | Base EN (default) | Small EN
  Download | Import | Delete
       |
       v
  filesDir/models/<model_id>/
    <id>-encoder.int8.onnx
    <id>-decoder.int8.onnx
    <id>-tokens.txt   (or tokens.txt)
       |
[Mic] -> AudioRecorder -> WAV -> LocalAsrEngine / SherpaWhisperEngine
       -> OfflineRecognizer (Whisper encoder/decoder + tokens)
       -> text -> InputConnection
```

Gradle:

- Add `maven { url = uri("https://jitpack.io") }` where repositories are declared for app deps
- `implementation("com.github.k2-fsa:sherpa-onnx:<pin>")`
- Remove Microsoft ONNX Runtime and Extensions dependencies

Pin: choose a JitPack tag that resolves successfully at implement time (candidate `v1.12.21` or newer OK build). Record the exact pin in `app/build.gradle.kts` and NOTICE.

## Model catalog

Official int8 English packs from Hugging Face `csukuangfj/sherpa-onnx-whisper-{id}`:

| id | Title | Approx download (enc+dec int8 + tokens) |
|----|--------|----------------------------------------|
| tiny.en | Whisper Tiny EN | ~104 MB |
| base.en | Whisper Base EN (default) | ~161 MB |
| small.en | Whisper Small EN | ~375 MB |

Download three files per model (not the old single RTranslator zip):

- `{id}-encoder.int8.onnx`
- `{id}-decoder.int8.onnx`
- `{id}-tokens.txt`

URL pattern:

`https://huggingface.co/csukuangfj/sherpa-onnx-whisper-{id}/resolve/main/{filename}`

Optional integrity: HTTPS + successful OfflineRecognizer init after download (SHA pins can be added later if desired; not required for v1).

## On-disk layout

```
filesDir/models/tiny.en/
filesDir/models/base.en/
filesDir/models/small.en/
filesDir/models/import/          # user import slot if not matching a catalog id
```

`Prefs.active_model_id` selects which directory the engine loads.

Multiple sizes may be installed at once; only one is active. Delete removes the target install and clears active id if it pointed there.

## Import

SAF open document (zip preferred):

Required logical files after extract (flat basenames):

- encoder: `*-encoder.int8.onnx` or `*-encoder.onnx` (prefer int8)
- decoder: `*-decoder.int8.onnx` or `*-decoder.onnx` (prefer int8)
- tokens: `*-tokens.txt` or `tokens.txt`

Flow: copy/extract to staging -> verify three files -> try OfflineRecognizer init -> on success install to `models/import/` or detected id folder -> set active -> release engine.

Failed import must not delete other installed sizes.

## Upgrade / migration

On first run of the sherpa build (or whenever active model files are missing):

- If `models/whisper_small_int8/` (RTranslator six ONNX names) exists, delete it
- Clear obsolete prefs (`model_source` download/import for old pipeline)
- User must Download or Import a sherpa pack before mic works

## UI

Settings Speech Model section:

- List three catalog entries with size hint and installed/active state
- User selects a row, then Download (if missing) or Set active (if installed)
- Shared progress for multi-file download
- Import and Delete actions
- Status strings use real approximate MB (no false less-than-100MB claims)

Setup wizard: point at settings to get a model; copy mentions download or import; default recommendation base.

Keyboard: `isModelReady()` gate unchanged in spirit; status may show active model title.

## Engine API (behavioral)

Replace RTranslator session loop with sherpa offline Whisper:

1. Build `OfflineRecognizer` from encoder path, decoder path, tokens path (CPU provider, small thread count e.g. 2-4)
2. Create stream, accept waveform (16 kHz float PCM from existing WavReader path), decode
3. Return text; apply light post-process if still desired (trim, optional capitalize) without English-token hacks from ModelConfig
4. Close recognizer/stream on release; `releaseAll` on switch/delete/destroy

Keep `AudioRecorder` + `WavReader` as the audio front end unless sherpa stream-from-mic is a clear win; WAV path is fine for v1.

## Prefs

- `active_model_id`: string, empty when none
- Remove or stop using old `model_source` values tied to RTranslator

## Error handling

| Failure | User-facing behavior |
|---------|----------------------|
| No active model | Prompt to install in settings |
| Download HTTP/IO | Show error; leave other models intact |
| Incomplete files | Not installed / invalid package |
| Recognizer init fails | Model could not be loaded; do not mark active |
| Import cancel | Silent |
| Import invalid | Invalid sherpa Whisper package |

## Files to change (expected)

| Area | Change |
|------|--------|
| `settings.gradle.kts` / root repos | JitPack repository |
| `app/build.gradle.kts` | sherpa dep; remove ORT deps; versionName 1.4.0 / versionCode bump |
| `voice/LocalAsrEngine.kt` | Load sherpa; drop WhisperEngine wiring |
| New engine wrapper | Thin sherpa Offline Whisper API |
| `ModelCatalog.kt` | Multi-entry catalog |
| `ModelDownloader.kt` | Multi-file download + sherpa import; new required basenames |
| Delete or gut | `WhisperEngine.kt`, `OnnxRuntime.kt`, `ModelConfig.kt` if unused |
| Settings UI + strings | Model list, sizes, actions |
| Tests | Catalog + install layout + import failure isolation |
| `NOTICE` / `LICENSE` | Sherpa Apache-2.0; remove stale "includes sherpa jni bindings" if wrong; remove ORT-extensions if gone |
| `README.md` | Document sherpa + model sizes |
| `metadata/me.trion.whispertype.yml` (draft) | Note JitPack; version placeholders for later fdroiddata MR |

## Testing

1. Unit: catalog ids unique; required file name matchers
2. Unit: incomplete install is not `isInstalled`
3. Unit: failed import does not remove other model dirs
4. Unit: delete clears active id when appropriate
5. Manual/device: JitPack resolve; download base.en; dictate once offline; switch to tiny; import zip

## Success criteria

- User downloads tiny, base, or small and dictation works offline
- Default recommendation is base.en
- Import installs a valid sherpa Whisper package
- No dependency on Microsoft onnxruntime-android or onnxruntime-extensions
- No RTranslator six-file requirement
- Old RTranslator folder does not crash the app
- Release version 1.4.0

## F-Droid notes (for later MR, not auto-posted)

- Prefer plain gradle build with JitPack dependency (trusted Maven host)
- Do not revive `scripts/fdroid-fetch-jni.sh` or committed `app/src/main/jniLibs` prebuilts
- If a reviewer rejects JitPack prebuilt AAR later, fall back plan is srclib source-build (jiyi pattern) - out of scope for this implementation version
- Update fdroiddata Builds to 1.4.0 and new commit when releasing
- NonFreeNet remains if catalog download uses Hugging Face; Import still allows offline install

## Open follow-ups

- medium.en catalog entry
- SHA-256 pins per file
- Multilingual models + language UI
- F-Droid JNI source-build recipe
- Stream mic samples directly into sherpa without WAV temp file
