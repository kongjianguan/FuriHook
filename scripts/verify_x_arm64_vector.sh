#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
validation_root="$repo_root/work/native-arm64-validation"
sdk_root="$validation_root/sdk"
avd_name="furi-x-arm64-api35"
serial="emulator-5586"
android_home="$sdk_root"
emulator_bin="$sdk_root/emulator/emulator"
adb_bin="$sdk_root/platform-tools/adb"
apk="$repo_root/app/build/outputs/apk/debug/app-debug.apk"
vector_zip="$repo_root/work/root-tools/Vector-v2.2-3080-Release.zip"
magisk_apk="$repo_root/work/root-tools/Magisk-v30.7.apk"
x_apks="$repo_root/work/x-analysis/apks"
original_ramdisk="$sdk_root/system-images/android-35/google_apis/arm64-v8a/ramdisk.img"
copied_ramdisk="$validation_root/isolatedrootimage/ramdisk.img"
patched_ramdisk="$validation_root/isolatedrootimage/ramdisk.magisk"
boot_log="$validation_root/boot-magisk.log"
run_id="$(date '+%Y%m%d-%H%M%S')"
evidence_root="$validation_root/final-x-vector/$run_id"

export ANDROID_HOME="$android_home"
export ANDROID_SDK_ROOT="$sdk_root"
export ANDROID_USER_HOME="$validation_root/android-user"
export ANDROID_AVD_HOME="$validation_root/avd"
export ANDROID_EMULATOR_HOME="$validation_root/android-emulator"
export TMPDIR="$validation_root/tmp"
export TEST_TMPDIR="$validation_root/tmp"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$ANDROID_EMULATOR_HOME" "$TMPDIR" "$evidence_root"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

require_file() {
    [[ -f "$1" ]] || fail "缺少文件：$1"
}

require_file "$emulator_bin"
require_file "$adb_bin"
require_file "$apk"
require_file "$vector_zip"
require_file "$magisk_apk"
require_file "$x_apks/base.apk"
require_file "$x_apks/split_config.arm64_v8a.apk"
require_file "$x_apks/split_config.en.apk"
require_file "$x_apks/split_config.ja.apk"
require_file "$x_apks/split_config.xxxhdpi.apk"
require_file "$x_apks/split_config.zh.apk"
require_file "$original_ramdisk"
require_file "$copied_ramdisk"
require_file "$patched_ramdisk"
require_file "$boot_log"
command -v jq >/dev/null || fail '需要 jq 生成 JSON 验证报告'
command -v rg >/dev/null || fail '需要 ripgrep 检查运行证据'
command -v shasum >/dev/null || fail '需要 shasum 验证资产摘要'

expected_apk_sha="3bb955522e7a7e6e625decd7e186317956164c2d2c5d98b2ef19b6347dba6fda"
expected_vector_sha="9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc"
expected_magisk_sha="e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5"
expected_ramdisk_sha="46ff502d0e0a1d8f844d29c084a8b797107513d26199825f0d827836a87e84d7"
expected_patched_sha="a77dbaf7dfcbb350c104a479a358092024fa1f9be75802aad3d22e4ea2b3c2ec"
expected_x_base_sha="10a9cc7df96f6838fe7f767117fd9c969a0a2c6b103fd5e487e46a3d48fb7140"
expected_x_arm64_sha="26a09c21045c77883604f0c0e680148144e1ec757345bcc53755145869e0dcf0"
expected_x_ja_sha="49e109fade03496d7f836fc8e1d12d7dde6ba7300da89e0be9fde4127ee420b8"

sha256() {
    shasum -a 256 "$1" | awk '{print $1}'
}

