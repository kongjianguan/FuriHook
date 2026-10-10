param(
    [string]$Sdk = 'C:\Users\he\AppData\Local\Android\Sdk',
    [string]$Workspace = 'D:\workspace\furihook-validation',
    [string]$Serial = 'emulator-5580'
)

$ErrorActionPreference = 'Stop'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$outputDirectory = Join-Path $Workspace 'results\via'
$url = 'http://127.0.0.1:18765/via-ruby.html'
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

function Save-PageDump {
    param([string]$Name)
    Invoke-Device @('shell', 'uiautomator', 'dump', "/sdcard/furihook-via-$Name.xml") | Out-Null
    $path = Join-Path $outputDirectory "via-$Name.xml"
    Invoke-Device @('pull', "/sdcard/furihook-via-$Name.xml", $path) | Out-Null
    [xml]$document = Get-Content $path -Raw -Encoding UTF8
    return $document
}

function Read-PageReport {
    param([xml]$Document)
    foreach ($node in $Document.SelectNodes('//node')) {
        $text = $node.GetAttribute('text')
        if ($text.StartsWith('{"phase":"')) {
            return ($text | ConvertFrom-Json)
        }
    }
    return $null
}

function Wait-PageReport {
    param([string]$Name, [scriptblock]$Condition)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 700
        $document = Save-PageDump $Name
        $report = Read-PageReport $document
        if ($report -and (& $Condition $report)) { return @{ Document = $document; Report = $report } }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Via 页面验证超时：$Name"
}

