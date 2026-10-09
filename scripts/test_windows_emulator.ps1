param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Workspace = 'D:\workspace\furihook-validation',
    [string]$Serial = 'emulator-5580',
    [switch]$SkipFinalLaunch
)

$ErrorActionPreference = 'Stop'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$outputDirectory = Join-Path $Workspace 'results'
New-Item -ItemType Directory -Force $outputDirectory | Out-Null

function Invoke-Device {
    param([string[]]$Arguments)
    $result = & $adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB 操作失败：$($Arguments -join ' ')" }
    return $result
}

foreach ($apk in @('app-debug.apk', 'testapp-debug.apk', 'testapp-debug-androidTest.apk')) {
    $path = Join-Path $Workspace $apk
    if (-not (Test-Path $path)) { throw "缺少云端构建 APK：$path" }
    Invoke-Device @('install', '-r', $path) | Write-Output
}
Invoke-Device @('logcat', '-c') | Out-Null
$testClasses = 'dev.furihook.testapp.MainActivityE2ETest,dev.furihook.testapp.RubyRendererE2ETest'
$testOutput = Invoke-Device @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $testClasses, 'dev.furihook.testapp.test/androidx.test.runner.AndroidJUnitRunner')
$testOutput | Set-Content "$outputDirectory\instrumentation.txt" -Encoding utf8
$testOutput | Write-Output
$testText = $testOutput -join "`n"
if (-not $testText.Contains('OK (14 tests)')) { throw 'Android E2E 没有通过全部十四个测试，查看 instrumentation.txt' }

$launch = Invoke-Device @('shell', 'am', 'start', '-W', '-n', 'dev.furihook/.MainActivity')
$launch | Set-Content "$outputDirectory\module-launch.txt" -Encoding utf8
if (-not (($launch -join "`n").Contains('Status: ok'))) { throw 'FuriHook MainActivity 启动失败' }
Invoke-Device @('shell', 'uiautomator', 'dump', '/sdcard/furihook-module-ui.xml') | Out-Null
Invoke-Device @('pull', '/sdcard/furihook-module-ui.xml', "$outputDirectory\module-ui.xml") | Out-Null
[xml]$tree = Get-Content "$outputDirectory\module-ui.xml" -Raw -Encoding utf8
$texts = @($tree.SelectNodes('//node') | ForEach-Object { $_.GetAttribute('text') })
foreach ($expected in @('FuriHook 0.2.0', '阶段二 · TextView 本地振假名', '测试方法')) {
    if ($expected -notin $texts) { throw "模块界面缺少内容：$expected" }
}
$crashes = Invoke-Device @('logcat', '-d', '-b', 'crash')
($crashes -join "`n") | Set-Content "$outputDirectory\crash-log.txt" -Encoding utf8
$packages = Invoke-Device @('shell', 'pm', 'list', 'packages')
$lsposedPresent = [bool]($packages -match 'org\.lsposed\.manager')
$report = [ordered]@{
    hostName = $env:COMPUTERNAME
    serial = $Serial
    apiLevel = ((Invoke-Device @('shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim()
    testsPassed = 14
    originalApplicationTestsPassed = 6
    rubyRendererE2ETestsPassed = 8
    moduleInstalled = $true
    moduleActivityStarted = $true
    moduleUiAssertionsPassed = $true
    lsposedManagerPackagePresent = $lsposedPresent
    hookExecutionChecked = $false
    apks = @(Get-ChildItem "$Workspace\*.apk" | ForEach-Object {
        [ordered]@{ name = $_.Name; sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower() }
    })
}
$report | ConvertTo-Json -Depth 4 | Set-Content "$outputDirectory\windows-verification.json" -Encoding utf8
$report | ConvertTo-Json -Depth 4
if (-not $SkipFinalLaunch) {
    Invoke-Device @('shell', 'am', 'start', '-W', '-n', 'dev.furihook.testapp/.MainActivity') | Write-Output
}