[[ "$(sha256 "$apk")" == "$expected_apk_sha" ]] || fail '云端 APK SHA-256 与已验证构建不符'
[[ "$(sha256 "$vector_zip")" == "$expected_vector_sha" ]] || fail 'Vector ZIP SHA-256 与已验证发布包不符'
[[ "$(sha256 "$magisk_apk")" == "$expected_magisk_sha" ]] || fail 'Magisk APK SHA-256 与官方包不符'
[[ "$(sha256 "$original_ramdisk")" == "$expected_ramdisk_sha" ]] || fail 'Android SDK 原始 ramdisk SHA-256 已变化'
[[ "$(sha256 "$copied_ramdisk")" == "$expected_ramdisk_sha" ]] || fail '隔离副本与 Android SDK 原始 ramdisk 不一致'
[[ "$(sha256 "$patched_ramdisk")" == "$expected_patched_sha" ]] || fail 'Magisk 补丁 ramdisk SHA-256 与已验证镜像不符'
[[ "$(sha256 "$x_apks/base.apk")" == "$expected_x_base_sha" ]] || fail 'X base APK SHA-256 与已验证包不符'
[[ "$(sha256 "$x_apks/split_config.arm64_v8a.apk")" == "$expected_x_arm64_sha" ]] || fail 'X ARM64 split SHA-256 与已验证包不符'
[[ "$(sha256 "$x_apks/split_config.ja.apk")" == "$expected_x_ja_sha" ]] || fail 'X Japanese split SHA-256 与已验证包不符'
rg -q -- "$patched_ramdisk" "$boot_log" || fail '启动日志未确认使用隔离后的补丁 ramdisk'
if rg -q 'Storing crashdata|/tmp/' "$boot_log"; then
    fail '启动日志显示崩溃数据写入了临时系统目录'
fi

device_serials="$("$adb_bin" devices | awk 'NR > 1 && NF > 0 {print $1}')"
while IFS= read -r connected_serial; do
    [[ -z "$connected_serial" || "$connected_serial" == "$serial" ]] || fail "发现未授权设备：$connected_serial"
done <<< "$device_serials"

device_state="$("$adb_bin" -s "$serial" get-state 2>/dev/null || true)"
if [[ "$device_state" == 'device' ]]; then
    :
elif [[ -z "$device_state" ]]; then
    nohup "$emulator_bin" -avd "$avd_name" -port 5586 -ramdisk "$patched_ramdisk" \
        -crash-report-mode disabled -no-snapshot -no-audio -no-boot-anim -no-window \
        -verbose > "$boot_log" 2>&1 < /dev/null &
    printf '%s\n' "$!" > "$evidence_root/emulator.pid"
else
    fail "专用 AVD 串口当前状态为 $device_state"
fi

