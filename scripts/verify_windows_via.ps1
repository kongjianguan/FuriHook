param(
    [string]$Sdk = 'C:\Users\he\AppData\Local\Android\Sdk',
    [string]$Workspace = 'D:\workspace\furihook-validation',
    [string]$Serial = 'emulator-5580',
    [switch]$Diagnostic
)

$ErrorActionPreference = 'Stop'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$outputDirectory = Join-Path $Workspace 'results\via'
$fixtureDirectory = Join-Path $Workspace 'host-compatibility'
$url = 'http://127.0.0.1:18765/via-ruby.html'
$pageUri = [UriBuilder]$url
$queryFields = @("run=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())")
if ($Diagnostic) { $queryFields += 'diagnostic=1' }
$pageUri.Query = $queryFields -join '&'
$url = $pageUri.Uri.AbsoluteUri
New-Item -ItemType Directory -Force $outputDirectory | Out-Null
foreach ($fixture in @(
    'via-ruby.html',
    'vendor\react-18.3.1.production.min.js',
    'vendor\react-dom-18.3.1.production.min.js',
    'licenses\React-18.3.1-LICENSE.txt',
    'licenses\ReactDOM-18.3.1-LICENSE.txt'
)) {
    if (-not (Test-Path (Join-Path $fixtureDirectory $fixture) -PathType Leaf)) {
        throw "React/Via 验收文件缺失：$(Join-Path $fixtureDirectory $fixture)"
    }
}

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

function Read-NodeBounds {
    param([System.Xml.XmlElement]$Node)
    $match = [regex]::Match($Node.GetAttribute('bounds'), '^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$')
    if (-not $match.Success) { throw 'UIAutomator 节点范围无效' }
    return @{ Left = [int]$match.Groups[1].Value; Top = [int]$match.Groups[2].Value;
        Right = [int]$match.Groups[3].Value; Bottom = [int]$match.Groups[4].Value }
}

