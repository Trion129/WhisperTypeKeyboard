# Phone state checklist (plan step 1.1)

**Purpose:** record the exact installed state of WhisperType on the target
phone before any change, so Gate A can be judged on evidence instead of
assumptions. None of these steps reinstall, clear, or otherwise alter the
app or its data. Fill in every field; keep the outputs in this folder
(local only).

**Gate A is UNVERIFIED until every field below is filled from the phone.**

## 0. Setup

Phone with USB debugging, adb on the workstation. Check the device is the
right one:

```bash
adb devices -l            # note serial
adb shell getprop ro.product.model        # expect: SM-S908B/DS or similar S22 Ultra
adb shell getprop ro.build.version.release # Android version
adb shell getprop ro.product.cpu.abilist   # expect arm64-v8a first
```

- model: ____________________  android: ______  abilist: ______________

## 1. Which build is actually installed

`versionName` alone cannot distinguish two builds of the same version; map
the installed APK to a build artifact by hash.

```bash
adb shell dumpsys package me.trion.whispertype | grep -E \
  'versionName|versionCode|lastUpdateTime|installerPackageName|firstInstallTime'
adb shell pm path me.trion.whispertype      # copy the base.apk path
adb pull <path-from-above> /tmp/installed-base.apk
sha256sum /tmp/installed-base.apk
```

- versionName: ______  versionCode: ______ (arm64-v8a release = 10*N+2)
- lastUpdateTime: ____________  firstInstallTime: ____________
- base.apk sha256: ______________________________________
- Matching artifact (dist/ APK, CI artifact, or commit): ____________________

## 2. Which model and language are ACTIVELY used

The Settings spinner selection is not evidence; the active state is what
`LocalAsrEngine.ensureLoaded()` resolves. The temporary diagnostics path
reports it at dictation time, on the installed release build, without
reinstalling:

```bash
adb shell settings put global whispertype_diagnostics 1
adb logcat -c
# On the phone: dictate one short phrase with the keyboard mic, then:
adb logcat -d -s WhisperTypeDiag > phone-diag.log
adb shell settings delete global whispertype_diagnostics   # switch off again
```

The `capture ... model=<id> language=<code>` line is the effective state.

- active model id: ____________  effective language: ____________
- model files present (dictation loaded and returned text): yes / no
- was the language what the user believes they selected? yes / no
  - if no: record both the Settings screen state and the log line: __________

## 3. Capture-quality snapshot

From the same `phone-diag.log` capture line (duration/rms/peak/clip/
silence/speech extents), and the wav copied to
`Download/WhisperTypeDiagnostics/` on the phone:

- duration ______ s  rms ______  peak ______  clipping ______
- silence ratio ______  speech extent ______..______ s
- does the recording sound correct when played back? yes / no

## 4. Context worth recording now

- free storage: `adb shell df -h /data | tail -1` → ____________
- battery optimization state for the app if dictation ever stalls: __________
- languages the user actually dictates (affected languages): __________

## Gate A reading

- If active model is an English-only id (e.g. `base.en`) while the user
  dictates Hindi/Spanish → state defect: fix selection/installation at its
  source, re-run this checklist, then retest the same recordings.
- If audio metrics look wrong (near-zero RMS, heavy clipping, speech
  extent ≈ 0) → capture defect: isolate in the pipeline before touching
  the decoder.
- If both are sound and transcripts are still poor → proceed to Phase 2
  on the same samples.
