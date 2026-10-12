param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Workspace = 'D:\workspace\furihook-validation-armbridge',
    [string]$RootAvdCommit = '0043bd2d8f8479850ee466fd8751e7273c976a7e',
    [int]$Port = 5584
)

$ErrorActionPreference = 'Stop'
$serial = "emulator-$Port"
$avdName = 'FuriHook_API35_RootAVD'
$sourceImage = Join-Path $Sdk 'system-images\android-35\google_apis\x86_64'
$run = Join-Path $Workspace 'rootavd-validation'
$isolatedSdk = Join-Path $run 'sdk'
$image = Join-Path $isolatedSdk 'system-images\android-35\google_apis\x86_64'
$avdHome = Join-Path $run 'avd'
$rootAvd = Join-Path $run 'rootAVD'
$rootAvdProxyBatch = Join-Path $run 'rootAVD-proxy.bat'
$results = Join-Path $run 'results'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$avdmanager = Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat'
$startScript = Join-Path $PSScriptRoot 'start_windows_armbridge_emulator.ps1'
$rootAvdArchive = Join-Path $run 'rootAVD-source.zip'
$taskName = "FuriHook-ARMBridge-$Port"
$deviceLines = @(& $adb devices)
$emulatorReady = $false
if ($deviceLines -match "^$serial\s+device$") {
    $bootState = ((& $adb -s $serial shell getprop sys.boot_completed) -join '').Trim()
    $emulatorReady = $bootState -eq '1'
}
$existingTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue

if ((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory -lt (8 * 1024 * 1024)) {
    throw 'Available memory is below 8 GiB'
}
if ((Get-PSDrive -Name D).Free -lt (15GB)) { throw 'Drive D has less than 15 GiB free' }
if (-not (Test-Path (Join-Path $sourceImage 'system.img'))) { throw 'Original API35 Google APIs system image is missing' }
if ((Test-Path $run) -and -not (Test-Path (Join-Path $image 'system.img'))) {
    throw "Isolated test directory exists without a complete image copy: $run"
}
if (-not $emulatorReady -and (Get-NetTCPConnection -LocalPort $Port, ($Port + 1) -State Listen -ErrorAction SilentlyContinue)) {
    throw "Port $Port or $($Port + 1) is already in use"
}
if ($existingTask -and -not $emulatorReady) {
    throw "Scheduled task FuriHook-ARMBridge-$Port already exists"
}

if ($LASTEXITCODE -ne 0 -or -not ($deviceLines -match '^emulator-5580\s+device$') -or
    -not ($deviceLines -match '^emulator-5582\s+device$')) {
    throw 'Both emulator-5580 and stock emulator-5582 must remain online'
}

$originalHashes = [ordered]@{}
foreach ($name in @('ramdisk.img', 'kernel-ranchu', 'system.img', 'build.prop')) {
    $originalHashes[$name] = (Get-FileHash (Join-Path $sourceImage $name) -Algorithm SHA256).Hash.ToLower()
}

New-Item -ItemType Directory -Force $run, $results | Out-Null
if (-not (Test-Path (Join-Path $image 'system.img'))) { Copy-Item $sourceImage $image -Recurse }
foreach ($name in $originalHashes.Keys) {
    $copyHash = (Get-FileHash (Join-Path $image $name) -Algorithm SHA256).Hash.ToLower()
    if ($copyHash -ne $originalHashes[$name]) {
        $backupPath = Join-Path $image "$name.backup"
        if (-not (Test-Path $backupPath)) { throw "Isolated image copy hash mismatch without backup: $name" }
        $backupHash = (Get-FileHash $backupPath -Algorithm SHA256).Hash.ToLower()
        if ($backupHash -ne $originalHashes[$name]) { throw "Isolated image backup hash mismatch: $name" }
    }
}
if (-not (Test-Path $rootAvdArchive)) { throw "Upload the pinned rootAVD source archive first: $rootAvdArchive" }
$archiveHash = (Get-FileHash $rootAvdArchive -Algorithm SHA256).Hash.ToLower()
if ($archiveHash -ne '61af189b04c93761657d15d95ac7bad37b391c7de98f14494243c4f51d446808') {
    throw "Pinned rootAVD archive SHA-256 mismatch: $archiveHash"
}
if (Test-Path $rootAvd) {
    if (-not (Test-Path (Join-Path $rootAvd 'rootAVD.sh'))) {
        Remove-Item $rootAvd -Recurse -Force
        Expand-Archive -Path $rootAvdArchive -DestinationPath $run
    }
} else {
    Expand-Archive -Path $rootAvdArchive -DestinationPath $run
}
if (-not (Test-Path (Join-Path $rootAvd 'rootAVD.bat.official'))) {
    Copy-Item (Join-Path $rootAvd 'rootAVD.bat') (Join-Path $rootAvd 'rootAVD.bat.official')
}
$rootAvdScriptHash = (Get-FileHash (Join-Path $rootAvd 'rootAVD.sh') -Algorithm SHA256).Hash.ToLower()
$rootAvdBatchHash = (Get-FileHash (Join-Path $rootAvd 'rootAVD.bat.official') -Algorithm SHA256).Hash.ToLower()
if ($rootAvdScriptHash -ne '7eae0f1c2c9bf969d80cfb3f54fc128bacb72a605fc7bff0b3e8f38930104328' -or
    $rootAvdBatchHash -ne '780c975e1364df19fbd2a129b11869d6f26d87d036403d8aa5245749b272b8a1') {
    throw 'Pinned rootAVD source file hash mismatch'
}
if (-not (Test-Path $rootAvdProxyBatch)) { throw "Upload the proxy transport wrapper first: $rootAvdProxyBatch" }
$proxyBatchHash = (Get-FileHash $rootAvdProxyBatch -Algorithm SHA256).Hash.ToLower()
if ($proxyBatchHash -ne '1cdeca0bf06cc12323438c8243b560ab81a4bde5989c29d8a53876f69addde8a') {
    throw "rootAVD proxy wrapper SHA-256 mismatch: $proxyBatchHash"
}
$rootAvdBatchPath = Join-Path $rootAvd 'rootAVD.bat'
$currentRootAvdBatchHash = (Get-FileHash $rootAvdBatchPath -Algorithm SHA256).Hash.ToLower()
if ($currentRootAvdBatchHash -ne $rootAvdBatchHash) {
    Copy-Item (Join-Path $rootAvd 'rootAVD.bat.official') $rootAvdBatchPath -Force
}
if ((Get-FileHash $rootAvdBatchPath -Algorithm SHA256).Hash.ToLower() -ne $rootAvdBatchHash) {
    throw 'Official rootAVD.bat was not restored from its pinned copy'
}
$actualCommit = $RootAvdCommit
$magiskArtifact = Join-Path $rootAvd 'Magisk30.7.zip'
$magiskZip = Join-Path $rootAvd 'Magisk.zip'
$magiskOriginalZip = Join-Path $rootAvd 'Magisk.zip.official'
if (-not (Test-Path $magiskArtifact)) { throw 'Pinned commit does not contain the Magisk 30.7 artifact' }
if (-not (Test-Path $magiskOriginalZip)) { Copy-Item $magiskZip $magiskOriginalZip }
$magiskOriginalHash = (Get-FileHash $magiskOriginalZip -Algorithm SHA256).Hash.ToLower()
if ($magiskOriginalHash -ne '543a96fe26c012d99baf3a3aa5a97b80508d67cc641af7c12ce9f7b226b2b889') {
    throw "Pinned original Magisk.zip hash mismatch: $magiskOriginalHash"
}
$magiskHash = (Get-FileHash $magiskArtifact -Algorithm SHA256).Hash.ToLower()
if ($magiskHash -ne 'e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5') {
    throw "Magisk 30.7 artifact hash mismatch: $magiskHash"
}
Copy-Item $magiskArtifact $magiskZip -Force
$appsPath = Join-Path $rootAvd 'Apps'
if (Test-Path $appsPath) {
    if (-not (Get-Item $appsPath).PSIsContainer) { throw "RootAVD Apps path is not a directory: $appsPath" }
} else {
    New-Item -ItemType Directory $appsPath | Out-Null
}

$currentRamdiskHash = (Get-FileHash (Join-Path $image 'ramdisk.img') -Algorithm SHA256).Hash.ToLower()
if ($currentRamdiskHash -ne $originalHashes['ramdisk.img']) {
    $restoreLog = Join-Path $results 'restore-isolated-ramdisk.log'
    $env:ANDROID_HOME = $isolatedSdk
    $env:PATH = "$(Join-Path $Sdk 'platform-tools');$env:PATH"
    Push-Location $rootAvd
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & .\rootAVD.bat 'system-images\android-35\google_apis\x86_64\ramdisk.img' restore *> $restoreLog
        $restoreExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Pop-Location
    }
    if ($restoreExit -ne 0) { throw "Could not restore isolated ramdisk; see $restoreLog" }
    $currentRamdiskHash = (Get-FileHash (Join-Path $image 'ramdisk.img') -Algorithm SHA256).Hash.ToLower()
    if ($currentRamdiskHash -ne $originalHashes['ramdisk.img']) { throw 'Isolated ramdisk did not match the original after restore' }
}

