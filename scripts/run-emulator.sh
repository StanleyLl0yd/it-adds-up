#!/usr/bin/env bash
set -euo pipefail
api="$1"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_AVD_HOME="${RUNNER_TEMP:-$PWD}/tracker-avd"
mkdir -p "$ANDROID_AVD_HOME"
image="system-images;android-${api};google_apis;x86_64"
# Hosted images already carry accepted SDK licenses. Never auto-accept new terms.
sdkmanager --install "platforms;android-${api}" "$image" < /dev/null
printf 'no\n' | avdmanager create avd --force -n tracker-test --package "$image" --device pixel_2 --path "$ANDROID_AVD_HOME/tracker-test.avd"
test -f "$ANDROID_AVD_HOME/tracker-test.ini"
adb start-server
"$ANDROID_HOME/emulator/emulator" -avd tracker-test -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim -no-metrics -memory 1536 -cores 2 > emulator.log 2>&1 &
emulator_pid=$!
trap 'adb emu kill || true' EXIT
booted=false
for attempt in $(seq 1 180); do
  if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then booted=true; break; fi
  if ! kill -0 "$emulator_pid" 2>/dev/null; then cat emulator.log; exit 1; fi
  sleep 2
done
if [[ "$booted" != true ]]; then cat emulator.log; exit 1; fi
adb shell input keyevent 82
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
bash scripts/device-tests.sh
