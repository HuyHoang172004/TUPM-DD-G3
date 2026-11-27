# Shared bootstrap helpers. Dot-sourcing this file does not install anything.
function Initialize-LabWorkspace([string]$Root) {
    $script:LabWorkspace = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    $script:LabCache = Join-Path $script:LabWorkspace '.lab'
    Assert-LabPath $script:LabCache
    New-Item -ItemType Directory -Path $script:LabCache -Force | Out-Null
}

function Assert-LabPath([string]$Path) {
    $resolved = [IO.Path]::GetFullPath($Path)
    if (-not $resolved.StartsWith($script:LabWorkspace + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path is outside this workspace: $resolved"
    }
    $ancestor = $resolved
    while ($ancestor -and $ancestor -ne $script:LabWorkspace) {
        if (Test-Path -LiteralPath $ancestor) {
            if ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Refusing to modify a junction or symlink: $ancestor"
            }
        }
        $ancestor = Split-Path -Parent $ancestor
    }
}

function Move-LabItem([string]$Source, [string]$Destination) {
    Assert-LabPath $Source
    Assert-LabPath $Destination
    Move-Item -LiteralPath $Source -Destination $Destination -Force
}

function Save-LabState([hashtable]$State) {
    $path = Join-Path $script:LabCache 'bootstrap-state.json'
    [IO.File]::WriteAllText("$path.tmp", ($State | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
    Move-LabItem "$path.tmp" $path
}

function Read-LabState {
    $state = @{}
    $path = Join-Path $script:LabCache 'bootstrap-state.json'
    if (Test-Path -LiteralPath $path) {
        try {
            $parsed = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
            foreach ($property in $parsed.PSObject.Properties) { $state[$property.Name] = $property.Value }
        } catch { Write-Host 'Bootstrap state was incomplete; checking resources again.' }
    }
    return $state
}

function Get-LabFingerprint([string[]]$Paths) {
    $lines = foreach ($path in ($Paths | Sort-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing source file: $path" }
        $relative = [IO.Path]::GetFullPath($path).Substring($script:LabWorkspace.Length).Replace('\', '/')
        $relative + ':' + (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    }
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes(($lines -join [char]10))))).Replace('-', '').ToLowerInvariant() }
    finally { $hasher.Dispose() }
}

function ConvertTo-LabArgument([AllowEmptyString()][string]$Value) {
    if ($Value.Length -and $Value -notmatch '[\s"]') { return $Value }
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Invoke-LabCapture([string]$File, [string[]]$Arguments, [int]$TimeoutSeconds = 30, [switch]$AllowFailure) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $File
    $info.Arguments = (($Arguments | ForEach-Object { ConvertTo-LabArgument $_ }) -join ' ')
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw "Could not start $File" }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            $process.WaitForExit()
            throw "Timed out: $File $($Arguments -join ' ')"
        }
        # adb.exe writes CRCRLF through redirected Windows pipes.
        $result = [PSCustomObject]@{ Code=$process.ExitCode; Out=$stdout.GetAwaiter().GetResult().Replace([string][char]13,'').Trim(); Err=$stderr.GetAwaiter().GetResult().Replace([string][char]13,'').Trim() }
        if ($result.Code -ne 0 -and -not $AllowFailure) {
            throw "$File failed ($($result.Code)): $($result.Err) $($result.Out)"
        }
        return $result
    } finally { $process.Dispose() }
}

function Invoke-LabNative([string]$File, [string[]]$Arguments) {
    & $File @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$File failed (exit $LASTEXITCODE)." }
}

function Get-LabDownload([string]$Url, [string]$Name, [string]$Sha256) {
    if ($Url -notmatch '^https://' -or $Name -notmatch '^[a-zA-Z0-9._-]+$' -or $Sha256 -notmatch '^[a-fA-F0-9]{64}$') {
        throw 'Invalid download URL, filename or checksum.'
    }
    $directory = Join-Path $script:LabCache 'downloads'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $target = Join-Path $directory $Name
    Assert-LabPath $target
    if ((Test-Path -LiteralPath $target) -and (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -eq $Sha256) { return $target }
    Write-Host "Downloading $Name (verified SHA-256) ..."
    $partial = "$target.part"
    Assert-LabPath $partial
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        Invoke-LabNative 'curl.exe' @('--fail','--location','--retry','3','--connect-timeout','20','--max-time','1800','--silent','--show-error','--output',$partial,$Url)
    } else {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $partial -TimeoutSec 1800
    }
    if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Checksum mismatch for $Name. The downloaded file was not executed."
    }
    Move-LabItem $partial $target
    return $target
}

