param(
    [string]$Workspace = 'D:\workspace\furihook-validation-armbridge',
    [string]$Serial = 'emulator-5582'
)

$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$results = Join-Path $Workspace 'results'
New-Item -ItemType Directory -Force $results | Out-Null
$localeHelp = @(& $adb -s $Serial shell cmd locale help)
$localeHelp | Set-Content (Join-Path $results 'locale-cli-help.txt') -Encoding utf8
if (-not (($localeHelp -join "`n").Contains('set-app-locales'))) { throw 'Android locale CLI 缺少应用语言设置命令' }
$localeConfig = @(& $adb -s $Serial shell cmd locale get-app-localeconfig com.twitter.android --user 0)
$localeConfig | Set-Content (Join-Path $results 'x-locale-config-before.txt') -Encoding utf8
& $adb -s $Serial shell cmd locale set-app-locales com.twitter.android --user 0 --locales ja-JP
if ($LASTEXITCODE -ne 0) { throw '为 X 设置 ja-JP 应用语言失败' }
$locale = @(& $adb -s $Serial shell cmd locale get-app-locales com.twitter.android --user 0)
$locale | Set-Content (Join-Path $results 'x-app-locales.txt') -Encoding utf8
if (-not (($locale -join "`n").Contains('ja-JP'))) { throw 'X 应用语言未切换到 ja-JP' }
& $adb -s $Serial logcat -c
if ($LASTEXITCODE -ne 0) { throw '清理独立 X 测试设备日志失败' }
& $adb -s $Serial shell monkey -p com.twitter.android 1
if ($LASTEXITCODE -ne 0) { throw '启动真实 X Android 应用失败' }
Start-Sleep -Seconds 15
& $adb -s $Serial shell uiautomator dump /sdcard/x-runtime-ui.xml
if ($LASTEXITCODE -ne 0) { throw '读取 X 可访问性树失败' }
& $adb -s $Serial pull /sdcard/x-runtime-ui.xml (Join-Path $results 'x-runtime-ui.xml')
if ($LASTEXITCODE -ne 0) { throw '保存 X 可访问性树失败' }
& $adb -s $Serial shell dumpsys activity activities | Set-Content (Join-Path $results 'x-runtime-activities.txt') -Encoding utf8
$runtimeLogs = @(& $adb -s $Serial logcat -d -v raw -s FuriHook:I AndroidRuntime:E)
$logPath = Join-Path $results 'x-runtime-logcat.txt'
if ($runtimeLogs.Count -gt 0) {
    $runtimeLogs | Set-Content $logPath -Encoding utf8
} else {
    [System.IO.File]::WriteAllText($logPath, '')
}
$tree = Get-Content (Join-Path $results 'x-runtime-ui.xml') -Raw -Encoding utf8
$activity = Get-Content (Join-Path $results 'x-runtime-activities.txt') -Raw -Encoding utf8
$uiTree = [xml]$tree
$xNodes = @($uiTree.SelectNodes('//node') | Where-Object { $_.GetAttribute('package') -eq 'com.twitter.android' })
$xText = @($xNodes | ForEach-Object { $_.GetAttribute('text') } | Where-Object { $_ })
$japaneseText = @($xText | Where-Object { $_ -match '[\u3040-\u30ff\u3400-\u9fff]' })
$loginWall = @($xText | Where-Object { $_ -match 'ログイン|サインイン|アカウントを作成|Sign in|Create account' })
$permissionDialog = @($uiTree.SelectNodes('//node') | Where-Object { $_.GetAttribute('package') -eq 'com.google.android.permissioncontroller' }).Count -gt 0
$resumedLines = @($activity -split "`r?`n" | Where-Object { $_ -match 'mResumedActivity|topResumedActivity|state=RESUMED' -and $_ -match 'com.twitter.android' })
$xPid = ((& $adb -s $Serial shell pidof com.twitter.android) -join '').Trim()
$report = [ordered]@{
    packageName = 'com.twitter.android'
    locale = ($locale -join '').Trim()
    localeConfig = $localeConfig
    launchSurfaceCaptured = [bool]$tree.Contains('<hierarchy')
    xActivityResumed = $resumedLines.Count -gt 0
    resumedActivityEvidence = $resumedLines
    xProcessId = $xPid
    visibleXPackageNodeCount = $xNodes.Count
    accessibleXTextNodeCount = $xText.Count
    accessibleJapaneseTextNodeCount = $japaneseText.Count
    loginWallTextPresent = $loginWall.Count -gt 0
    permissionDialogVisible = $permissionDialog
    selectedRuntimeLogLineCount = $runtimeLogs.Count
    uiXml = 'x-runtime-ui.xml'
    hookLog = 'x-runtime-logcat.txt'
    accountLoginPerformed = $false
    contentPublished = $false
}
$report | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $results 'x-launch.json') -Encoding utf8
$report | ConvertTo-Json -Depth 4
