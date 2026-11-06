#!/bin/sh
# Helpers for the on-device run (emulator or phone over adb).
#   tools/device_test.sh push          - copy testdata/*.{m4a,mp3,wav} to /sdcard/Download
#   tools/device_test.sh mem <secs>    - sample PSS of the app every second, print the peak (MB)
#   tools/device_test.sh log           - print the app's timing lines from logcat
#   tools/device_test.sh pull <dir>    - pull exported JSON/TXT files from /sdcard/Download
set -eu
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
PKG=ru.replika.steno
cd "$(dirname "$0")/.."
case "${1:-}" in
  push)
    for f in testdata/*.m4a testdata/*.mp3 testdata/*.wav; do $ADB push "$f" /sdcard/Download/ >/dev/null && echo "pushed $f"; done
    $ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download >/dev/null 2>&1 || true ;;
  mem)
    n=${2:-120}; peak=0
    for i in $(seq 1 "$n"); do
      kb=$($ADB shell dumpsys meminfo $PKG 2>/dev/null | awk '/TOTAL PSS:/ {print $3; exit} /^ *TOTAL / {print $2; exit}')
      [ -n "$kb" ] && [ "$kb" -gt "$peak" ] && peak=$kb
      sleep 1
    done
    echo "peak PSS: $((peak / 1024)) MB" ;;
  log)
    $ADB logcat -d -s Replika:I | tail -40 ;;
  pull)
    mkdir -p "$2"; for f in $($ADB shell ls /sdcard/Download | tr -d '\r' | grep -E '\.(json|txt)$'); do $ADB pull "/sdcard/Download/$f" "$2/" >/dev/null && echo "pulled $f"; done ;;
  *) sed -n 2,7p "$0" ;;
esac
