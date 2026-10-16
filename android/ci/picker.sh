#!/usr/bin/env bash
# End-to-end check of "Your own file" on the emulator: put real files in Downloads,
# open the app normally, pick each one through Android's own file picker, and check
# the app recognized it. Fails the build if any step doesn't happen.
set -u
PKG=io.github.ravenor907.handheldforge
UI="python3 android/ci/ui.py"
mkdir -p screens picker-files
fail() { echo "::error title=File picker::$1"; $UI dump | head -60; adb exec-out screencap -p > "screens/picker-fail.png"; exit 1; }

# A USB-bootable (hybrid) ISO, an xz-compressed disk image, and a DVD-only ISO.
python3 - <<'PY'
import lzma
iso = bytearray(1 << 20); iso[510:512] = b"\x55\xaa"; iso[32769:32774] = b"CD001"
open("picker-files/forge-test.iso", "wb").write(iso)
disk = bytearray(3 << 20); disk[510:512] = b"\x55\xaa"
open("picker-files/forge-disk.img.xz", "wb").write(lzma.compress(bytes(disk), format=lzma.FORMAT_XZ))
dvd = bytearray(1 << 20); dvd[32769:32774] = b"CD001"
open("picker-files/forge-dvd.iso", "wb").write(dvd)
PY
for f in picker-files/*; do adb push "$f" /sdcard/Download/ >/dev/null; done
adb shell content call --method scan_volume --uri content://media --arg external_primary >/dev/null 2>&1 || true

# Pick NAME through the system picker, then expect EXPECT on the app's screen.
pick() {
  name="$1"; expect="$2"; shot="$3"
  for i in $(seq 1 12); do
    if $UI tap "$name" >/dev/null; then picked=1; break; fi
    # Not visible yet: open the picker's list of places and go to Downloads.
    $UI tap "Downloads" >/dev/null || $UI tap "Show roots" "Open navigation drawer" >/dev/null
    sleep 2
  done
  [ "${picked:-}" = 1 ] || fail "couldn't find $name in the file picker (greyed out or missing)"
  unset picked
  sleep 3
  adb exec-out screencap -p > "screens/$shot.png"
  $UI has "$name" "$expect" || fail "after picking $name the app didn't show '$expect'"
  echo "picked $name -> $expect"
}

adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 4
$UI has "Your own file" || fail "no 'Your own file' tile"
adb exec-out screencap -p > screens/picker-0-start.png

$UI tap "Your own file" >/dev/null || fail "couldn't tap 'Your own file'"
sleep 3
adb exec-out screencap -p > screens/picker-1-system-picker.png
pick forge-test.iso "ISO image" picker-2-iso

# Picking again replaces the file (tap the chosen tile, which shows "Change").
$UI tap "forge-test.iso" >/dev/null || fail "couldn't tap the chosen file to change it"
sleep 3
pick forge-disk.img.xz "Compressed disk image (.xz)" picker-3-xz

$UI tap "forge-disk.img.xz" >/dev/null || fail "couldn't tap the chosen file to change it"
sleep 3
pick forge-dvd.iso "DVD only" picker-4-dvd

if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  fail "the app crashed: $(adb logcat -d | grep -A12 'FATAL EXCEPTION' | head -14 | tr '\n' ' ')"
fi
echo "File picker works for ISO, .img.xz and DVD-only ISO"
