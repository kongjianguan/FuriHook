param(
    [string]$Sdk,
    [string]$Workspace,
    [string]$AvdName,
    [int]$Port,
    [string]$Ramdisk = '',
    [string]$SystemImageOverride = ''
)

$ErrorActionPreference = 'Stop'
$env:ANDROID_HOME = $Sdk
$env:ANDROID_AVD_HOME = Join-Path $Workspace 'avd'
$arguments = @('-avd', $AvdName, '-port', $Port, '-no-window', '-no-snapshot', '-noaudio', '-no-boot-anim', '-show-kernel', '-gpu', 'swiftshader_indirect', '-memory', '4096', '-cores', '4')
if ($Ramdisk) { $arguments += @('-ramdisk', $Ramdisk) }
if ($SystemImageOverride) { $arguments += @('-sysdir', $SystemImageOverride) }
& (Join-Path $Sdk 'emulator\emulator.exe') @arguments 1> (Join-Path $Workspace 'emulator-stdout.log') 2> (Join-Path $Workspace 'emulator-stderr.log')
exit $LASTEXITCODE
