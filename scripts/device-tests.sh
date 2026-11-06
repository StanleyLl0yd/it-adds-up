#!/usr/bin/env bash
set -euxo pipefail
# Core scenarios must work with the emulator disconnected from networks.
adb shell svc wifi disable || true
adb shell svc data disable || true
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=io.github.stanleyll0yd.itaddsup.AdsNetworkTest --stacktrace
# Gradle uninstalls the target after its test run. Reinstall and capture the real
# application once more before pulling screenshots, without Gradle cleanup.
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class 'io.github.stanleyll0yd.itaddsup.TrackerInstrumentedTest#screenshotsOfRealApplication' io.github.stanleyll0yd.itaddsup.test/androidx.test.runner.AndroidJUnitRunner | tee screenshot-test.txt
rg -F 'OK (1 test)' screenshot-test.txt
adb pull /sdcard/Android/data/io.github.stanleyll0yd.itaddsup/files/screenshots screenshots
