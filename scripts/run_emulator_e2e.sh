#!/usr/bin/env bash
set -euo pipefail

mkdir -p work/e2e
./gradlew :testapp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.furihook.testapp.MainActivityE2ETest,dev.furihook.testapp.RubyRendererE2ETest \
  --stacktrace
python3 scripts/smoke_module.py --serial "emulator-${EMULATOR_PORT}" --output work/e2e
adb -s "emulator-${EMULATOR_PORT}" logcat -d -b crash > work/e2e/crash-log.txt
