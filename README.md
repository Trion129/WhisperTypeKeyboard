# WhisperType Keyboard

Full QWERTY IME with offline voice dictation for Android.

Unlike dictation-only apps that just show a mic button, WhisperType is a complete
input method: type with a real QWERTY keyboard, or tap the mic and dictate -
all speech recognition runs on-device.

## Features

| Feature | Details |
|---------|---------|
| Typing | Full QWERTY layout with numbers and symbols layers, shift / caps lock, long-press key alternatives, and an **emoji mode** (`😀` key) |
| Voice | Fully offline via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (JitPack) |
| Models | `tiny.en` / `base.en` (default, ~161 MB) / `small.en` int8; import a custom sherpa Whisper zip; more packs at <https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/index.html> |
| Privacy | No API keys - audio never leaves the device |

## Setup

1. Install the APK (debug build, or a release from GitHub Releases)
2. Open **WhisperType**
3. Enable the keyboard in system settings
4. Select WhisperType as input method
5. Grant microphone permission
6. In Settings, pick **Base EN** (or another model) and **Download** - or **Import** a sherpa Whisper zip
7. Tap the mic on the keyboard to dictate offline

## Usage

Type normally with the keys; tap the **mic** to dictate (tap again to stop and
insert the text); the **gear** opens settings; **long-press** keys for
alternatives (e.g. accents); `😀` switches to the emoji layer.

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
  voice/        # SherpaWhisperEngine, ModelDownloader, model catalog, audio
  settings/     # Setup wizard + model management
  util/         # Preferences
```

## Credits

- On-device ASR runtime: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0), which bundles ONNX Runtime (MIT)
- Whisper models from the `csukuangfj/sherpa-onnx-whisper-*` packs on [Hugging Face](https://huggingface.co/csukuangfj) (OpenAI Whisper weights, MIT)
- Docs for extra models: the sherpa Whisper model index linked in Features above

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