function Tap-VisibleText {
    param([xml]$Document, [string]$Text)
    $node = $Document.SelectSingleNode("//node[@text='$Text' or @content-desc='$Text']")
    if (-not $node) { throw "UIAutomator 没有找到可触摸文本：$Text" }
    $bounds = [regex]::Match($node.GetAttribute('bounds'), '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $bounds.Success) { throw "UIAutomator 文本范围无效：$Text" }
    $x = [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
    $y = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
    Invoke-Device @('shell', 'input', 'tap', "$x", "$y") | Out-Null
}

try {
    $devices = Invoke-Device @('get-state')
    if (($devices -join '').Trim() -ne 'device') { throw "目标设备没有处于连接状态：$Serial" }
    $status = Read-Vector 'status'
    if ($status.data.'API Version' -ne 102) { throw 'Vector CLI 没有报告 API 102' }
    $modules = Read-Vector 'modules ls'
    if (@($modules.data | Where-Object { $_.PACKAGE -eq 'dev.furihook' -and $_.STATUS -eq 'enabled' }).Count -ne 1) {
        throw 'FuriHook 模块没有启用'
    }
    Read-Vector 'scope add dev.furihook mark.via.gp/0' | Out-Null
    Invoke-Device @('reverse', 'tcp:18765', 'tcp:18765') | Out-Null
    Invoke-Device @('shell', 'am', 'force-stop', 'mark.via.gp') | Out-Null
    Invoke-Device @('shell', 'logcat', '-c') | Out-Null

    $launch = Invoke-Device @('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW',
        '-n', 'mark.via.gp/mark.via.Trampoline', '-d', $url)
    if (-not (($launch -join "`n").Contains('Status: ok'))) { throw 'Via 浏览器没有打开测试页面' }

    $initial = Wait-PageReport 'initial' { param($report) $report.originalRubyMatch }
    foreach ($pair in @('今日:きょう', '学校:がっこう', '日本語:にほんご', '勉強:べんきょう')) {
        if ($pair -notin $initial.Report.originalRubies) { throw "初始 Ruby 缺少范围：$pair" }
    }
    if ($initial.Report.originalText -ne '今日は学校で日本語を勉強します。' -or
        -not $initial.Report.inputAndEditableExcluded -or -not $initial.Report.inputValuePreserved -or
        -not $initial.Report.editableTextPreserved -or -not $initial.Report.existingRubyPreserved) {
        throw '输入框、可编辑区域或原有 Ruby 排除检查失败'
    }

    Tap-VisibleText $initial.Document '生成动态 Ruby 注音'
    $updated = Wait-PageReport 'updated' { param($report) $report.updateRequested -and $report.updatedRubyMatch }
    foreach ($pair in @('明日:あした', '図書館:としょかん', '行:い')) {
        if ($pair -notin $updated.Report.updatedRubies) { throw "触摸更新后的 Ruby 缺少范围：$pair" }
    }

    $linkDump = Save-PageDump 'link-before'
    Tap-VisibleText $linkDump 'Open link'
    $linked = Wait-PageReport 'linked' { param($report) $report.linkClickCount -eq 1 }
    Save-PageDump 'final' | Out-Null
    $logs = Invoke-Device @('logcat', '-d', '-v', 'raw', '-s', 'FuriHook:I')
    $logs | Set-Content (Join-Path $outputDirectory 'via-logcat.txt') -Encoding utf8
    $prefix = 'dev.furihook: '
    $events = @($logs | Where-Object { $_.StartsWith($prefix) } | ForEach-Object {
        $_.Substring($prefix.Length) | ConvertFrom-Json
    })
    $applied = @($events | Where-Object {
        $_.event -eq 'webview_ruby_applied' -and $_.package -eq 'mark.via.gp' -and $_.count -gt 0
    })
    if ($applied.Count -eq 0) { throw 'Via WebView 没有记录实际 Ruby 注音应用事件' }
    if (@($events | Where-Object event -Match 'failed').Count -ne 0) { throw 'Via Hook 日志报告错误' }
    if (-not $linked.Report.originalRubyMatch -or -not $linked.Report.updatedRubyMatch -or
        -not $linked.Report.inputAndEditableExcluded -or -not $linked.Report.inputValuePreserved -or
        -not $linked.Report.editableTextPreserved -or -not $linked.Report.existingRubyPreserved -or
        $linked.Report.originalText -ne '今日は学校で日本語を勉強します。' -or
        $linked.Report.updatedText -ne '明日は図書館へ行きます。') {
        throw '最终网页状态没有通过完整 DOM 检查'
    }

    $report = [ordered]@{
        timestamp = [DateTimeOffset]::Now.ToString('o')
        serial = $Serial
        targetPackage = 'mark.via.gp'
        targetVersion = '7.3.3'
        url = $url
        originalRubyPairs = $linked.Report.originalRubies
        updatedRubyPairs = $linked.Report.updatedRubies
        linkClickCount = $linked.Report.linkClickCount
        inputAndEditableExcluded = $linked.Report.inputAndEditableExcluded
        existingRubyPreserved = $linked.Report.existingRubyPreserved
        webviewRubyApplicationEventCount = $applied.Count
        applicationCounts = @($applied | ForEach-Object count)
        verifiedWithActualViaActivity = $true
    }
    $linked.Report | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outputDirectory 'via-page-report.json') -Encoding utf8
    $report | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outputDirectory 'via-verification.json') -Encoding utf8
    $report | ConvertTo-Json -Depth 5
} finally {
    Invoke-Device @('shell', 'am', 'force-stop', 'mark.via.gp') | Out-Null
    $scope = Read-Vector 'scope ls dev.furihook'
    foreach ($entry in @($scope.data | Where-Object { $_.APP_PACKAGE -ne 'dev.furihook.testapp' })) {
        Read-Vector "scope rm dev.furihook $($entry.APP_PACKAGE)/$($entry.USER_ID)" | Out-Null
    }
    if (@($scope.data | Where-Object {
        $_.APP_PACKAGE -eq 'dev.furihook.testapp' -and $_.USER_ID -eq 0
    }).Count -eq 0) {
        Read-Vector 'scope add dev.furihook dev.furihook.testapp/0' | Out-Null
    }
    Invoke-Device @('reverse', 'tcp:18765', 'tcp:18765') | Out-Null
}
