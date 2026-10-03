#!/usr/bin/env bash
# Pull the Gate A baseline off the phone in one pass (plan steps 1.1–1.3).
#
#   docs/quality/pull-phone-baseline.sh
#
# Does, in order:
#   1. verify exactly one device and record model/android/abilist;
#   2. record the installed package state and the installed base.apk hash;
#   3. switch the (TEMPORARY) diagnostics path on and clear logcat;
#   4. wait while you dictate the scripted phrases;
#   5. pull the WhisperTypeDiag log and every recorded wav;
#   6. switch diagnostics off again.
#
# Local output (all gitignored, nothing uploads):
#   docs/quality/phone-state.txt        device + installed-artifact evidence
#   docs/quality/phone-diag.log         capture/decode log lines (no text)
#   bench/fixtures-phone/*.wav          the app's own mic captures
#
# adb resolution: $ADB if set, else the first of `adb`, then the Windows
# Android SDK adb (USB devices are visible to Windows, not to WSL), which
# still works over USB when the phone is plugged into the PC.
set -euo pipefail

cd "$(dirname "$0")/../.."

PACKAGE=me.trion.whispertype
REMOTE_DIR=/sdcard/Download/WhisperTypeDiagnostics
LOG_OUT=docs/quality/phone-diag.log
STATE_OUT=docs/quality/phone-state.txt
WAV_DIR=bench/fixtures-phone
WIN_ADB="/mnt/c/Users/parms/AppData/Local/Android/Sdk/platform-tools/adb.exe"

resolve_adb() {
    if [[ -n "${ADB:-}" ]]; then echo "$ADB"; return; fi
    if command -v adb >/dev/null && adb devices | grep -q "device$"; then echo adb; return; fi
    if [[ -x "$WIN_ADB" ]]; then echo "$WIN_ADB"; return; fi
    echo "error: no adb with a connected device; set ADB=/path/to/adb" >&2
    exit 1
}

ADB="$(resolve_adb)"
echo "== adb: $ADB"

DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
COUNT="$(echo -n "$DEVICES" | grep -c . || true)"
if [[ "$COUNT" -ne 1 ]]; then
    echo "error: expected exactly one authorized device, found $COUNT" >&2
    "$ADB" devices -l >&2
    exit 1
fi

mkdir -p "$WAV_DIR" docs/quality

{
    echo "# Phone state ($(date -Is))"
    echo
    echo "adb: $ADB"
    "$ADB" devices -l
    echo
    echo "model:   $("$ADB" shell getprop ro.product.model | tr -d '\r')"
    echo "android: $("$ADB" shell getprop ro.build.version.release | tr -d '\r')"
    echo "abilist: $("$ADB" shell getprop ro.product.cpu.abilist | tr -d '\r')"
    echo "storage: $("$ADB" shell df -h /data | tail -1 | tr -d '\r')"
    echo
    echo "## Installed package"
    "$ADB" shell dumpsys package "$PACKAGE" \
        | grep -E "versionName|versionCode|lastUpdateTime|firstInstallTime|installerPackageName" \
        | sed 's/^ *//'
    echo
    echo "## Installed base.apk"
    for apk in $("$ADB" shell pm path "$PACKAGE" | tr -d '\r' | sed 's/^package://'); do
        echo "$apk"
        echo -n "sha256 $(basename "$apk"): "
        "$ADB" exec-out cat "$apk" | sha256sum | cut -d' ' -f1
    done
} | tee "$STATE_OUT"

if ! "$ADB" shell ls "$REMOTE_DIR" >/dev/null 2>&1; then
    echo "note: $REMOTE_DIR does not exist yet (created by the first dictation)"
fi

echo
echo "== enabling diagnostics + clearing logcat"
"$ADB" shell settings put global whispertype_diagnostics 1
"$ADB" shell logcat -c

echo
echo "Dictate the scripted phrases now (docs/quality/capture-protocol.md)."
read -r -p "Press Enter when done... " _

echo
echo "== pulling log"
"$ADB" logcat -d -s WhisperTypeDiag >"$LOG_OUT"
grep -E "capture|decode" "$LOG_OUT" | tail -40 || true

echo
echo "== pulling wavs"
pulled=0
while IFS= read -r name; do
    [[ "$name" == *.wav ]] || continue
    "$ADB" exec-out cat "$REMOTE_DIR/$name" >"$WAV_DIR/$name"
    pulled=$((pulled + 1))
done < <("$ADB" shell ls "$REMOTE_DIR" 2>/dev/null | tr -d '\r')
echo "pulled $pulled wav(s) -> $WAV_DIR"

echo
echo "== disabling diagnostics"
"$ADB" shell settings delete global whispertype_diagnostics

cat <<EOF

Done. Next:
  1. write bench/fixtures-phone/refs.tsv (one line per wav: <name><TAB><said text>)
  2. replay the same audio through the app mirror + reference decoders:
     /tmp/whisper-check/.venv/bin/python bench/harness.py \\
         --wav-dir bench/fixtures-phone --references bench/fixtures-phone/refs.tsv \\
         --model small --sherpa-model-dir /tmp/whisper-check/models/small \\
         --language "" --arms app,app-nopad,faster \\
         --out bench/results/phone-small
EOF
