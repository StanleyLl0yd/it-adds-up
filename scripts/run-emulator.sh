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
export ANDROID_SERIAL=emulator-5554
adb start-server
"$ANDROID_HOME/emulator/emulator" -avd tracker-test -port 5554 -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim -no-metrics -memory 1536 -cores 2 > emulator.log 2>&1 &
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
if [[ "${2:-core}" == yandex ]]; then
  ./gradlew assembleDebug assembleDebugAndroidTest
  adb install -r -t app/build/outputs/apk/debug/app-debug.apk
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb shell am instrument -w -r -e class io.github.stanleyll0yd.itaddsup.AdsNetworkTest io.github.stanleyll0yd.itaddsup.test/androidx.test.runner.AndroidJUnitRunner | tee ads-test.txt
  adb pull /sdcard/Android/data/io.github.stanleyll0yd.itaddsup/files/screenshots screenshots
  rg -F 'OK (1 test)' ads-test.txt
else
  bash scripts/device-tests.sh
fi
