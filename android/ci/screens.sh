#!/usr/bin/env bash
# Runs inside the emulator job: install the debug APK, open each screen, screenshot it,
# and fail if the app crashed.
set -u
PKG=io.github.ravenor907.handheldforge
mkdir -p screens
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
for demo in empty choose confirm confirm-ready working done done-cachyos error; do
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.MainActivity --es demo $demo >/dev/null
  sleep 4
  adb exec-out screencap -p > screens/$demo.png
  if [ -z "$(adb shell pidof $PKG | tr -d '\r')" ]; then
    echo "::error title=App crashed::screen '$demo' is not running"
    adb logcat -d -s AndroidRuntime:E | tail -40
    exit 1
  fi
done
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  msg=$(adb logcat -d | grep -A15 "FATAL EXCEPTION" | head -30 | sed ':a;N;$!ba;s/\n/%0A/g')
  echo "::error title=Crash in logcat::$msg"
  exit 1
fi
echo "All screens rendered without crashing"