$env:ANDROID_HOME = $Sdk
$env:ANDROID_AVD_HOME = $avdHome
$avdConfig = Join-Path $avdHome "$avdName.avd\config.ini"
if (-not (Test-Path $avdConfig)) {
    'no' | & $avdmanager create avd --name $avdName --package 'system-images;android-35;google_apis;x86_64' --device pixel_2
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated API35 AVD' }
}
if (-not $emulatorReady) {
    & $startScript -Sdk $Sdk -Workspace $run -Port $Port -SystemImageOverride $image -AvdName $avdName
    if ($LASTEXITCODE -ne 0) { throw 'Could not boot API35 AVD from isolated system image' }
}

$env:ANDROID_SERIAL = $serial
$selectedSerial = ((& $adb get-serialno) -join '').Trim()
if ($LASTEXITCODE -ne 0 -or $selectedSerial -ne $serial) {
    throw "ANDROID_SERIAL did not select ${serial}: $selectedSerial"
}
$proxyBefore = ((& $adb -s $serial shell settings get global http_proxy) -join '').Trim()
& $adb -s $serial shell settings put global http_proxy '10.0.2.2:18890'
if ($LASTEXITCODE -ne 0) { throw 'Could not set temporary proxy on the isolated device' }

$rootAvdLog = Join-Path $results ("rootAVD-patch-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
$env:ANDROID_HOME = $isolatedSdk
$env:PATH = "$(Join-Path $Sdk 'platform-tools');$env:PATH"
Push-Location $rootAvd
$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    & $rootAvdProxyBatch 'system-images\android-35\google_apis\x86_64\ramdisk.img' *> $rootAvdLog
    $rootAvdExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
    Pop-Location
    $currentState = ((& $adb -s $serial get-state 2>$null) -join '').Trim()
    if ($currentState -eq 'device') {
        if ($proxyBefore -eq 'null' -or $proxyBefore -eq '' -or $proxyBefore -eq ':0') {
            & $adb -s $serial shell settings delete global http_proxy | Out-Null
        } else {
            & $adb -s $serial shell settings put global http_proxy $proxyBefore | Out-Null
        }
    }
}
if ($rootAvdExit -ne 0) { throw "rootAVD failed; see $rootAvdLog" }

