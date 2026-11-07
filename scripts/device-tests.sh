#!/usr/bin/env bash
set -euxo pipefail
# Use only the CI emulator started by run-emulator.sh.
test "${ANDROID_SERIAL:-}" = emulator-5554
package=io.github.stanleyll0yd.itaddsup
./gradlew assembleDebug assembleDebugAndroidTest --stacktrace
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb root
timeout 30s adb wait-for-device
package_uid="$(adb shell cmd package list packages -U "$package" | tr -d '\r' | awk -v name="package:$package" '$1 == name { sub(/^uid:/, "", $2); print $2 }')"
[[ "$package_uid" =~ ^[0-9]+$ ]]
# Block the application's traffic while preserving adb and instrumentation transport.
adb shell iptables -I OUTPUT -m owner --uid-owner "$package_uid" '!' -o lo -j REJECT
adb shell ip6tables -I OUTPUT -m owner --uid-owner "$package_uid" '!' -o lo -j REJECT
timeout 180s adb shell am instrument -w -r -e class "$package.TrackerInstrumentedTest" "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee device-tests.txt
adb pull "/sdcard/Android/data/$package/files/screenshots" screenshots
grep -E 'OK \([1-9][0-9]* tests?\)' device-tests.txt
