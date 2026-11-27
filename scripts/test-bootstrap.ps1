# Standalone tests; no Pester installation and no network required.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot/lab-bootstrap.ps1"
$fixture = Join-Path $projectRoot ('.lab/bootstrap-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
Initialize-LabWorkspace $fixture
$checks = 0
function Assert-Check([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}
function Assert-Rejected([scriptblock]$Action, [string]$Message) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-Check $rejected $Message
}
foreach ($entry in @(@('v20.18.0',$false),@('v20.19.0',$true),@('v22.11.0',$false),@('v22.12.0',$true),@('v24.19.0',$true),@('invalid',$false))) {
    Assert-Check ((Test-LabNodeVersion $entry[0]) -eq $entry[1]) "Wrong Node support: $($entry[0])"
}
Assert-Rejected { Assert-LabPath (Join-Path $fixture '../outside') } 'Workspace escape was accepted.'
Assert-Rejected { Get-LabDownload 'http://example.com/file' 'file.zip' ('0'*64) } 'Plain HTTP download was accepted.'
Assert-Rejected { Get-LabDownload 'https://example.com/file' '../file.zip' ('0'*64) } 'Download filename escape was accepted.'

$source = Join-Path $fixture 'source.txt'
[IO.File]::WriteAllText($source, 'first')
$first = Get-LabFingerprint @($source)
[IO.File]::WriteAllText($source, 'second')
Assert-Check ($first -ne (Get-LabFingerprint @($source))) 'Source changes did not invalidate the cache.'
Save-LabState @{ web_build='old'; android_build='old' }
Assert-Check ((Read-LabState).web_build -eq 'old') 'State did not round-trip.'
[IO.File]::WriteAllText("$script:LabCache/bootstrap-state.json", '{incomplete')
Assert-Check ((Read-LabState).Count -eq 0) 'An interrupted state file prevented recovery.'

# A verified cached archive must work offline.
New-Item -ItemType Directory -Path "$script:LabCache/downloads" -Force | Out-Null
[IO.File]::WriteAllText("$script:LabCache/downloads/cached.zip", 'verified fixture')
$digest = (Get-FileHash "$script:LabCache/downloads/cached.zip" -Algorithm SHA256).Hash
Assert-Check ([IO.Path]::GetFullPath((Get-LabDownload 'https://invalid.example.test/not-used' 'cached.zip' $digest)) -eq [IO.Path]::GetFullPath("$script:LabCache/downloads/cached.zip")) 'A cached verified download was not reused.'

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipPath = Join-Path $fixture 'escape.zip'
$zip = [IO.Compression.ZipFile]::Open($zipPath, 'Create')
try { $entry = $zip.CreateEntry('../escaped.txt') } finally { $zip.Dispose() }
Assert-Rejected { Expand-LabZip $zipPath } 'ZIP traversal was accepted.'
Assert-Check (-not (Test-Path -LiteralPath "$script:LabCache/escaped.txt")) 'ZIP wrote outside staging.'
$zipPath = Join-Path $fixture 'valid.zip'
$zip = [IO.Compression.ZipFile]::Open($zipPath, 'Create')
try {
    $writer = New-Object IO.StreamWriter($zip.CreateEntry('nested/path with spaces.txt').Open())
    try { $writer.Write('valid') } finally { $writer.Dispose() }
} finally { $zip.Dispose() }
$stage = Expand-LabZip $zipPath
Assert-Check ((Get-Content -LiteralPath "$stage/nested/path with spaces.txt" -Raw) -eq 'valid') 'A valid ZIP did not extract.'

# Check argv handling with real Windows process parsing, including literal shell characters.
$python = "$projectRoot/.lab/venv/Scripts/python.exe"
if (-not (Test-Path -LiteralPath $python)) { $python = (Get-Command python.exe).Source }
$arguments = @('', 'with spaces', 'a"quote', 'C:\path with spaces\', '$() & literal; value', 'back\slash"quote')
$json = (Invoke-LabCapture $python (@('-c','import sys,json; print(json.dumps(sys.argv[1:]))') + $arguments)).Out | ConvertFrom-Json
Assert-Check (($json | ConvertTo-Json -Compress) -eq ($arguments | ConvertTo-Json -Compress)) 'Native argument quoting changed the supplied arguments.'
$errorResult = Invoke-LabCapture $python @('-c','import sys; print("failure",file=sys.stderr); sys.exit(7)') -AllowFailure
Assert-Check ($errorResult.Code -eq 7 -and $errorResult.Err -eq 'failure') 'Native failure details were lost.'
$crlf = Invoke-LabCapture $python @('-c','import sys; sys.stdout.write("AdaptiveNewsLab\r\r\nOK\r\r\n")')
Assert-Check (($crlf.Out -split '\r?\n')[0] -eq 'AdaptiveNewsLab') 'ADB-style doubled CRLF was not normalized.'
Assert-Rejected { Invoke-LabCapture $python @('-c','import time; time.sleep(3)') -TimeoutSeconds 1 } 'A hung process was not stopped.'
Write-Host "Bootstrap checks passed: $checks. Fixtures retained in $fixture"