"$adb_bin" -s "$serial" wait-for-device
boot_deadline=$((SECONDS + 300))
until [[ "$("$adb_bin" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == '1' ]]; do
    (( SECONDS < boot_deadline )) || fail 'Android guest 在 300 秒内没有完成启动'
    sleep 2
done

emulator_process="$(ps -axo command | rg "q[e]mu-system-aarch64-headless -avd $avd_name " || true)"
[[ -n "$emulator_process" ]] || fail '无法找到专用 ARM64 模拟器进程'
rg -Fq -- '-crash-report-mode disabled' <<< "$emulator_process" || fail '模拟器进程未禁用崩溃报告模式'
rg -Fq -- "$patched_ramdisk" <<< "$emulator_process" || fail '模拟器进程未加载隔离补丁 ramdisk'
printf '%s\n' "$emulator_process" > "$evidence_root/emulator-command.txt"
if rg -q 'Storing crashdata|/tmp/' "$boot_log"; then
    fail '本次启动日志显示崩溃数据写入了临时系统目录'
fi

"$adb_bin" -s "$serial" root > "$evidence_root/adb-root.txt"
"$adb_bin" -s "$serial" wait-for-device
[[ "$("$adb_bin" -s "$serial" shell id | rg -o 'uid=0\(root\)' || true)" == 'uid=0(root)' ]] || fail 'ADB root 未生效'
[[ "$("$adb_bin" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" == '35' ]] || fail 'Android API 版本不是 35'
[[ "$("$adb_bin" -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')" == 'arm64-v8a' ]] || fail 'Guest ABI 不是 arm64-v8a'
[[ "$("$adb_bin" -s "$serial" shell getprop persist.sys.locale | tr -d '\r')" == 'ja-JP' ]] || fail '系统 locale 不是 ja-JP'
[[ "$("$adb_bin" -s "$serial" shell /debug_ramdisk/magisk -v | tr -d '\r')" == '30.7:MAGISK:R' ]] || fail '运行中的 Magisk 版本不是 30.7'
"$adb_bin" -s "$serial" shell dumpsys package com.twitter.android > "$evidence_root/x-package.txt"
rg -q 'versionCode=312310001' "$evidence_root/x-package.txt" || fail 'X versionCode 不是 312310001'
rg -q 'versionName=12.31.0-prod.01' "$evidence_root/x-package.txt" || fail 'X versionName 不是 12.31.0-prod.01'
"$adb_bin" -s "$serial" shell dumpsys package dev.furihook > "$evidence_root/module-package.txt"
rg -q 'versionCode=4' "$evidence_root/module-package.txt" || fail '设备中的 FuriHook 版本不是 0.4.0'
rg -q 'versionName=0.4.0' "$evidence_root/module-package.txt" || fail '设备中的 FuriHook 版本名称不是 0.4.0'
"$adb_bin" -s "$serial" shell pm path dev.furihook > "$evidence_root/module-apk-paths.txt"
module_base_line="$(rg '^package:.*/base.apk$' "$evidence_root/module-apk-paths.txt" || true)"
[[ -n "$module_base_line" && "$(printf '%s\n' "$module_base_line" | wc -l | tr -d ' ')" == '1' ]] || \
    fail 'FuriHook 已安装 APK 路径缺失或不唯一'
module_base_path="${module_base_line#package:}"
"$adb_bin" -s "$serial" pull "$module_base_path" "$evidence_root/installed-module-base.apk" \
    > "$evidence_root/module-apk-pull.txt"
installed_module_apk_sha="$(sha256 "$evidence_root/installed-module-base.apk")"
[[ "$installed_module_apk_sha" == "$expected_apk_sha" ]] || fail '设备中安装的 FuriHook APK 字节与已验证构建不一致'
"$adb_bin" -s "$serial" shell cat /data/adb/modules/zygisk_vector/module.prop > "$evidence_root/vector-module.prop"
rg -q '^versionCode=3080$' "$evidence_root/vector-module.prop" || fail 'Vector 模块版本不是 3080'
"$adb_bin" -s "$serial" shell pm path com.twitter.android > "$evidence_root/x-apk-paths.txt"
rg -q 'split_config.arm64_v8a.apk' "$evidence_root/x-apk-paths.txt" || fail 'X ARM64 split 未安装'
x_base_line="$(rg '^package:.*/base.apk$' "$evidence_root/x-apk-paths.txt" || true)"
x_arm64_line="$(rg '^package:.*/split_config\.arm64_v8a\.apk$' "$evidence_root/x-apk-paths.txt" || true)"
x_ja_line="$(rg '^package:.*/split_config\.ja\.apk$' "$evidence_root/x-apk-paths.txt" || true)"
for installed_path_line in "$x_base_line" "$x_arm64_line" "$x_ja_line"; do
    [[ -n "$installed_path_line" && "$(printf '%s\n' "$installed_path_line" | wc -l | tr -d ' ')" == '1' ]] || \
        fail 'X 的 base、ARM64 或 Japanese split APK 路径缺失或不唯一'
done
x_base_path="${x_base_line#package:}"
x_arm64_path="${x_arm64_line#package:}"
x_ja_path="${x_ja_line#package:}"
"$adb_bin" -s "$serial" pull "$x_base_path" "$evidence_root/installed-x-base.apk" \
    > "$evidence_root/x-base-apk-pull.txt"
"$adb_bin" -s "$serial" pull "$x_arm64_path" "$evidence_root/installed-x-arm64.apk" \
    > "$evidence_root/x-arm64-apk-pull.txt"
"$adb_bin" -s "$serial" pull "$x_ja_path" "$evidence_root/installed-x-ja.apk" \
    > "$evidence_root/x-ja-apk-pull.txt"
installed_x_base_sha="$(sha256 "$evidence_root/installed-x-base.apk")"
installed_x_arm64_sha="$(sha256 "$evidence_root/installed-x-arm64.apk")"
installed_x_ja_sha="$(sha256 "$evidence_root/installed-x-ja.apk")"
[[ "$installed_x_base_sha" == "$expected_x_base_sha" ]] || fail '设备中的 X base APK 字节与已验证包不一致'
[[ "$installed_x_arm64_sha" == "$expected_x_arm64_sha" ]] || fail '设备中的 X ARM64 split 字节与已验证包不一致'
[[ "$installed_x_ja_sha" == "$expected_x_ja_sha" ]] || fail '设备中的 X Japanese split 字节与已验证包不一致'

"$adb_bin" -s "$serial" shell logcat -c
"$adb_bin" -s "$serial" shell am force-stop com.twitter.android
"$adb_bin" -s "$serial" shell am start -W -n com.twitter.android/com.x.android.main.MainActivity \
    > "$evidence_root/x-start.txt"

runtime_log="$evidence_root/x-vector-runtime.log"
deadline=$((SECONDS + 120))
while :; do
    "$adb_bin" -s "$serial" shell 'logcat -d -v threadtime -s FuriHook:I VectorModuleManager:D Vector:V VectorZygiskBridge:D AndroidRuntime:E' > "$runtime_log"
    if rg -q '"event":"x_compose_ruby_applied"' "$runtime_log"; then
        break
    fi
    (( SECONDS < deadline )) || fail 'X 进程在 120 秒内没有产生 RubySpan 应用事件'
    sleep 2
done

rg -q 'Loading Vector/Xposed for com.twitter.android' "$runtime_log" || fail 'Vector 没有加载 X 进程'
rg -q 'VectorModuleManager: Loading module dev.furihook' "$runtime_log" || fail 'Vector 没有向 X 加载 FuriHook'
rg -q '"event":"module_loaded","process":"com.twitter.android","api":102' "$runtime_log" || fail 'FuriHook API 102 入口没有在 X 进程初始化'
rg -q '"event":"hook_registered","package":"com.twitter.android"' "$runtime_log" || fail 'TextView Hook 没有在 X 进程注册'
rg -q '"event":"webview_hooks_registered","package":"com.twitter.android"' "$runtime_log" || fail 'WebView Hook 没有在 X 进程注册'
rg -q '"event":"x_compose_hooks_registered"' "$runtime_log" || fail 'Compose Hook 没有在 X 进程注册'
if rg -q 'FATAL EXCEPTION|Process: com.twitter.android.*has died' "$runtime_log"; then
    fail 'AndroidRuntime 记录到 X 进程崩溃'
fi

"$adb_bin" -s "$serial" shell uiautomator dump /sdcard/x-vector-ui.xml \
    > "$evidence_root/uiautomator-dump.txt"
rg -q 'hierchary dumped to:' "$evidence_root/uiautomator-dump.txt" || fail 'uiautomator 没有确认生成新 XML'
"$adb_bin" -s "$serial" pull /sdcard/x-vector-ui.xml "$evidence_root/x-ui.xml" > "$evidence_root/ui-pull.txt"
rg -q 'いま何が起こっているかチェック|電話番号で続ける' "$evidence_root/x-ui.xml" || fail 'X 当前界面没有出现日文主屏文本'
"$adb_bin" -s "$serial" shell dumpsys activity activities > "$evidence_root/activities.txt"
rg -q 'topResumedActivity=.*com.twitter.android/com.x.android.main.MainActivity' "$evidence_root/activities.txt" || fail 'X 主 Activity 没有位于前台'
"$adb_bin" -s "$serial" shell cmd activity get-config > "$evidence_root/device-config.txt"
"$adb_bin" -s "$serial" shell cmd locale get-app-locales com.twitter.android > "$evidence_root/x-app-locales.txt"
"$adb_bin" -s "$serial" shell dumpsys connectivity > "$evidence_root/connectivity.txt"
"$adb_bin" -s "$serial" shell ss -tpn > "$evidence_root/sockets.txt"
"$adb_bin" -s "$serial" shell ps -A > "$evidence_root/processes.txt"
rg -q 'VALIDATED' "$evidence_root/connectivity.txt" || fail 'Android 当前网络没有通过系统验证'
rg -q 'twitter.android.*:443|:443.*twitter.android' "$evidence_root/sockets.txt" || \
    printf 'NOTICE: 当前采样没有观察到 X 的 443 socket\n' > "$evidence_root/network-note.txt"

compose_count="$(rg -c '"event":"x_compose_ruby_applied"' "$runtime_log" | awk '{sum += $1} END {print sum + 0}')"
textview_registered="$(rg -c '"event":"hook_registered","package":"com.twitter.android"' "$runtime_log" | awk '{sum += $1} END {print sum + 0}')"
vector_loading="$(rg -c 'Loading Vector/Xposed for com.twitter.android' "$runtime_log" | awk '{sum += $1} END {print sum + 0}')"
zygisk_bridge_events="$(rg -c 'VectorZygiskBridge: onTransact: action=GET_BINDER, callerUid=' "$runtime_log" | awk '{sum += $1} END {print sum + 0}')"
network_socket="false"
if rg -q 'twitter.android.*:443|:443.*twitter.android' "$evidence_root/sockets.txt"; then
    network_socket="true"
fi

jq -n \
    --arg timestamp "$(date '+%Y-%m-%dT%H:%M:%S%z')" \
    --arg serial "$serial" \
    --arg avd "$avd_name" \
    --arg apkSha256 "$(sha256 "$apk")" \
    --arg vectorSha256 "$(sha256 "$vector_zip")" \
    --arg magiskSha256 "$(sha256 "$magisk_apk")" \
    --arg originalRamdiskSha256 "$(sha256 "$original_ramdisk")" \
    --arg copiedRamdiskSha256 "$(sha256 "$copied_ramdisk")" \
    --arg patchedRamdiskSha256 "$(sha256 "$patched_ramdisk")" \
    --arg xBaseSha256 "$(sha256 "$x_apks/base.apk")" \
    --arg xArm64Sha256 "$(sha256 "$x_apks/split_config.arm64_v8a.apk")" \
    --arg xJapaneseSha256 "$(sha256 "$x_apks/split_config.ja.apk")" \
    --arg installedModuleApkSha256 "$installed_module_apk_sha" \
    --arg installedXBaseSha256 "$installed_x_base_sha" \
    --arg installedXArm64SplitSha256 "$installed_x_arm64_sha" \
    --arg installedXJapaneseSplitSha256 "$installed_x_ja_sha" \
    --arg magiskVersion "$("$adb_bin" -s "$serial" shell /debug_ramdisk/magisk -v | tr -d '\r')" \
    --arg vectorVersion "$(rg '^version=' "$evidence_root/vector-module.prop" | cut -d= -f2-)" \
    --arg api "$("$adb_bin" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" \
    --arg abi "$("$adb_bin" -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')" \
    --arg locale "$("$adb_bin" -s "$serial" shell getprop persist.sys.locale | tr -d '\r')" \
    --argjson composeRubySpanEvents "$compose_count" \
    --argjson textViewHookRegistrationEvents "$textview_registered" \
    --argjson vectorXLoadEvents "$vector_loading" \
    --argjson vectorZygiskBridgeEvents "$zygisk_bridge_events" \
    --argjson xHttpsSocketObserved "$network_socket" \
    --argjson webViewRubyApplied false \
    '{
      timestamp: $timestamp,
      device: {serial: $serial, avd: $avd, androidApi: $api, abi: $abi, systemLocale: $locale},
      artifacts: {
        cloudApkSha256: $apkSha256,
        vectorZipSha256: $vectorSha256,
        magiskApkSha256: $magiskSha256,
        sdkRamdiskSha256: $originalRamdiskSha256,
        isolatedRamdiskSha256: $copiedRamdiskSha256,
        patchedRamdiskSha256: $patchedRamdiskSha256,
        xBaseSha256: $xBaseSha256,
        xArm64SplitSha256: $xArm64Sha256,
        xJapaneseSplitSha256: $xJapaneseSha256,
        installedModuleApkSha256: $installedModuleApkSha256,
        installedXBaseSha256: $installedXBaseSha256,
        installedXArm64SplitSha256: $installedXArm64SplitSha256,
        installedXJapaneseSplitSha256: $installedXJapaneseSplitSha256
      },
      root: {adbRoot: true, magiskVersion: $magiskVersion, vectorVersion: $vectorVersion},
      runtime: {
        vectorXLoadEvents: $vectorXLoadEvents,
        vectorZygiskBridgeEvents: $vectorZygiskBridgeEvents,
        zygiskRuntimeVerified: ($vectorZygiskBridgeEvents > 0),
        textViewHookRegistrationEvents: $textViewHookRegistrationEvents,
        composeHookRegistered: true,
        composeRubySpanEvents: $composeRubySpanEvents,
        webViewHookRegistered: true,
        webViewRubyApplied: $webViewRubyApplied,
        xHttpsSocketObserved: $xHttpsSocketObserved,
        androidNetworkValidated: true,
        xAndroidRuntimeCrash: false
      },
      x: {
        package: "com.twitter.android",
        version: "12.31.0-prod.01 (312310001)",
        activity: "com.x.android.main.MainActivity",
        appLocaleOverride: "",
        JapaneseMainScreenInUiXml: true,
        authenticated: false
      },
      evidenceDirectory: "work/native-arm64-validation/final-x-vector",
      boundary: "Compose RubySpan application is confirmed by the module event; no authenticated timeline or WebView Ruby rendering was exercised."
    }' > "$evidence_root/result.json"

printf 'PASS: X ARM64 + Vector API 102 + FuriHook runtime evidence saved to %s\n' "$evidence_root/result.json"