function Expand-LabZip([string]$Archive) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $stage = Join-Path $script:LabCache ('extract-' + [guid]::NewGuid().ToString('N'))
    Assert-LabPath $stage
    New-Item -ItemType Directory -Path $stage | Out-Null
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -match '(^[\\/]|:|(^|[\\/])\.\.([\\/]|$))') { throw 'Unsafe ZIP entry.' }
            $target = [IO.Path]::GetFullPath((Join-Path $stage $entry.FullName))
            if (-not $target.StartsWith($stage + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'ZIP entry leaves staging directory.' }
            if ($entry.FullName.EndsWith('/') -or $entry.FullName.EndsWith('\')) {
                New-Item -ItemType Directory -Path $target -Force | Out-Null
            } else {
                New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            }
        }
    } finally { $zip.Dispose() }
    return $stage
}

function Install-LabDirectory([string]$Source, [string]$Destination) {
    Assert-LabPath $Source
    Assert-LabPath $Destination
    if (Test-Path -LiteralPath $Destination) {
        Move-LabItem $Destination ($Destination + '.previous-' + [guid]::NewGuid().ToString('N'))
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
    Move-LabItem $Source $Destination
}

function Test-LabNodeVersion([string]$Text) {
    $version = $null
    if (-not [Version]::TryParse($Text.Trim().TrimStart('v'), [ref]$version)) { return $false }
    return ($version.Major -eq 20 -and $version.Minor -ge 19) -or ($version.Major -eq 22 -and $version.Minor -ge 12) -or $version.Major -ge 23
}

function Ensure-LabNode([switch]$PortableOnly) {
    $candidates = @()
    if (-not $PortableOnly) {
        $command = Get-Command node.exe -ErrorAction SilentlyContinue
        if ($command) { $candidates += $command.Source }
    }
    $candidates += @(Get-ChildItem "$script:LabCache/tools/node-*/node.exe" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    foreach ($candidate in $candidates) {
        $npm = Join-Path (Split-Path -Parent $candidate) 'npm.cmd'
        if ((Test-Path -LiteralPath $npm) -and (Test-LabNodeVersion (Invoke-LabCapture $candidate @('--version')).Out)) {
            $env:PATH = (Split-Path -Parent $candidate) + ';' + $env:PATH
            return [PSCustomObject]@{ Node=$candidate; Npm=$npm }
        }
    }
    $archive = Get-LabDownload 'https://nodejs.org/dist/v22.23.5/node-v22.23.5-win-x64.zip' 'node-v22.23.5-win-x64.zip' '4a191082e9706866830379d577d2cc8a50c776fcd9436a8d061b063ed0d9a0e3'
    $stage = Expand-LabZip $archive
    $destination = Join-Path $script:LabCache 'tools/node-v22.23.5-win-x64'
    Install-LabDirectory "$stage/node-v22.23.5-win-x64" $destination
    $env:PATH = $destination + ';' + $env:PATH
    return [PSCustomObject]@{ Node="$destination/node.exe"; Npm="$destination/npm.cmd" }
}

function Test-LabPython([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    try {
        return (Invoke-LabCapture $Path @('-c','import sys,venv,ensurepip; assert (3,10) <= sys.version_info[:2] < (3,15)') -AllowFailure).Code -eq 0
    } catch { return $false }
}

function Ensure-LabPython([switch]$PortableOnly) {
    $candidates = @()
    if (-not $PortableOnly) {
        $candidates += "$script:LabCache/venv/Scripts/python.exe"
        $command = Get-Command python.exe -ErrorAction SilentlyContinue
        if ($command -and $command.Source -notmatch '\\WindowsApps\\') { $candidates += $command.Source }
    }
    $candidates += @(Get-ChildItem "$script:LabCache/python/*/python.exe" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    foreach ($candidate in $candidates) { if (Test-LabPython $candidate) { return $candidate } }
    $uvPath = Join-Path $script:LabCache 'tools/uv-0.13.10/uv.exe'
    if (-not (Test-Path -LiteralPath $uvPath)) {
        $archive = Get-LabDownload 'https://releases.astral.sh/github/uv/releases/download/0.13.10/uv-x86_64-pc-windows-msvc.zip' 'uv-0.13.10-windows.zip' '297b3881fe1adfb922becc2c1613202abb41dc83ea5ff43d472aa087ca1f8683'
        $stage = Expand-LabZip $archive
        Install-LabDirectory $stage (Split-Path -Parent $uvPath)
    }
    $env:UV_CACHE_DIR = Join-Path $script:LabCache 'uv-cache'
    $env:UV_PYTHON_INSTALL_DIR = Join-Path $script:LabCache 'python'
    $env:UV_PYTHON_INSTALL_BIN = '0'
    $env:UV_PYTHON_INSTALL_REGISTRY = '0'
    Write-Host 'Installing managed Python 3.12 in .lab (no global PATH or registry changes) ...'
    Invoke-LabNative $uvPath @('python','install','3.12','--no-registry','--no-config')
    $python = (Invoke-LabCapture $uvPath @('python','find','3.12','--managed-python','--no-config')).Out
    if (-not (Test-LabPython $python)) { throw 'Managed Python did not pass its startup check.' }
    return $python
}

function Ensure-LabJdk {
    $candidates = @(Get-ChildItem "$script:LabCache/jdk/*/release" -ErrorAction SilentlyContinue | ForEach-Object { Split-Path -Parent $_.FullName })
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) { $candidates += Split-Path -Parent (Split-Path -Parent $java.Source) }
    foreach ($candidate in $candidates) {
        if ((Test-Path -LiteralPath "$candidate/bin/jlink.exe") -and (Test-Path -LiteralPath "$candidate/release")) {
            if ((Get-Content -LiteralPath "$candidate/release" -Raw) -match 'JAVA_VERSION="(17|21)\.') { $env:JAVA_HOME = $candidate; return }
        }
    }
    $archive = Get-LabDownload 'https://aka.ms/download-jdk/microsoft-jdk-17.0.21.1-windows-x64.zip' 'microsoft-jdk-17.0.21.1-windows-x64.zip' 'a07e270f86aec056d9c2acf981eb64a38e30814dacfadbc5f80f31c55d5a4427'
    $stage = Expand-LabZip $archive
    $jdk = @(Get-ChildItem "$stage/*/bin/jlink.exe")[0]
    $source = Split-Path -Parent (Split-Path -Parent $jdk.FullName)
    $destination = Join-Path $script:LabCache ('jdk/' + (Split-Path -Leaf $source))
    Install-LabDirectory $source $destination
    $env:JAVA_HOME = $destination
}

function Ensure-LabSdk {
    $env:ANDROID_HOME = Join-Path $script:LabCache 'android-sdk'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $env:LAB_ADB = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
    $sdkmanager = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/sdkmanager.bat'
    if (-not (Test-Path -LiteralPath $sdkmanager)) {
        $archive = Get-LabDownload 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' 'commandlinetools-win-13114758_latest.zip' '98b565cb657b012dae6794cefc0f66ae1efb4690c699b78a614b4a6a3505b003'
        $stage = Expand-LabZip $archive
        Install-LabDirectory "$stage/cmdline-tools" "$env:ANDROID_HOME/cmdline-tools/latest"
    }
    $packages = [ordered]@{
        'platform-tools'='platform-tools/adb.exe'
        'platforms;android-36'='platforms/android-36/android.jar'
        'build-tools;35.0.0'='build-tools/35.0.0/aapt2.exe'
        'build-tools;36.0.0'='build-tools/36.0.0/aapt2.exe'
        'emulator'='emulator/emulator.exe'
        'system-images;android-35;default;x86_64'='system-images/android-35/default/x86_64/system.img'
    }
    $missing = @($packages.Keys | Where-Object { -not (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME $packages[$_])) })
    if ($missing.Count) {
        Write-Host 'Installing Android SDK packages and accepting their SDK licenses for this local installation.'
        Write-Host 'Terms: https://developer.android.com/studio/terms'
        @('y') * 100 | & $sdkmanager "--sdk_root=$env:ANDROID_HOME" --licenses
        if ($LASTEXITCODE -ne 0) { throw 'Android SDK license setup failed.' }
        @('y') * 100 | & $sdkmanager "--sdk_root=$env:ANDROID_HOME" @missing
        if ($LASTEXITCODE -ne 0) { throw 'Android SDK package installation failed. Rerun the launcher to retry.' }
    }
    $localProperties = Join-Path $script:LabWorkspace 'android/local.properties'
    $existing = @()
    if (Test-Path -LiteralPath $localProperties) { $existing = @(Get-Content -LiteralPath $localProperties | Where-Object { $_ -notmatch '^\s*sdk\.dir\s*=' }) }
    $sdkProperty = 'sdk.dir=' + $env:ANDROID_HOME.Replace('\','/').Replace(':','\:')
    [IO.File]::WriteAllLines($localProperties, [string[]]($existing + $sdkProperty), (New-Object Text.UTF8Encoding($false)))
}

function Ensure-LabPerfetto {
    $binary = Join-Path $script:LabCache 'tools/perfetto/trace_processor_shell.exe'
    if (-not (Test-Path -LiteralPath $binary) -or (Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash -ne '3acc5aca1c764541033a82ba3bb0a47524b5c3c0aa194eea8d9a7b0cdb346bdc') {
        $download = Get-LabDownload 'https://commondatastorage.googleapis.com/perfetto-luci-artifacts/v59.1/windows-amd64/trace_processor_shell.exe' 'perfetto-v59.1.exe' '3acc5aca1c764541033a82ba3bb0a47524b5c3c0aa194eea8d9a7b0cdb346bdc'
        New-Item -ItemType Directory -Path (Split-Path -Parent $binary) -Force | Out-Null
        Copy-Item -LiteralPath $download -Destination $binary -Force
    }
    $env:LAB_TRACE_PROCESSOR = $binary
}

function Get-LabBrowser([string]$Node, [switch]$InstallIfMissing) {
    $programFilesX86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    $candidates = @(
        "$env:ProgramFiles/Google/Chrome/Application/chrome.exe",
        "$programFilesX86/Microsoft/Edge/Application/msedge.exe",
        "$env:ProgramFiles/Microsoft/Edge/Application/msedge.exe",
        "$env:LOCALAPPDATA/Google/Chrome/Application/chrome.exe"
    )
    $candidates += @(Get-ChildItem "$script:LabCache/browsers/chromium-*/chrome-win*/chrome.exe" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    foreach ($candidate in $candidates) { if (Test-Path -LiteralPath $candidate) { return $candidate } }
    $env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $script:LabCache 'browsers'
    if ($InstallIfMissing) {
        Invoke-LabNative $Node @("$script:LabWorkspace/dashboard/node_modules/playwright/cli.js",'install','chromium')
        $javascript = 'console.log(require(process.argv[1]).chromium.executablePath())'
        return (Invoke-LabCapture $Node @('-e',$javascript,"$script:LabWorkspace/dashboard/node_modules/playwright")).Out
    }
    return $null
}

function Find-LabEmulator {
    $devices = (Invoke-LabCapture $env:LAB_ADB @('devices')).Out
    foreach ($line in ($devices -split '\r?\n')) {
        if ($line -match '^(emulator-\d+)\s+device\b') {
            $serial = $Matches[1]
            $avd = Invoke-LabCapture $env:LAB_ADB @('-s',$serial,'emu','avd','name') -AllowFailure
            if (($avd.Out -split '\r?\n')[0] -eq 'AdaptiveNewsLab') { return $serial }
        }
    }
    return $null
}

function Wait-LabEmulator([int]$TimeoutSeconds = 300) {
    $serial = Find-LabEmulator
    if (-not $serial) {
        $emulator = Join-Path $env:ANDROID_HOME 'emulator/emulator.exe'
        $accel = Invoke-LabCapture $emulator @('-accel-check') -AllowFailure
        if ($accel.Code -ne 0) {
            throw "Android acceleration is unavailable: $($accel.Out) $($accel.Err). Enable VT-x/SVM in BIOS and Windows Hypervisor Platform, restart Windows, then rerun. See lab/README.md."
        }
        & "$script:LabWorkspace/scripts/create-emulator.ps1" | Out-Host
    } else { Write-Host "Reusing emulator $serial (AdaptiveNewsLab)." }
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $nextMessage = [DateTime]::MinValue
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not $serial) { $serial = Find-LabEmulator }
        if ($serial) {
            $boot = Invoke-LabCapture $env:LAB_ADB @('-s',$serial,'shell','getprop','sys.boot_completed') -AllowFailure
            if ($boot.Code -eq 0 -and $boot.Out -eq '1') { return $serial }
        }
        if ([DateTime]::UtcNow -ge $nextMessage) {
            Write-Host 'Waiting for Android to finish booting ...'
            $nextMessage = [DateTime]::UtcNow.AddSeconds(10)
        }
        Start-Sleep -Seconds 2
    }
    throw 'Emulator boot timed out. Check its visible window and rerun the launcher.'
}

function Install-LabApk([string]$Serial, [string]$Package, [string]$Apk) {
    $localHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
    $installed = Invoke-LabCapture $env:LAB_ADB @('-s',$Serial,'shell','pm','path',$Package) -AllowFailure
    $path = @($installed.Out -split '\r?\n' | Where-Object { $_ -match '^package:/data/app/[-A-Za-z0-9_./+=~]+/base\.apk$' } | Select-Object -First 1)
    if ($path.Count) {
        $remotePath = $path[0].Substring(8)
        $remote = Invoke-LabCapture $env:LAB_ADB @('-s',$Serial,'shell','sha256sum',$remotePath) -AllowFailure
        if (($remote.Out -split '\s+')[0] -eq $localHash) { Write-Host "$Package is up to date."; return }
    }
    Write-Host "Installing $Package ..."
    Invoke-LabNative $env:LAB_ADB @('-s',$Serial,'install','-r',$Apk)
}
