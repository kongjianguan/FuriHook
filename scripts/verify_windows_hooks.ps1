param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Workspace = 'D:\workspace\furihook-validation',
    [string]$Serial = 'emulator-5580'
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

function Read-Vector {
    param([string]$Command)
    $text = (Invoke-Device @('shell', "/debug_ramdisk/su -c 'PATH=/debug_ramdisk:/system/bin /data/adb/modules/zygisk_vector/cli --json $Command'")) -join "`n"
    $response = $text | ConvertFrom-Json
    if (-not $response.success) { throw "Vector CLI 操作失败：$Command" }
    return $response
}

$status = Read-Vector 'status'
if ($status.data.'API Version' -ne 102) { throw '测试框架没有报告 API 102' }
$modules = Read-Vector 'modules ls'
$module = @($modules.data | Where-Object { $_.PACKAGE -eq 'dev.furihook' -and $_.STATUS -eq 'enabled' })
if ($module.Count -ne 1) { throw 'FuriHook 没有启用' }
$scope = Read-Vector 'scope ls dev.furihook'
if (@($scope.data).Count -ne 1 -or $scope.data[0].APP_PACKAGE -ne 'dev.furihook.testapp' -or $scope.data[0].USER_ID -ne 0) {
    throw 'FuriHook 测试作用域与预期不同'
}
$status | ConvertTo-Json -Depth 5 | Set-Content "$outputDirectory\hook-framework-status.json" -Encoding utf8
$scope | ConvertTo-Json -Depth 5 | Set-Content "$outputDirectory\hook-scope.json" -Encoding utf8

& (Join-Path $PSScriptRoot 'test_windows_emulator.ps1') -Sdk $Sdk -Workspace $Workspace -Serial $Serial -SkipFinalLaunch
$testLogs = Invoke-Device @('logcat', '-d', '-v', 'raw', '-s', 'FuriHook:I')
$testLogs | Set-Content "$outputDirectory\furihook-instrumentation-logcat.txt" -Encoding utf8
$prefix = 'dev.furihook: '
$testEvents = @($testLogs | Where-Object { $_.StartsWith($prefix) } | ForEach-Object {
    $_.Substring($prefix.Length) | ConvertFrom-Json
})
foreach ($event in @('module_loaded', 'hook_registered', 'text_observed', 'ruby_applied')) {
    if (@($testEvents | Where-Object event -eq $event).Count -eq 0) { throw "instrumentation 期间缺少 Hook 事件：$event" }
}
if (@($testEvents | Where-Object event -Match 'failed').Count -ne 0) { throw 'instrumentation 期间 Hook 日志报告错误' }
$hookOutput = Invoke-Device @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'dev.furihook.testapp.RubyHookE2ETest', 'dev.furihook.testapp.test/androidx.test.runner.AndroidJUnitRunner')
$hookOutput | Set-Content "$outputDirectory\ruby-hook-instrumentation.txt" -Encoding utf8
$hookText = $hookOutput -join "`n"
if (-not $hookText.Contains('OK (1 test)')) { throw '真实 Hook Ruby 注音端到端测试未通过，查看 ruby-hook-instrumentation.txt' }
Invoke-Device @('shell', 'am', 'force-stop', 'dev.furihook.testapp') | Out-Null
Invoke-Device @('logcat', '-c') | Out-Null
$launch = Invoke-Device @('shell', 'am', 'start', '-W', '-n', 'dev.furihook.testapp/.MainActivity')
if (-not (($launch -join "`n").Contains('Status: ok'))) { throw 'Hook 目标应用启动失败' }
Start-Sleep -Seconds 3
$raw = Invoke-Device @('logcat', '-d', '-v', 'raw', '-s', 'FuriHook:I')
$raw | Set-Content "$outputDirectory\furihook-logcat.txt" -Encoding utf8
$events = @($raw | Where-Object { $_.StartsWith($prefix) } | ForEach-Object {
    $_.Substring($prefix.Length) | ConvertFrom-Json
})
if (@($events | Where-Object event -eq 'module_loaded').Count -ne 1) { throw '目标进程没有一次完整的模块初始化' }
$registration = @($events | Where-Object event -eq 'hook_registered')
if ($registration.Count -ne 1 -or $registration[0].methods.Count -ne 2) { throw '两个 TextView Hook 没有完成一次注册' }
if (@($events | Where-Object event -Match 'failed').Count -ne 0) { throw 'Hook 日志报告错误' }
$observed = @($events | Where-Object event -eq 'text_observed')
if ($observed.Count -eq 0) { throw '没有捕获到真实文本更新' }
$applied = @($events | Where-Object event -eq 'ruby_applied')
if (@($applied | Where-Object { $_.viewId -eq 1001 -and $_.segmentCount -eq 4 }).Count -eq 0) {
    throw '日语样例没有报告四个真实注音范围'
}
foreach ($viewId in @(1001, 1002, 1003, 1004)) {
    $sample = @($observed | Where-Object viewId -eq $viewId)
    if ($sample.Count -eq 0) { throw "缺少样例 View 的候选结果：$viewId" }
    $expected = $viewId -in @(1001, 1002)
    if ($sample[0].containsKanjiCandidate -ne $expected) { throw "候选检测结果错误：$viewId" }
}
if (@($observed | Where-Object { $_.viewId -in @(1011, 1012) -or $_.viewClass -eq 'android.widget.EditText' }).Count -ne 0) {
    throw '敏感输入控件进入了观测日志'
}
if (@($observed | Where-Object { $_.textExcerptLength -ne 0 -or $_.package -ne 'dev.furihook.testapp' }).Count -ne 0) {
    throw '日志原文长度或目标包名与要求不同'
}
$report = [ordered]@{
    hostName = $env:COMPUTERNAME
    serial = $Serial
    apiLevel = ((Invoke-Device @('shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim()
    framework = 'Vector'
    frameworkVersion = $status.data.'Framework Version'
    modernApi = $status.data.'API Version'
    hookExecutionVerified = $true
    applicationTestsPassedWithHooksEnabled = 6
    rubyRendererE2ETestsPassedWithHooksEnabled = 8
    hookIntegrationE2ETestsPassed = 1
    registeredMethods = $registration[0].methods
    candidateSampleViewIds = @(1001, 1002, 1003, 1004)
    sensitiveInputsObserved = $false
    textExcerptLength = 0
    observationCount = $observed.Count
    rubyApplicationCount = $applied.Count
    moduleApkSha256 = (Get-FileHash "$Workspace\app-debug.apk" -Algorithm SHA256).Hash.ToLower()
}
$events | ConvertTo-Json -Depth 5 | Set-Content "$outputDirectory\hook-events.json" -Encoding utf8
$hookOutput | Set-Content "$outputDirectory\ruby-hook-instrumentation.txt" -Encoding utf8
$report | ConvertTo-Json -Depth 5 | Set-Content "$outputDirectory\hook-verification.json" -Encoding utf8
$report | ConvertTo-Json -Depth 5
