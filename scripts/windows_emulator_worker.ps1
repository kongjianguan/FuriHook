param(
    [string]$Sdk,
    [string]$Workspace,
    [int]$Port,
    [string]$Ramdisk = ''
)

$ErrorActionPreference = 'Stop'
$env:ANDROID_HOME = $Sdk
$env:ANDROID_AVD_HOME = Join-Path $Workspace 'avd'
$arguments = @('-avd', 'FuriHook_API35', '-port', $Port, '-no-window', '-no-snapshot', '-noaudio', '-no-boot-anim', '-gpu', 'swiftshader_indirect', '-memory', '3072', '-cores', '4')
if ($Ramdisk) { $arguments += @('-ramdisk', $Ramdisk) }
& "$Sdk\emulator\emulator.exe" @arguments 1> "$Workspace\emulator-stdout.log" 2> "$Workspace\emulator-stderr.log"
exit $LASTEXITCODE
