#!/usr/bin/env bash
# Runs the IME instrumentation tests on the booted CI emulator.
# Usage: ime-tests.sh <try number>
# On failure, copies the test reports, the emulator logcat, and the window
# list to ime-reports/try-<n> so the workflow can upload them at once.
set -u
try="${1:?try number}"
out="ime-reports/try-$try"

# A cold first boot can freeze Pixel Launcher. Its "not responding" dialog
# then holds the input focus, and Android refuses to show any keyboard.
# Stopping the launcher closes its dialog; hide later error dialogs too.
adb shell settings put global hide_error_dialogs 1 || true
adb shell am force-stop com.google.android.apps.nexuslauncher || true
adb shell input keyevent KEYCODE_HOME || true
echo "Window focus before the tests:"
adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' || true

./gradlew :app:connectedDebugAndroidTest --no-daemon
status=$?
if [ "$status" -eq 0 ]; then
  exit 0
fi

mkdir -p "$out"
adb logcat -d > "$out/emulator-logcat.txt" || true
adb shell dumpsys window windows > "$out/windows.txt" || true
cp -r app/build/reports/androidTests/connected "$out/report" 2>/dev/null || true
cp -r app/build/outputs/androidTest-results/connected "$out/results" 2>/dev/null || true
exit "$status"
