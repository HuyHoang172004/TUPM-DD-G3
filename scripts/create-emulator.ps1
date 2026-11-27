param([switch]$InstallTools)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) {
    $javaCandidates = Get-ChildItem -Path "$env:USERPROFILE\.vscode\extensions\redhat.java-*\jre\*\bin\java.exe" -ErrorAction SilentlyContinue
    if ($javaCandidates) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaCandidates[-1].FullName) }
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17 or 21 installation.' }
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = if (Test-Path -LiteralPath "$projectRoot\.lab\android-sdk") { "$projectRoot\.lab\android-sdk" } else { "$env:LOCALAPPDATA\Android\Sdk" }
}
$tools = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin'
$image = 'system-images;android-35;default;x86_64'
if ($InstallTools) {
    & "$tools\sdkmanager.bat" "--sdk_root=$env:ANDROID_HOME" 'emulator' $image
    if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed.' }
}
$emulatorExe = Join-Path $env:ANDROID_HOME 'emulator/emulator.exe'
if (-not (Test-Path -LiteralPath $emulatorExe)) { throw 'Install SDK emulator/system image with -InstallTools or Android Studio SDK Manager.' }
$env:ANDROID_AVD_HOME = Join-Path $projectRoot '.lab/avd'
New-Item -ItemType Directory -Path $env:ANDROID_AVD_HOME -Force | Out-Null
$avdPath = Join-Path $env:ANDROID_AVD_HOME 'AdaptiveNewsLab.avd'
if (-not (Test-Path -LiteralPath "$env:ANDROID_AVD_HOME\AdaptiveNewsLab.ini")) {
    'no' | & "$tools\avdmanager.bat" create avd --name AdaptiveNewsLab --package $image --path $avdPath --device pixel_5
    if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed.' }
}
# Visible window is intentional: this is the interactive emulator to share as evidence.
Start-Process -FilePath $emulatorExe -ArgumentList @('-avd','AdaptiveNewsLab','-memory','2048','-no-snapshot','-no-audio','-no-boot-anim') -WorkingDirectory $projectRoot -PassThru
Write-Host 'Wait for Android to boot, then run build-benchmark.ps1 -DeviceSerial emulator-5554.'
