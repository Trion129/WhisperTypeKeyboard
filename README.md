# WhisperType Keyboard

Full QWERTY IME with offline voice dictation for Android.

Unlike dictation-only apps that just show a mic button, WhisperType is a complete
input method: type with a real QWERTY keyboard, or tap the mic and dictate -
all speech recognition runs on-device.

## Features

| Feature | Details |
|---------|---------|
| Typing | QWERTY, numbers/symbols layers, shift / caps lock, sentence caps, double-space period, word-delete swipe, cursor bar, globe IME switch |
| Long-press | Accents on letters, extra symbols on digits/punctuation (slide to select) |
| Suggestions | On-device English wordlist + local learned words; incognito toggle |
| Emoji | Categorized Unicode picker with search, recents, and skin tones (`😀`) |
| Clipboard | Last 20 text clips, captured while WhisperType is enabled, tap to paste, swipe or × to delete |
| Voice | Offline via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx); 25s warning, auto-stop at 30s |
| Models | `tiny.en` / `base.en` (default, ~161 MB) / `small.en` int8; import a custom sherpa Whisper zip |
| Privacy | No API keys — audio, clipboard, and learned words stay on the device |

## Setup

1. Install the APK (debug build, or a release from GitHub Releases)
2. Open **WhisperType**
3. Enable the keyboard in system settings
4. Select WhisperType as input method
5. Grant microphone permission
6. In Settings, pick **Base EN** (or another model) and **Download** - or **Import** a sherpa Whisper zip
7. Tap the mic on the keyboard to dictate offline

## Usage

Type with the keys; tap the **mic** to dictate (tap again to stop, or wait for
the 30s auto-stop); the **gear** opens settings; **long-press** keys for
accents and symbols; `😀` opens the emoji picker; the clipboard chip opens
history.

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
