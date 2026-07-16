# WhisperType Keyboard

Android keyboard with **real QWERTY typing** plus **offline voice dictation**.

Unlike dictation-only apps that only show a mic button, WhisperType is a full input method:

- Letters, numbers, symbols
- Shift / caps lock
- Space, enter, backspace (hold to repeat)
- Mic key for on-device speech-to-text
- Works in any app once enabled as a system keyboard

## Features

| Feature | Details |
|--------|---------|
| Typing | Full QWERTY keyboard with `?123` and symbols layers |
| Voice | Fully offline via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) |
| Models | **Parakeet 110M EN** (recommended) + **Whisper Tiny.en** |
| Privacy | No API keys — audio never leaves the device |

## Setup

1. Build & install the APK
2. Open **WhisperType**
3. Enable the keyboard in system settings
4. Select WhisperType as input method
5. Grant microphone permission
6. Open **Download models** and download Parakeet (recommended) or Whisper Tiny
7. Tap mic on the keyboard to dictate offline

## Usage

1. Focus any text field
2. **Type** with the keys normally
3. Tap the **green mic** to start listening
4. Tap mic again to stop → text is transcribed and inserted
5. Gear icon opens settings

## Build

1. Download sherpa-onnx Android `.so` libs into `app/src/main/jniLibs/`  
   (see `app/src/main/jniLibs/README.md` — binaries are **not** in this repo)
2. Build:

```bash
cd WhisperTypeKeyboard
./gradlew assembleDebug
```

APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements:

- Android Studio / Android SDK
- JDK 17+
- minSdk 26

## Project structure

```
app/src/main/java/com/whispertype/keyboard/
  ime/          # InputMethodService + keyboard UI
  voice/        # Audio recorder, model download, local ASR
  settings/     # Setup wizard + model management
  util/         # Preferences
app/src/main/java/com/k2fsa/sherpa/onnx/
  # sherpa-onnx Kotlin JNI bindings (Apache-2.0)
app/src/main/jniLibs/
  # Prebuilt sherpa-onnx + ONNX Runtime Android libraries
```

## Credits

- On-device ASR runtime: [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0)
- Models downloaded at runtime from the sherpa-onnx `asr-models` release

## F-Droid

Packaging notes and submission steps: [`docs/FDROID.md`](docs/FDROID.md)

Draft fdroiddata recipe: [`metadata/me.trion.whispertype.yml`](metadata/me.trion.whispertype.yml)

## Releases (CI)

GitHub Actions builds signed APK + AAB and publishes a GitHub Release **when you run it**.

### Trigger a release

1. Open **Actions** → **Release**
2. **Run workflow**
3. Enter:
   - `version_name` e.g. `1.2.0`
   - `version_code` e.g. `3` (must increase each time)
   - optional pre-release flag
4. Wait for the job; artifacts appear on the Releases page

### Optional production signing

Without secrets, the release APK is **debug-signed** (fine for testers).

For Play Store / production signing, add repo secrets:

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64 of your `.jks` / `.keystore` |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Encode keystore:

```bash
base64 -w0 your-release.keystore > keystore.b64
```

## License

MIT (see `LICENSE`). Native bindings and libraries from sherpa-onnx remain under Apache-2.0 (see `NOTICE`).
