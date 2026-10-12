param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Workspace = 'D:\workspace\furihook-validation-armbridge',
    [int]$Port = 5582,
    [string]$Ramdisk = '',
    [string]$SystemImageOverride = '',
    [string]$AvdName = 'FuriHook_API35_ARMBridge'
)

$ErrorActionPreference = 'Stop'
$name = $AvdName
$serial = "emulator-$Port"
$avdHome = Join-Path $Workspace 'avd'
$image = Join-Path $Sdk 'system-images\android-35\google_apis\x86_64'
$emulator = Join-Path $Sdk 'emulator\emulator.exe'
$avdmanager = Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$worker = Join-Path $PSScriptRoot 'windows_armbridge_emulator_worker.ps1'
$taskName = "FuriHook-ARMBridge-$Port"

if ((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory -lt (8 * 1024 * 1024)) {
    throw 'Available memory is below 8 GiB; refusing to create the test emulator'
}
if (-not (Test-Path (Join-Path $image 'system.img'))) { throw 'API35 Google APIs x86_64 system image is missing' }
$props = Get-Content (Join-Path $image 'build.prop') -Raw
if (-not $props.Contains('ro.dalvik.vm.native.bridge=libndk_translation.so') -or
    -not $props.Contains('ro.system.product.cpu.abilist64=x86_64,arm64-v8a')) {
    throw 'Google APIs image does not declare arm64-v8a native bridge support'
}
if (-not (Test-Path $worker)) { throw 'Isolated ARMBridge emulator worker script is missing' }
if (Get-NetTCPConnection -LocalPort $Port, ($Port + 1) -State Listen -ErrorAction SilentlyContinue) {
    throw "Emulator port $Port or $($Port + 1) is already in use"
}
if ((& $adb devices) -match "^$serial\s+device$") { throw "Device $serial is already running; refusing to replace it" }
if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
    throw "Scheduled task $taskName already exists; refusing to replace it"
}
if ($Ramdisk -and -not (Test-Path $Ramdisk)) { throw "Isolated Magisk ramdisk is missing: $Ramdisk" }

New-Item -ItemType Directory -Force $Workspace, $avdHome, (Join-Path $Workspace 'results') | Out-Null
$env:ANDROID_AVD_HOME = $avdHome
if (-not (Test-Path (Join-Path $avdHome "$name.avd\config.ini"))) {
    'no' | & $avdmanager create avd --name $name --package 'system-images;android-35;google_apis;x86_64' --device pixel_2
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated Google APIs AVD' }
}
$taskArguments = "-NoProfile -File `"$worker`" -Sdk `"$Sdk`" -Workspace `"$Workspace`" -AvdName `"$name`" -Port $Port"
if ($Ramdisk) { $taskArguments += " -Ramdisk `"$Ramdisk`"" }
if ($SystemImageOverride) { $taskArguments += " -SystemImageOverride `"$SystemImageOverride`"" }
$action = New-ScheduledTaskAction -Execute (Get-Command pwsh).Source -Argument $taskArguments
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Settings $settings | Out-Null
Start-ScheduledTask -TaskName $taskName

$deadline = [DateTime]::UtcNow.AddMinutes(8)
do {
    Start-Sleep -Seconds 2
    $deviceRows = @(& $adb devices)
    if ($LASTEXITCODE -ne 0) { throw 'Could not read ADB device state' }
    if ($deviceRows -match "^$serial\s+device$") {
        $boot = (& $adb -s $serial shell getprop sys.boot_completed 2>$null) -join ''
    } else {
        $boot = ''
    }
    if ($boot.Trim() -eq '1') { break }
    $task = Get-ScheduledTask -TaskName $taskName
    if ($task.State -ne 'Running' -and (Get-ScheduledTaskInfo -TaskName $taskName).LastTaskResult -ne 0) {
        throw "Isolated emulator task exited; inspect $Workspace\emulator-stderr.log"
    }
} while ([DateTime]::UtcNow -lt $deadline)
if ($boot.Trim() -ne '1') { throw 'ARMBridge emulator did not finish booting within eight minutes' }

$bridge = ((& $adb -s $serial shell getprop ro.dalvik.vm.native.bridge) -join '').Trim()
$abis = ((& $adb -s $serial shell getprop ro.product.cpu.abilist64) -join '').Trim()
if ($bridge -ne 'libndk_translation.so' -or $abis -notmatch 'arm64-v8a') {
    throw "Booted system properties lack expected ARM translation support: bridge=$bridge; abi=$abis"
}
& $adb -s $serial shell settings put global window_animation_scale 0
& $adb -s $serial shell settings put global transition_animation_scale 0
& $adb -s $serial shell settings put global animator_duration_scale 0
& $adb -s $serial shell input keyevent 82
$report = [ordered]@{
    hostName = $env:COMPUTERNAME
    avd = $name
    serial = $serial
    taskName = $taskName
    apiLevel = ((& $adb -s $serial shell getprop ro.build.version.sdk) -join '').Trim()
    nativeBridge = $bridge
    abiList64 = $abis
    bootCompleted = $true
}
$report | ConvertTo-Json | Set-Content (Join-Path $Workspace 'armbridge-start.json') -Encoding utf8
$report | ConvertTo-Json
