param(
    [string]$Workspace = 'D:\workspace\furihook-validation-armbridge',
    [string]$Serial = 'emulator-5582'
)

$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$archive = 'D:\workspace\X_12.31.0-prod.01.apks'
$module = 'D:\workspace\furihook-validation\app-debug.apk'
$extract = Join-Path $Workspace 'x-apks'
$results = Join-Path $Workspace 'results'
$moduleHash = (Get-FileHash $module -Algorithm SHA256).Hash.ToLower()
$archiveHash = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLower()
if ($moduleHash -ne '3bb955522e7a7e6e625decd7e186317956164c2d2c5d98b2ef19b6347dba6fda') {
    throw "FuriHook APK SHA-256 不匹配：$moduleHash"
}
if ($archiveHash -ne '03eb62077992827bb79fc2cec582db2734dc035b9bf9c6fffbfa456296ecf073') {
    throw "X APKS SHA-256 不匹配：$archiveHash"
}
$bridge = ((& $adb -s $Serial shell getprop ro.dalvik.vm.native.bridge) -join '').Trim()
$abis = ((& $adb -s $Serial shell getprop ro.product.cpu.abilist64) -join '').Trim()
if ($bridge -ne 'libndk_translation.so' -or $abis -notmatch 'arm64-v8a') {
    throw "Android 设备没有启用 ARM64 翻译：bridge=$bridge; abi=$abis"
}
$installed = (& $adb -s $Serial shell pm path com.twitter.android 2>$null) -join "`n"
if ($installed) { throw 'X 已安装；停止以保留原有应用状态' }
if (Test-Path $extract) { throw "X APKS 解压目录已存在：$extract" }
New-Item -ItemType Directory -Force $extract, $results | Out-Null
tar.exe -xf $archive -C $extract
if ($LASTEXITCODE -ne 0) { throw '读取 X APKS 归档失败' }
$splitNames = @(
    'base.apk',
    'split_config.arm64_v8a.apk',
    'split_config.xxxhdpi.apk',
    'split_config.en.apk',
    'split_config.ja.apk',
    'split_config.zh.apk'
)
$splitPaths = @($splitNames | ForEach-Object { Join-Path $extract $_ })
foreach ($path in $splitPaths) { if (-not (Test-Path $path)) { throw "X 安装分包缺失：$path" } }
& $adb -s $Serial install-multiple -r $splitPaths
if ($LASTEXITCODE -ne 0) { throw '安装 X 的 arm64 APK 分包失败' }
$packagePaths = @(& $adb -s $Serial shell pm path com.twitter.android)
if ($LASTEXITCODE -ne 0 -or @($packagePaths | Where-Object { $_ -match 'split_config.arm64_v8a.apk$' }).Count -ne 1) {
    throw '安装结果没有包含 X arm64 原生库分包'
}
$report = [ordered]@{
    serial = $Serial
    apiLevel = ((& $adb -s $Serial shell getprop ro.build.version.sdk) -join '').Trim()
    nativeBridge = $bridge
    abiList64 = $abis
    moduleSha256 = $moduleHash
    xApksSha256 = $archiveHash
    packageName = 'com.twitter.android'
    installedPaths = $packagePaths
    installedArm64Split = $true
    loggedIn = $false
    contentPublished = $false
}
$report | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $results 'x-install.json') -Encoding utf8
$report | ConvertTo-Json -Depth 4
