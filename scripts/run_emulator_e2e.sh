#!/usr/bin/env bash
set -euo pipefail

mkdir -p work/e2e
./gradlew :testapp:connectedDebugAndroidTest --stacktrace
python3 scripts/smoke_module.py --serial "emulator-${EMULATOR_PORT}" --output work/e2e
adb -s "emulator-${EMULATOR_PORT}" logcat -d -b crash > work/e2e/crash-log.txt
