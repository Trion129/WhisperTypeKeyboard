# User model import (same ONNX zip layout)

**Date:** 2026-08-07
**Status:** Approved for implementation planning
**App:** WhisperType Keyboard (`me.trion.whispertype`)

## Goal

Let the user install the on-device ASR model from a local zip file (no network), using the same 6-file Whisper ONNX package layout already required by the app. Import shares the single existing model slot with the HuggingFace download path.

## Non-goals

- Multiple installed models or a model library
- Other formats (ggml, whisper.cpp, single-file ONNX, TFLite)
- Auto-detecting Whisper size / architecture (ModelConfig stays whisper-small constants)
- Changing the inference pipeline (WhisperEngine session graph unchanged)
- Making Import the primary setup path (Download remains the default onboarding action)

## Decisions

| Decision | Choice |
|----------|--------|
| Format | Same RTranslator-style zip: six `Whisper_*.onnx` basenames |
| Slot model | Replace-only: one active install under `filesDir/models/whisper_small_int8/` |
| Validation | Required basenames + non-empty files, then open the five ONNX sessions used at runtime and close them |
| Failure | Keep previous model (staging + safeInstall; validate before swap) |
| UI approach | Approach A: Import button on Settings next to Download/Delete |
| Prefs | Light source tracking: `model_source` = `download` \| `import` \| cleared |

## Architecture

```
[Settings]
  Download  -> HTTP zip (+ SHA-256) --+
  Import    -> SAF zip (no SHA)    --+--> staging
                                       -> verifyRequiredFiles
                                       -> validateSessions (5 ONNX open/close)
                                       -> safeInstall -> modelDir
  Delete    -> LocalAsrEngine.releaseAll + wipe modelDir + clear model_source

[Keyboard mic] -> LocalAsrEngine.isModelReady / transcribeWav -> WhisperEngine
```

Download and Import both end in the same directory layout the engine already expects. WhisperEngine, ModelConfig, AudioRecorder, WavReader, and OnnxRuntime stay as-is except where Import reuses OnnxRuntime for validation.

## On-disk layout (unchanged)

```
context.filesDir/models/whisper_small_int8/
  Whisper_initializer.onnx
  Whisper_encoder.onnx
  Whisper_decoder.onnx
  Whisper_cache_initializer.onnx
  Whisper_cache_initializer_batch.onnx   # required for install; not loaded by WhisperEngine
  Whisper_detokenizer.onnx
```

Staging/backup temps remain as today (`*.staging`, `*.backup`, `*.zip.part` / import part file).

## Import pipeline

1. User picks a file via `ACTION_OPEN_DOCUMENT` (prefer `application/zip`, allow broad fallback if needed).
2. Copy `ContentResolver` input stream to `modelsDir()/import.zip.part` (or equivalent part name under modelsDir).
3. `extractZipFlat` into `whisper_small_int8.staging`.
4. `verifyRequiredFiles(staging)` - same six basenames, each non-empty.
5. `validateSessions(staging)` - open the five files WhisperEngine loads (initializer, encoder, decoder, cache_initializer, detokenizer) via existing OnnxRuntime helpers; close all sessions; on any failure, abort without touching the live modelDir.
6. `safeInstall(staging, modelDir)` - existing atomic backup/swap.
7. Set `Prefs.model_source = "import"`.
8. `LocalAsrEngine.releaseAll()` so the next transcription reloads from disk.
9. Delete part file; ensure staging cleaned (safeInstall / failure paths).

Download path: keep SHA-256 verification; on success set `Prefs.model_source = "download"`.

## UI

### Settings (Speech Model section)

- Status text:
  - Not installed
  - Catalog title when source is download (e.g. existing Whisper Small title)
  - "Imported model" (or string resource) when source is import
- Buttons: Download | Import model... | Delete
- Shared progress indicator for download and import jobs
- Disable Download/Import while a job runs
- Delete: release engines, wipe model, clear `model_source`, refresh UI

### Setup wizard

No required layout change. Existing "Download model" continues to open Settings, where Import is available.

### Keyboard

Keep `isModelReady()` gate. Optionally surface `currentModelTitle()` in ready status so imported models show a distinct label.

## Error handling (user-visible)

| Failure | Behavior |
|---------|----------|
| Picker cancelled | No toast/error |
| Unreadable / not zip | Error: could not read selected file |
| Missing or empty required ONNX files | Error: invalid model package (missing files) |
| Session validation fails | Error: model files could not be loaded |
| IO / disk | Error: not enough space or write failed |
| Any failure after partial work | Live modelDir and previous `model_source` unchanged |

## Prefs

Add to `Prefs.kt`:

- `model_source`: String, values `"download"`, `"import"`, or absent/empty when no model / unknown
- Clear on delete
- Set only after successful install
- If model files exist but source missing (upgrade from older app version), treat as installed; display can fall back to catalog title or generic "Installed"

## Files to change

| File | Change |
|------|--------|
| `app/src/main/java/me/trion/whispertype/voice/ModelDownloader.kt` | `importFromUri` (or equivalent), `validateSessions`, share post-acquire install path with download |
| `app/src/main/java/me/trion/whispertype/util/Prefs.kt` | `model_source` accessors |
| `app/src/main/java/me/trion/whispertype/voice/LocalAsrEngine.kt` | `currentModelTitle()` respects import source |
| `app/src/main/java/me/trion/whispertype/settings/SettingsActivity.kt` | Import button, SAF launcher, wire import + progress/errors |
| `app/src/main/res/layout/activity_settings.xml` | Import button |
| `app/src/main/res/values/strings.xml` | Import label, status, error strings |
| `app/src/test/java/me/trion/whispertype/voice/ModelDownloaderTest.kt` | Import/verify/failure-preserves-model coverage |
| Docs (optional follow-up) | README / FDROID note that offline import exists |

**Explicitly unchanged:** `WhisperEngine.kt`, `ModelConfig.kt`, `AudioRecorder.kt`, `WavReader.kt`, core download URL/SHA in `ModelCatalog.kt` (still used for Download).

## Testing

1. `verifyRequiredFiles` / extract behavior remains covered (existing tests).
2. Failed verify leaves an existing modelDir intact (`safeInstall` / import orchestration).
3. Successful import path sets install markers the same way download does (`isInstalled()` true).
4. Prefs: source set to `import` on success; cleared on delete; not flipped on failed import.
5. `validateSessions`: provide a test seam (e.g. optional validator lambda or package-visible hook) so JVM unit tests can assert it runs after verify and that failure aborts install without requiring real ONNX weights in unit tests. Device/manual check: real zip opens sessions.

## Success criteria

- User can install a correct offline zip with network disabled.
- A bad zip never removes a working installed model.
- After successful import, mic transcription works the same as after download.
- Settings status distinguishes imported vs downloaded when source is known.

## Implementation notes

- Reuse `extractZipFlat`, `verifyRequiredFiles`, `safeInstall`, and download progress callback style where practical.
- Do not persist SAF URI long-term; copy into app-private storage at import time (no `takePersistableUriPermission` required for replace-only copy).
- F-Droid: optional doc note that users can avoid the HF download via Import; recipe/AntiFeature changes are out of scope for this feature unless product later wants them.

## Open follow-ups (not this feature)

- Multi-model library / switcher
- Other Whisper sizes with per-model ModelConfig
- Setup wizard copy change ("Get model" instead of "Download model")
