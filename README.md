# WhisperType Keyboard

Full QWERTY IME with offline voice dictation for Android.

Unlike dictation-only apps that just show a mic button, WhisperType is a complete
input method: type with a real QWERTY keyboard, or tap the mic and dictate -
all speech recognition runs on-device.

## Features

| Feature | Details |
|---------|---------|
| Typing | QWERTY, numbers/symbols layers, shift / caps lock, sentence caps, double-space period, word-delete swipe, cursor bar |
| Long-press | Accents on letters, extra symbols on digits/punctuation (slide to select) |
| Suggestions | On-device English wordlist + local learned words; incognito toggle |
| Emoji | Categorized Unicode picker with search, recents, and skin tones (`😀`) |
| Clipboard | Last 20 text clips, captured while WhisperType is enabled, tap to paste, swipe or × to delete |
| Voice | Offline via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx); Silero VAD cuts at speech pauses while the microphone stays open and packs the speech into parts of about 24 s; dense scripts (Hindi, CJK, Arabic, ...) decode in the full Whisper window; elapsed-time indicator and manual stop; successful portions commit even if another segment fails |
| Models | English/multilingual Whisper, Canary 180M Flash EN/ES/DE/FR, Oriserve Swift Hindi2Hinglish, Parakeet CTC English; offline ZIP imports |
| Privacy | No API keys — audio, clipboard, and learned words stay on the device |

## Setup

1. Install the APK (debug build, or a release from GitHub Releases)
2. Open **WhisperType**
3. Enable the keyboard in system settings
4. Select WhisperType as input method
5. Grant microphone permission
6. In Settings, pick a model and **Download**, or **Import** a compatible ONNX package
7. Tap the mic on the keyboard to dictate offline

## Usage

Type with the keys; tap the **mic** to dictate (tap again to stop); the **gear** opens settings; **long-press** keys for
accents and symbols; `😀` opens the emoji picker; the clipboard chip opens
history.

## Models and languages

- **Oriserve Swift Hindi2Hinglish** (~161 MB): Hindi/Hinglish in **Romanized script**, not Devanagari. The adapter uses the Hindi language token.
- **Canary 180M Flash** (~207 MB): fast non-autoregressive EN/ES/DE/FR with casing and punctuation; language is selected, not auto-detected. The adapter uses the target-language token.
- **Parakeet TDT CTC 110M English** (~132 MB): the exported **NeMo CTC** graph, with English casing and punctuation. This is not a TDT/transducer adapter.
- **Whisper Tiny/Base/Small Multilingual**: auto-detect or a selected language, including Hindi in its native script.
- **Whisper Tiny/Base/Small EN**: English only; Base EN remains the default.

Fixed-language models disable the language picker without overwriting the saved
multilingual preference. Downloading, importing a replacement, or deleting a
model invalidates loaded native engines. Existing Whisper installations migrate
locally without redownloading.

Swift decoded the Hindi smoke fixtures without token-cap hits, but this is not
a broad accuracy benchmark. Direct decoder replay produced `Hai.` on a one-second
silent WAV. The existing recorder silence gate is unchanged; the adapter adds
neither VAD nor transliteration.

### Import packages

Existing unambiguous Whisper ZIPs remain supported. Other packages need a
`model.json` at the package root (one enclosing folder is allowed), for example:

```json
{
  "version": 1,
  "title": "My English CTC model",
  "backend": "nemo_ctc",
  "files": {"model": "graphs/model.onnx", "tokens": "tokens.txt"},
  "artifacts": ["graphs/model.onnx", "tokens.txt"],
  "language": {"mode": "fixed", "code": "en", "label": "English"}
}
```

For `whisper`, `files` must map `encoder`, `decoder`, and `tokens`.
List **every** artifact, including external ONNX data sidecars, using safe
relative paths. Files retain their package layout. Optional provenance fields
are `sourceUrl`, `license`, and `revision`; `approxSizeMb` controls the size label.
Whisper also supports `{"mode":"selectable","codes":["","hi"],"defaultCode":""}`;
`""` means auto-detect. An automatic-only policy uses
`{"mode":"automatic","label":"Auto-detect"}`. CTC cannot advertise selectable
language forcing. Unsupported backends/versions and invalid packages are
rejected without replacing the installed model.

### Adding model families

`ModelSpec` declares typed layout, language policy, artifact paths/checksums, and
provenance. `AsrEngineFactory` dispatches to `SherpaWhisperEngine` or
`SherpaNemoCtcEngine`; dictation and Kotlin replay use the same factory.
Another compatible model in these families needs a descriptor, not a new
dictation path. A new family needs a real `AsrEngine`, typed layout, factory
binding, and manifest support. Raw Hugging Face/PyTorch checkpoints are not
compatible ONNX packages.

## Build

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements:

- JDK 17+
- Android SDK
- minSdk 26
- Network access once at build time for the JitPack sherpa-onnx artifact

Release splits (optional, local):

```bash
# with KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD set
./gradlew assembleRelease
# per-ABI APKs under app/build/outputs/apk/release/
```

## Project structure

```
app/src/main/java/me/trion/whispertype/
  ime/          # InputMethodService + keyboard UI (layouts, emoji, long-press)
  voice/        # ASR adapters/factory, descriptors, installer, audio processing
  settings/     # Setup wizard + model management
  util/         # Preferences
```

## Credits

- On-device ASR runtime: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0), which bundles ONNX Runtime (MIT)
- Whisper models from the `csukuangfj/sherpa-onnx-whisper-*` packs on [Hugging Face](https://huggingface.co/csukuangfj) (OpenAI Whisper weights, MIT)
- [Oriserve Hindi2Hinglish Swift](https://huggingface.co/Oriserve/Whisper-Hindi2Hinglish-Swift) (Apache-2.0), using the pinned [sherpa export](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx)
- NVIDIA Parakeet CTC (CC-BY-4.0), sherpa export distributed through the pinned [VocaHQ mirror](https://huggingface.co/VocaHQ/sherpa-onnx-nemo-parakeet-tdt-ctc-110m-en-int8)
- NVIDIA Canary 180M Flash (CC-BY-4.0), pinned [sherpa export](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8)

## F-Droid

Packaging notes: [`docs/FDROID.md`](docs/FDROID.md)

fdroiddata recipe draft: [`metadata/me.trion.whispertype.yml`](metadata/me.trion.whispertype.yml)

The F-Droid MR/process is documented there briefly. Models are downloaded at
runtime, so the build itself needs no model assets; `NonFreeNet` is noted for
Hugging Face downloads, and **Import** works fully offline.

## Releases

- Latest: <https://github.com/Trion129/WhisperTypeKeyboard/releases> (v1.4.0)
- Per-ABI APKs: `arm64-v8a` (most phones), `armeabi-v7a`, `x86`, `x86_64` - plus an AAB
- CI: **Actions** -> **Release** workflow; needs `KEYSTORE_*` secrets
  (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) for
  production signing. Without them the release is debug-signed.

## License

MIT (see `LICENSE`). Third-party runtime and model licenses are listed in
`NOTICE`.