function Tap-VisibleText {
    param([string]$Text)
    for ($attempt = 0; $attempt -lt 12; $attempt++) {
        $document = Save-PageDump 'tap-current'
        $webNode = $document.SelectSingleNode("//node[@package='mark.via.gp' and @class='android.webkit.WebView']")
        if (-not $webNode) { throw 'UIAutomator 没有找到 Via WebView 视口' }
        $viewport = Read-NodeBounds $webNode
        $node = $document.SelectSingleNode("//node[@text='$Text' or @content-desc='$Text']")
        $bounds = if ($node) { Read-NodeBounds $node } else { $null }
        if ($bounds) {
            $left = [Math]::Max($bounds.Left, $viewport.Left)
            $right = [Math]::Min($bounds.Right, $viewport.Right)
            $top = [Math]::Max($bounds.Top, $viewport.Top)
            $bottom = [Math]::Min($bounds.Bottom, $viewport.Bottom)
            if ($right - $left -ge 8 -and $bottom - $top -ge 8) {
                $x = [int](($left + $right) / 2)
                $y = [int](($top + $bottom) / 2)
                Invoke-Device @('shell', 'input', 'tap', "$x", "$y") | Out-Null
                return
            }
        }
        $x = [int](($viewport.Left + $viewport.Right) / 2)
        $upper = $viewport.Top + 60
        $lower = $viewport.Bottom - 60
        $scrollUp = ($bounds -and $bounds.Bottom -le $viewport.Top -and $bounds.Bottom -gt $bounds.Top) -or $attempt -ge 6
        $from = if ($scrollUp) { $upper } else { $lower }
        $to = if ($scrollUp) { $lower } else { $upper }
        Invoke-Device @('shell', 'input', 'swipe', "$x", "$from", "$x", "$to", '350') | Out-Null
        Start-Sleep -Milliseconds 350
    }
    throw "UIAutomator 未能滚动到可触摸文本：$Text"
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
        '-n', 'mark.via.gp/mark.via.Trampoline', '-d', "'$url'")
    if (-not (($launch -join "`n").Contains('Status: ok'))) { throw 'Via 浏览器没有打开测试页面' }

    if ($Diagnostic) {
        $layoutSnapshot = Wait-PageReport 'layout-diagnostic' {
            param($report) $report.phase -eq 'diagnostic-ready'
        }
        $layoutSnapshot.Report | ConvertTo-Json -Depth 40 |
            Set-Content (Join-Path $outputDirectory 'via-layout-diagnostic.json') -Encoding utf8
        if ($layoutSnapshot.Report.initialOriginal.characters.Count -eq 0 -or
            $layoutSnapshot.Report.initialInspection.generation -lt 0 -or
            $layoutSnapshot.Report.reactOriginalGeometry.characters.Count -eq 0) {
            throw 'Via 实际布局诊断没有包含原文 Range、React 原文 Range 或生产 inspect 数据'
        }
        [ordered]@{
            timestamp = $layoutSnapshot.Report.capturedAt
            targetPackage = 'mark.via.gp'
            originalSegmentCount = $layoutSnapshot.Report.initialOriginal.productionSegments.Count
            reactSegmentCount = $layoutSnapshot.Report.reactOriginalGeometry.productionSegments.Count
            originalAnnotationCount = $layoutSnapshot.Report.initialInspection.annotationCount
            reactAnnotationCount = $layoutSnapshot.Report.reactOriginalInspection.annotationCount
            evidencePath = Join-Path $outputDirectory 'via-layout-diagnostic.json'
        } | ConvertTo-Json
        return
    }

    $initial = Wait-PageReport 'initial' { param($report) $report.originalRubyMatch -and $report.reactTranslationOnly }
    foreach ($pair in @('今日:きょう', '学校:がっこう', '日本語:にほんご', '勉強:べんきょう')) {
        if ($pair -notin $initial.Report.originalRubies) { throw "初始 Ruby 缺少范围：$pair" }
    }
    if ($initial.Report.originalText -ne '今日は学校で日本語を勉強します。' -or
        -not $initial.Report.layoutIsClear -or $initial.Report.visibleOriginalAnnotationCount -eq 0 -or
        -not $initial.Report.inputAndEditableExcluded -or -not $initial.Report.inputValuePreserved -or
        -not $initial.Report.editableTextPreserved -or -not $initial.Report.existingRubyPreserved) {
        throw '输入框、可编辑区域或原有 Ruby 排除检查失败'
    }

    Tap-VisibleText '显示日语原文'
    $reactOriginal = Wait-PageReport 'react-original' { param($report) $report.reactOriginalUpdated }
    Tap-VisibleText '显示中文译文'
    $reactTranslation = Wait-PageReport 'react-translation' { param($report) $report.reactTranslationOnly }
    Tap-VisibleText '更新 React 列表'
    $reactList = Wait-PageReport 'react-list' { param($report) $report.reactListUpdated }

    Tap-VisibleText '生成动态 Ruby 注音'
    $updated = Wait-PageReport 'updated' { param($report) $report.updateRequested -and $report.updatedRubyMatch }
    foreach ($pair in @('明日:あした', '図書館:としょかん', '行:い')) {
        if ($pair -notin $updated.Report.updatedRubies) { throw "触摸更新后的 Ruby 缺少范围：$pair" }
    }

    Tap-VisibleText 'Open link'
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
        -not $linked.Report.layoutIsClear -or
        -not $reactOriginal.Report.reactOriginalUpdated -or
        -not $reactTranslation.Report.reactTranslationOnly -or
        -not $reactList.Report.reactListUpdated -or
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
        reactTranslationNodePreserved = $reactTranslation.Report.react.postNodeIdentityPreserved
        reactTranslationParentPreserved = $reactTranslation.Report.react.postNodeParentPreserved
        reactTranslationTextUpdated = $reactTranslation.Report.react.postNodeData
        reactOriginalRubyPairs = @($reactOriginal.Report.reactAnnotationPairs)
        reactListNodePreserved = $reactList.Report.react.schoolNodeIdentityPreserved
        reactListRemovedNodeDisconnected = $reactList.Report.react.removedRowDisconnected
        reactListRubyPairs = @($reactList.Report.reactListAnnotationPairs)
        reactProduction = $reactList.Report.react.reactProduction
        rubyLayoutClear = $linked.Report.layoutIsClear
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
    Invoke-Device @('reverse', '--remove', 'tcp:18765') | Out-Null
}
