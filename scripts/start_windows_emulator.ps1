param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JavaHome = 'C:\Program Files\Android\Android Studio\jbr',
    [string]$Workspace = 'D:\workspace\furihook-validation',
    [int]$Port = 5580,
    [string]$Ramdisk = ''
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $Sdk
$env:ANDROID_AVD_HOME = Join-Path $Workspace 'avd'
$env:PATH = "$Sdk\platform-tools;$JavaHome\bin;$env:PATH"
$emulator = Join-Path $Sdk 'emulator\emulator.exe'
$avdmanager = Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$name = 'FuriHook_API35'
$serial = "emulator-$Port"
New-Item -ItemType Directory -Force $Workspace, $env:ANDROID_AVD_HOME | Out-Null

& $emulator -accel-check
if ($LASTEXITCODE -ne 0) { throw 'Android Emulator 的硬件加速不可用' }
if (-not (Test-Path "$Sdk\system-images\android-35\default\x86_64\system.img")) {
    throw '缺少官方 system-images;android-35;default;x86_64 镜像'
}
if (-not (Test-Path "$env:ANDROID_AVD_HOME\$name.avd\config.ini")) {
    'no' | & $avdmanager create avd --name $name --package 'system-images;android-35;default;x86_64' --device pixel_2
    if ($LASTEXITCODE -ne 0) { throw '创建 FuriHook AVD 失败' }
}
$devices = & $adb devices
if ($devices -match "^$serial\s+device$") {
    throw "端口 $Port 已存在运行的模拟器，请检查归属后继续"
}
if (Get-NetTCPConnection -LocalPort $Port, ($Port + 1) -State Listen -ErrorAction SilentlyContinue) {
    throw "模拟器端口 $Port 已被占用"
}
$worker = Join-Path $PSScriptRoot 'windows_emulator_worker.ps1'
if (-not (Test-Path $worker)) { throw '缺少 windows_emulator_worker.ps1' }
$taskName = "FuriHook-Emulator-$Port"
$taskArguments = "-NoProfile -File `"$worker`" -Sdk `"$Sdk`" -Workspace `"$Workspace`" -Port $Port"
if ($Ramdisk) {
    if (-not (Test-Path $Ramdisk)) { throw "指定的 ramdisk 文件不存在：$Ramdisk" }
    $taskArguments += " -Ramdisk `"$Ramdisk`""
}
$existingTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($existingTask -and -not $existingTask.Actions.Arguments.Contains("-File `"$worker`"")) {
    throw "计划任务 $taskName 已存在且执行内容不同"
}
$action = New-ScheduledTaskAction -Execute (Get-Command pwsh).Source -Argument $taskArguments
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $taskName
$deadline = [DateTime]::UtcNow.AddMinutes(6)
$booted = $false
while ([DateTime]::UtcNow -lt $deadline) {
    $task = Get-ScheduledTask -TaskName $taskName
    if ($task.State -ne 'Running' -and (Get-ScheduledTaskInfo -TaskName $taskName).LastTaskResult -ne 0) {
        throw "模拟器计划任务退出，查看 $Workspace 中的日志"
    }
    $boot = & $adb -s $serial shell getprop sys.boot_completed 2>$null
    if ($boot -eq '1') { $booted = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $booted) { throw '模拟器在六分钟内没有完成启动' }
& $adb -s $serial shell settings put global window_animation_scale 0
& $adb -s $serial shell settings put global transition_animation_scale 0
& $adb -s $serial shell settings put global animator_duration_scale 0
& $adb -s $serial shell input keyevent 82
$report = [ordered]@{
    hostName = $env:COMPUTERNAME
    avd = $name
    serial = $serial
    taskName = $taskName
    sdk = $Sdk
    workspace = $Workspace
    ramdisk = $Ramdisk
    apiLevel = ((& $adb -s $serial shell getprop ro.build.version.sdk) -join '').Trim()
    bootCompleted = $true
    hookExecutionChecked = $false
}
$report | ConvertTo-Json | Set-Content "$Workspace\emulator-start.json" -Encoding utf8
$report | ConvertTo-Json