$task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($task) {
    if (-not $task.Actions[0].Arguments.Contains($run)) { throw 'Scheduled task does not point to the isolated AVD' }
    if ($task.State -eq 'Running') { Stop-ScheduledTask -TaskName $taskName }
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
}
$portDeadline = [DateTime]::UtcNow.AddMinutes(2)
while ((Get-NetTCPConnection -LocalPort $Port, ($Port + 1) -State Listen -ErrorAction SilentlyContinue) -and
    [DateTime]::UtcNow -lt $portDeadline) {
    Start-Sleep -Seconds 2
}
if (Get-NetTCPConnection -LocalPort $Port, ($Port + 1) -State Listen -ErrorAction SilentlyContinue) {
    throw "Emulator port $Port is still listening after rootAVD finished"
}
& $startScript -Sdk $Sdk -Workspace $run -Port $Port -SystemImageOverride $image -AvdName $avdName
if ($LASTEXITCODE -ne 0) { throw 'Could not boot the patched isolated AVD' }
& $adb -s $serial shell settings delete global http_proxy | Out-Null
$proxyAfter = ((& $adb -s $serial shell settings get global http_proxy) -join '').Trim()
if ($proxyAfter -notin @('null', '', ':0')) { throw "Temporary proxy was not restored: $proxyAfter" }

foreach ($name in $originalHashes.Keys) {
    $afterHash = (Get-FileHash (Join-Path $sourceImage $name) -Algorithm SHA256).Hash.ToLower()
    if ($afterHash -ne $originalHashes[$name]) { throw "Original Google APIs image hash changed: $name" }
}
if (-not (Test-Path (Join-Path $image 'ramdisk.img.backup'))) { throw 'rootAVD did not create the isolated ramdisk backup' }
$currentKernelHash = (Get-FileHash (Join-Path $image 'kernel-ranchu') -Algorithm SHA256).Hash.ToLower()
$kernelBackupPath = Join-Path $image 'kernel-ranchu.backup'
$kernelBackupHash = $null
if (Test-Path $kernelBackupPath) {
    $kernelBackupHash = (Get-FileHash $kernelBackupPath -Algorithm SHA256).Hash.ToLower()
    if ($kernelBackupHash -ne $originalHashes['kernel-ranchu']) { throw 'Isolated kernel backup does not match the original' }
} elseif ($currentKernelHash -ne $originalHashes['kernel-ranchu']) {
    throw 'Isolated kernel changed without a verified original backup'
}

$report = [ordered]@{
    capturedAt = [DateTimeOffset]::Now.ToString('o')
    rootAvdCommit = $actualCommit
    rootAvdArchiveSha256 = $archiveHash
    rootAvdOfficialBatchSha256 = $rootAvdBatchHash
    rootAvdProxyBatchSha256 = $proxyBatchHash
    rootAvdExecutedBatch = [IO.Path]::GetFileName($rootAvdProxyBatch)
    rootAvdOriginalMagiskZipSha256 = $magiskOriginalHash
    rootAvdSelectedMagiskArtifact = 'Magisk30.7.zip'
    rootAvdMagiskArtifactSha256 = $magiskHash
    serial = $serial
    androidSerialTarget = $selectedSerial
    isolatedImage = $image
    rootAvdExitCode = $rootAvdExit
    originalImageHashesBefore = $originalHashes
    originalImageHashesAfter = [ordered]@{}
    isolatedRamdiskBackup = (Get-FileHash (Join-Path $image 'ramdisk.img.backup') -Algorithm SHA256).Hash.ToLower()
    isolatedKernelBackup = $kernelBackupHash
    isolatedKernelCurrent = $currentKernelHash
    proxyBefore = $proxyBefore
    proxyRestored = $proxyAfter
    rootAvdLog = $rootAvdLog
    rootAvdInstalledManager = 'Magisk 30.7'
}
foreach ($name in $originalHashes.Keys) {
    $report.originalImageHashesAfter[$name] = (Get-FileHash (Join-Path $sourceImage $name) -Algorithm SHA256).Hash.ToLower()
}
$report | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $results 'rootavd-install-report.json') -Encoding utf8
$report | ConvertTo-Json -Depth 5
