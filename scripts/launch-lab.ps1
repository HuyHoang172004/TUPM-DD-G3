param(
    [switch]$NoBrowser,
    [switch]$SetupOnly,
    [switch]$Refresh,
    [ValidateRange(30, 900)][int]$BootTimeoutSeconds = 300
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot/lab-bootstrap.ps1"
Initialize-LabWorkspace $projectRoot
$url = 'http://127.0.0.1:8765'
$lock = $null
$transcribing = $false
$originalEnvironment = @{}
$environmentNames = @('PATH','JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','LAB_ADB','LAB_TRACE_PROCESSOR',
    'ADAPTIVE_NEWS_DATA_DIR','GRADLE_USER_HOME','PIP_CACHE_DIR','UV_CACHE_DIR','UV_PYTHON_INSTALL_DIR',
    'UV_PYTHON_INSTALL_BIN','UV_PYTHON_INSTALL_REGISTRY','PLAYWRIGHT_BROWSERS_PATH','TEMP','TMP','PYTHONUTF8')
foreach ($name in $environmentNames) { $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location -LiteralPath $projectRoot
try {
    if (-not [Environment]::Is64BitOperatingSystem -or $env:OS -ne 'Windows_NT' -or
        $env:PROCESSOR_ARCHITECTURE -eq 'ARM64' -or $env:PROCESSOR_ARCHITEW6432 -eq 'ARM64') {
        throw 'This launcher supports Intel/AMD Windows x64. The Android x86_64 emulator requires hardware virtualization.'
    }
    $instance = $null
    try { $instance = Invoke-RestMethod "$url/api/instance" -TimeoutSec 2 } catch {}
    if ($instance -and $instance.app -eq 'adaptive-news-performance-lab') {
        if ([IO.Path]::GetFullPath($instance.workspace).TrimEnd('\') -ne $script:LabWorkspace) {
            throw 'Port 8765 belongs to another checkout. Stop that Lab before starting this one.'
        }
        Write-Host "Lab is already running: $url"
        if (-not $NoBrowser -and -not $SetupOnly) {
            $browser = Get-LabBrowser ''
            if ($browser) { Start-Process -FilePath $browser -ArgumentList @('--new-window',$url) | Out-Null }
            else { Start-Process $url | Out-Null }
        }
        return
    }
    try { $lock = [IO.File]::Open((Join-Path $script:LabCache 'launcher.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Another launcher is preparing or running this checkout. Use its window and wait for setup to finish.' }
    $logDirectory = Join-Path $script:LabCache 'logs'
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    $logPath = Join-Path $logDirectory ('launcher-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
    Start-Transcript -Path $logPath | Out-Null
    $transcribing = $true
    Write-Host 'Adaptive News Performance Lab - automatic setup and launch' -ForegroundColor Cyan
    Write-Host "Workspace: $projectRoot"
    Write-Host 'The first run downloads tools and dependencies. Later runs reuse .lab.'
    foreach ($port in @(8000,8001,8765)) {
        $listener = New-Object Net.Sockets.TcpClient
        try {
            $connection = $listener.ConnectAsync('127.0.0.1', $port)
            try { $connected = $connection.Wait(500) -and $listener.Connected } catch { $connected = $false }
            if ($connected) { throw "Port $port is in use. Stop its existing service before launching the Lab; no unrelated process will be terminated." }
        } finally { $listener.Dispose() }
    }
    if (-not (Test-Path -LiteralPath "$script:LabCache/android-sdk/system-images/android-35/default/x86_64/system.img")) {
        $drive = New-Object IO.DriveInfo([IO.Path]::GetPathRoot($projectRoot))
        if ($drive.AvailableFreeSpace -lt 12GB) { throw 'The first Android setup needs at least 12 GB of free disk space on the project drive.' }
    }
    $state = Read-LabState
    New-Item -ItemType Directory -Path "$script:LabCache/tmp" -Force | Out-Null
    $env:TEMP = "$script:LabCache/tmp"
    $env:TMP = $env:TEMP
    $env:PYTHONUTF8 = '1'
    $env:PIP_CACHE_DIR = "$script:LabCache/pip-cache"
    $env:GRADLE_USER_HOME = "$script:LabCache/gradle"
    $env:ADAPTIVE_NEWS_DATA_DIR = "$projectRoot/backend/generated"

    Write-Host '[1/8] Python and Node ...' -ForegroundColor Cyan
    $python = Ensure-LabPython
    $node = Ensure-LabNode
    Write-Host '[2/8] Complete JDK and Android SDK ...' -ForegroundColor Cyan
    Ensure-LabJdk
    Ensure-LabSdk

    Write-Host '[3/8] Python dependencies ...' -ForegroundColor Cyan
    $venvPython = "$script:LabCache/venv/Scripts/python.exe"
    if (-not (Test-LabPython $venvPython)) {
        if (Test-Path -LiteralPath "$script:LabCache/venv") {
            Move-LabItem "$script:LabCache/venv" ("$script:LabCache/venv.previous-" + [guid]::NewGuid().ToString('N'))
        }
        Invoke-LabNative $python @('-m','venv',"$script:LabCache/venv")
    }
    $dependencyKey = Get-LabFingerprint @("$projectRoot/backend/requirements.txt","$projectRoot/lab/requirements.txt")
    $dependencies = Invoke-LabCapture $venvPython @('-m','lab.bootstrap','dependencies') -AllowFailure
    if ($Refresh -or $state.python_dependencies -ne $dependencyKey -or $dependencies.Code -ne 0) {
        Invoke-LabNative $venvPython @('-m','pip','install','--disable-pip-version-check','-r','backend/requirements.txt','-r','lab/requirements.txt')
        Invoke-LabNative $venvPython @('-m','pip','check')
        Invoke-LabNative $venvPython @('-m','lab.bootstrap','dependencies')
        $state.python_dependencies = $dependencyKey
        Save-LabState $state
    } else { Write-Host 'Python dependencies are up to date.' }

    Write-Host '[4/8] Web dependencies and dashboard build ...' -ForegroundColor Cyan
    $packageKey = (Get-LabFingerprint @("$projectRoot/dashboard/package.json","$projectRoot/dashboard/package-lock.json")) + ':' + (Invoke-LabCapture $node.Node @('--version')).Out
    Push-Location -LiteralPath "$projectRoot/dashboard"
    try {
        if ($Refresh -or $state.web_packages -ne $packageKey -or -not (Test-Path -LiteralPath 'node_modules/vite/bin/vite.js')) {
            Invoke-LabNative $node.Npm @('ci','--cache',"$script:LabCache/npm-cache",'--no-audit','--no-fund')
            $state.web_packages = $packageKey
            Save-LabState $state
        } else { Write-Host 'Web dependencies are up to date.' }
        $webFiles = @((Get-ChildItem -LiteralPath "$projectRoot/dashboard/src" -Recurse -File).FullName)
        $webFiles += @("$projectRoot/dashboard/index.html","$projectRoot/dashboard/vite.config.ts","$projectRoot/dashboard/tsconfig.json","$projectRoot/dashboard/package-lock.json")
        $webKey = (Get-LabFingerprint $webFiles) + ':' + $packageKey
        if ($Refresh -or $state.web_build -ne $webKey -or -not (Test-Path -LiteralPath 'dist/index.html')) {
            Invoke-LabNative $node.Npm @('run','build')
            $state.web_build = $webKey
            Save-LabState $state
        } else { Write-Host 'Dashboard build is up to date.' }
    } finally { Pop-Location }

    Write-Host '[5/8] Canonical dataset (10,000 articles and 30,000 images) ...' -ForegroundColor Cyan
    $dataset = Invoke-LabCapture $venvPython @('-m','lab.bootstrap','dataset') -AllowFailure -TimeoutSeconds 90
    if ($dataset.Code -ne 0) {
        Write-Host $dataset.Out
        $generated = Join-Path $projectRoot 'backend/generated'
        if (Test-Path -LiteralPath $generated) {
            $previous = Join-Path $projectRoot ('backend/.dataset-previous-' + [guid]::NewGuid().ToString('N'))
            Move-LabItem $generated $previous
            Write-Host "Previous incomplete dataset preserved at $previous"
        }
        Write-Host 'Generating the canonical dataset. This can take several minutes ...'
        Invoke-LabNative $venvPython @('-m','backend.generate')
        Invoke-LabNative $venvPython @('-m','lab.bootstrap','dataset')
    } else { Write-Host 'Dataset is complete and matches the canonical manifest.' }

    Write-Host '[6/8] Perfetto and Android benchmark build ...' -ForegroundColor Cyan
    Ensure-LabPerfetto
    $androidFiles = @()
    foreach ($directory in @('android/app/src','android/app/schemas','android/benchmark/src','android/gradle')) {
        if (Test-Path -LiteralPath "$projectRoot/$directory") {
            $androidFiles += @((Get-ChildItem -LiteralPath "$projectRoot/$directory" -Recurse -File).FullName)
        }
    }
    $androidFiles += @('android/build.gradle.kts','android/settings.gradle.kts','android/gradle.properties',
        'android/app/build.gradle.kts','android/benchmark/build.gradle.kts','scripts/build-benchmark.ps1') | ForEach-Object { Join-Path $projectRoot $_ }
    $androidKey = Get-LabFingerprint $androidFiles
    $appApk = "$projectRoot/android/app/build/outputs/apk/benchmark/app-benchmark.apk"
    $testApk = "$projectRoot/android/benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk"
    if ($Refresh -or $state.android_build -ne $androidKey -or -not (Test-Path -LiteralPath $appApk) -or -not (Test-Path -LiteralPath $testApk)) {
        try { & "$PSScriptRoot/build-benchmark.ps1" }
        catch {
            $buildLog = "$script:LabCache/last-android-build.log"
            if ((Test-Path -LiteralPath $buildLog) -and (Select-String -LiteralPath $buildLog -Pattern 'Could not move temporary workspace' -Quiet)) {
                Write-Host 'Repairing completed Gradle transform workspaces, then retrying once ...'
                & "$PSScriptRoot/repair-gradle-cache.ps1"
                & "$PSScriptRoot/build-benchmark.ps1"
            } else { throw }
        }
        $state.android_build = $androidKey
        Save-LabState $state
    } else { Write-Host 'Android APKs are up to date.' }
    $browser = $null
    if (-not $NoBrowser) { $browser = Get-LabBrowser $node.Node -InstallIfMissing }
    if ($SetupOnly) { Write-Host 'Setup complete. Run the launcher without -SetupOnly to boot Android and open the Lab.' -ForegroundColor Green; return }

    Write-Host '[7/8] Android emulator and APK installation ...' -ForegroundColor Cyan
    $serial = Wait-LabEmulator $BootTimeoutSeconds
    Install-LabApk $serial 'com.adaptivenews.app' $appApk
    Install-LabApk $serial 'com.adaptivenews.benchmark' $testApk
    $bridge = Invoke-LabCapture $env:LAB_ADB @('-s',$serial,'shell','content','call','--uri','content://com.adaptivenews.app.benchmark','--method','ping')
    if ($bridge.Out -notmatch 'protocol=1') { throw 'The installed benchmark bridge did not respond. See the launcher log.' }
    Write-Host '[8/8] Starting backend, proxy and dashboard ...' -ForegroundColor Cyan
    Write-Host 'Keep this terminal open. Press Ctrl+C to stop the Lab servers.'
    Write-Host 'In the browser, share the emulator window and calibrate the Android screen corners to enable evidence.'
    & "$PSScriptRoot/start-lab.ps1" -OpenBrowser:(-not $NoBrowser) -BrowserPath $browser
} catch {
    Write-Host ("Launcher stopped: " + $_.Exception.Message) -ForegroundColor Red
    if ($logPath) { Write-Host "Diagnostic log: $logPath" }
    throw
} finally {
    if ($transcribing) { Stop-Transcript | Out-Null }
    if ($lock) { $lock.Dispose() }
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process') }
    Pop-Location
}
