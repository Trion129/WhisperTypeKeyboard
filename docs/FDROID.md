# Publishing WhisperType on F-Droid

This guide prepares and submits **me.trion.whispertype** to the main F-Droid repository.

## Compliance checklist

| Requirement | Status |
|-------------|--------|
| Public source | https://github.com/Trion129/WhisperTypeKeyboard |
| FOSS license | MIT (`LICENSE`) + NOTICE for ONNX Runtime/model |
| No GMS / Firebase / ads | Yes |
| Builds from source (Gradle) | Yes |
| FOSS dependencies only | Yes (AndroidX, OkHttp, ONNX Runtime and Extensions) |
| Upstream metadata (fastlane) | `fastlane/metadata/android/en-US/` |
| Version tags | Use git tags like `v1.2.0` matching `versionName` |

### Notes reviewers will care about

1. **Native libraries**  
   `.so` files are not committed or downloaded by custom build scripts. The app
   uses the MIT-licensed `onnxruntime-android` and
   `onnxruntime-extensions-android` artifacts from Maven Central, which is a
   trusted repository under the F-Droid Inclusion Policy.

2. **Model downloads**  
   The Apache-2.0 Whisper Small model is downloaded only after the user presses
   Download. Its Hugging Face revision and SHA-256 are pinned in the app.
   Metadata marks **NonFreeNet** because first-time setup uses Hugging Face;
   dictation is offline after installation.

3. **Build recipe**  
   The F-Droid recipe is a plain Gradle build with no `prebuild`, `scanignore`,
   downloaded JNI archive, or NDK setup.

## What we added in this repo

```
fastlane/metadata/android/en-US/   # store listing text + changelogs
metadata/me.trion.whispertype.yml  # draft fdroiddata recipe
docs/FDROID.md                     # this file
```

## Step-by-step submission

### A. Finish upstream packaging (this repo)

1. Add at least 2 phone screenshots under:
   `fastlane/metadata/android/en-US/images/phoneScreenshots/`
   (`1.png`, `2.png` — portrait, no device frame required)
2. Add `fastlane/metadata/android/en-US/images/icon.png` (512×512 recommended)
3. Commit and push the version change, then run the GitHub **Release** workflow.
   The workflow creates the matching tag from that commit. For a manual tag:
   ```bash
   git tag -a v1.2.0 -m "v1.2.0"
   git push origin v1.2.0
   ```
4. Update `metadata/me.trion.whispertype.yml` → `Builds.commit` to the resulting
   tag's **full SHA**. This pin is required for the initial submission; future
   tagged releases are handled by `AutoUpdateMode`.

### B. Open an F-Droid RFP (optional but recommended)

https://gitlab.com/fdroid/rfp/-/issues/new

Title: `New App: me.trion.whispertype (WhisperType Keyboard)`

Include:

- Source: https://github.com/Trion129/WhisperTypeKeyboard  
- License: MIT  
- Summary: offline QWERTY + on-device STT  
- Note that ONNX Runtime comes from Maven Central and model download uses NonFreeNet  

### C. Submit metadata MR to fdroiddata

1. Fork https://gitlab.com/fdroid/fdroiddata  
2. Branch: `me.trion.whispertype`  
3. Copy draft recipe:
   ```bash
   cp metadata/me.trion.whispertype.yml \
      /path/to/fdroiddata/metadata/me.trion.whispertype.yml
   ```
4. Push and open a merge request titled:  
   `New App: me.trion.whispertype`
5. Watch CI on the MR; fix lint/build issues packagers report

### D. After merge

- F-Droid buildserver cycles take roughly **1–3 days** before the app appears  
- Future updates: push a new git tag; with `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`, metadata can auto-update  

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
