# Publishing WhisperType on F-Droid

This guide prepares and submits **me.trion.whispertype** to the main F-Droid repository.

## Compliance checklist

| Requirement | Status |
|-------------|--------|
| Public source | https://github.com/Trion129/WhisperTypeKeyboard |
| FOSS license | MIT (`LICENSE`) + NOTICE for ONNX Runtime/model |
| No GMS / Firebase / ads | Yes |
| Builds from source (Gradle) | Yes |
| FOSS dependencies only | Yes (AndroidX, OkHttp, sherpa-onnx via JitPack) |
| Upstream metadata (fastlane) | `fastlane/metadata/android/en-US/` |
| Version tags | Git tags like `v1.4.0` matching `versionName` |
| ABI-split release APKs | `splits { abi { ... } }` in `app/build.gradle.kts` |

### Notes reviewers will care about

1. **Native libraries**  
   Speech recognition uses **sherpa-onnx** from JitPack:
   `com.github.k2-fsa:sherpa-onnx:v1.13.4`. That artifact bundles the ONNX
   Runtime native libraries for all supported ABIs, so this repo does **not**
   commit `jniLibs` and does **not** use a `fdroid-fetch-jni` prebuild or any
   other downloaded-JNI build step. JitPack is on the F-Droid trusted Maven
   list, the same pattern used by SherpaTTS.

2. **Model downloads**  
   Users can download int8 Whisper models - `tiny.en`, `base.en` (default), or
   `small.en` - from the `csukuangfj/sherpa-onnx-whisper-*` packs on Hugging
   Face. The download happens only when the user taps Download; **Import** of a
   sherpa-onnx Whisper zip works fully offline. Metadata marks **NonFreeNet**
   because first-time setup can use Hugging Face; dictation is offline after
   the model is installed.

3. **Build recipe**  
   The F-Droid recipe is a plain Gradle build (`subdir: app`, `gradle: yes`)
   with no `prebuild`, `scanignore`, downloaded JNI archive, or NDK setup.

4. **ABI-split APKs and binary URLs**  
   The release build produces one APK per ABI (`armeabi-v7a`, `arm64-v8a`,
   `x86`, `x86_64`). Version codes are `base * 10 + offset` per ABI - for 1.5.0
   (base 9) that is 91, 92, 93, 94 - and the fdroiddata recipe uses `binary:`
   URLs pointing at the GitHub release assets plus `AllowedAPKSigningKeys` so
   each ABI APK is verified against the known signing key.

## What we added in this repo

```
fastlane/metadata/android/en-US/   # store listing text + changelogs
metadata/me.trion.whispertype.yml  # fdroiddata recipe (ABI-split, binary URLs)
docs/FDROID.md                     # this file
```

## Step-by-step submission

### A. Finish upstream packaging (this repo)

1. Add at least 2 phone screenshots under:
   `fastlane/metadata/android/en-US/images/phoneScreenshots/`
   (`1.png`, `2.png` - portrait, no device frame required)
2. Add `fastlane/metadata/android/en-US/images/icon.png` (512x512 recommended)
3. Commit and push the version change, then run the GitHub **Release** workflow.
   The workflow creates the matching tag from that commit. For a manual tag:
   ```bash
   git tag -a v1.4.0 -m "v1.4.0"
   git push origin v1.4.0
   ```
4. Update `metadata/me.trion.whispertype.yml` -> `Builds.commit` to the
   tag's **full SHA**. This pin is required for the initial submission; future
   tagged releases are handled by `AutoUpdateMode`.

### B. Open an F-Droid RFP (optional but recommended)

https://gitlab.com/fdroid/rfp/-/issues/new

Title: `New App: me.trion.whispertype (WhisperType Keyboard)`

Include:

- Source: https://github.com/Trion129/WhisperTypeKeyboard
- License: MIT
- Summary: offline QWERTY + on-device STT
- Note that sherpa-onnx comes from JitPack and model download uses NonFreeNet

### C. Submit metadata MR to fdroiddata

1. Fork https://gitlab.com/fdroid/fdroiddata
2. Branch: `me.trion.whispertype`
3. Copy the recipe:
   ```bash
   cp metadata/me.trion.whispertype.yml \
      /path/to/fdroiddata/metadata/me.trion.whispertype.yml
   ```
4. Push and open a merge request titled `New App: me.trion.whispertype`.

An MR for this app already exists and is the ongoing submission:
https://gitlab.com/fdroid/fdroiddata/-/merge_requests/43083 .
Keep it updated with this recipe (ABI-split APKs, `binary:` URLs,
`AllowedAPKSigningKeys`, versionCodes 81-84) and reply to packager feedback.

### D. After merge

- F-Droid buildserver cycles take roughly **1-3 days** before the app appears
- Future updates: push a new git tag; with `AutoUpdateMode: Version` +
  `UpdateCheckMode: Tags`, metadata can auto-update (binary URLs use `%v`)

## Local dry-run (optional)

If you install [fdroidserver](https://f-droid.org/docs/Installing_the_Server_and_Repo_Tools/):

```bash
fdroid lint me.trion.whispertype
fdroid build me.trion.whispertype
```

Or use the official buildserver container from the [Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/).

## Useful links

- [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/)
- [Submitting Quick Start](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [Anti-Features](https://f-droid.org/docs/Anti-Features/)
- [fdroiddata CONTRIBUTING](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md)
- [RFP issues](https://gitlab.com/fdroid/rfp/-/issues)
- [fdroiddata MR !43083 (ongoing submission)](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/43083)
