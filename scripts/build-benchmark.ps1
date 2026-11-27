param([string]$DeviceSerial = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\jlink.exe")) {
    $workspaceJdk = Get-ChildItem -Path "$projectRoot\.lab\jdk\*\bin\jlink.exe" -ErrorAction SilentlyContinue
    if ($workspaceJdk) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $workspaceJdk[-1].FullName) }
}
if (-not $env:JAVA_HOME) {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaCommand.Source) }
    else {
        $javaCandidates = Get-ChildItem -Path "$env:USERPROFILE\.vscode\extensions\redhat.java-*\jre\*\bin\java.exe" -ErrorAction SilentlyContinue
        if ($javaCandidates) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaCandidates[-1].FullName) }
    }
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 or 21.' }
if (-not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\jlink.exe")) { throw 'JAVA_HOME must point to a complete JDK with jlink, not the trimmed VS Code Java runtime.' }
if (-not $env:ANDROID_HOME -and (Test-Path -LiteralPath "$projectRoot\.lab\android-sdk\platforms\android-36")) {
    $env:ANDROID_HOME = "$projectRoot\.lab\android-sdk"
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path -LiteralPath "$env:ANDROID_HOME\platforms\android-36\android.jar")) {
    throw 'Install Android SDK platform 36 and build-tools 36.0.0, then set ANDROID_HOME.'
}
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.lab/gradle'
Push-Location -LiteralPath (Join-Path $projectRoot 'android')
try {
    & .\gradlew.bat :app:assembleBenchmark :benchmark:assembleBenchmark :app:testDebugUnitTest --console=plain --no-daemon --max-workers=1 '-Dorg.gradle.vfs.watch=false' | Tee-Object -FilePath (Join-Path $projectRoot '.lab/last-android-build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Android build or unit tests failed.' }
    if ($DeviceSerial) {
        if ($DeviceSerial -notmatch '^emulator-\d+$') { throw 'Select an Android emulator serial.' }
        $adbExe = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
        & $adbExe -s $DeviceSerial install -r app/build/outputs/apk/benchmark/app-benchmark.apk
        if ($LASTEXITCODE -ne 0) { throw 'Install app APK failed.' }
        & $adbExe -s $DeviceSerial install -r benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk
        if ($LASTEXITCODE -ne 0) { throw 'Install benchmark APK failed.' }
    }
} finally { Pop-Location }
