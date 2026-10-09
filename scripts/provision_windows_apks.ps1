param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Workspace = 'D:\workspace\furihook-validation',
    [string]$Serial = 'emulator-5580'
)

$ErrorActionPreference = 'Stop'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'

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

$deviceName = (Invoke-Device @('emu', 'avd', 'name'))[0].Trim()
if ($deviceName -ne 'FuriHook_API35') { throw '重新安装仅允许使用 FuriHook 专用测试虚拟设备' }
if ((Read-Vector 'status').data.'API Version' -ne 102) { throw '测试框架没有报告 API 102' }
$apks = @('app-debug.apk', 'testapp-debug.apk', 'testapp-debug-androidTest.apk')
foreach ($apk in $apks) {
    if (-not (Test-Path (Join-Path $Workspace $apk))) { throw "缺少云端构建 APK：$apk" }
}

# 独立云端构建的 Debug 签名不同，专用设备每次重新安装全部项目包。
$installed = @(Invoke-Device @('shell', 'pm', 'list', 'packages'))
foreach ($package in @('dev.furihook.testapp.test', 'dev.furihook.testapp', 'dev.furihook')) {
    if ("package:$package" -in $installed) {
        Invoke-Device @('uninstall', $package) | Write-Output
    }
}
foreach ($apk in $apks) {
    Invoke-Device @('install', (Join-Path $Workspace $apk)) | Write-Output
}
Read-Vector 'modules enable dev.furihook' | ConvertTo-Json -Depth 5 | Write-Output
Read-Vector 'scope set dev.furihook dev.furihook.testapp/0' | ConvertTo-Json -Depth 5 | Write-Output
Read-Vector 'scope ls dev.furihook' | ConvertTo-Json -Depth 5 | Write-Output
