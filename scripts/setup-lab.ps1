param([switch]$SkipAndroid)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot

function Assert-ExitCode([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step failed (exit $LASTEXITCODE)." }
}

if (-not (Test-Path -LiteralPath '.lab/venv/Scripts/python.exe')) {
    python -m venv .lab/venv
    Assert-ExitCode 'Create Python venv'
}
$labPython = Join-Path $projectRoot '.lab/venv/Scripts/python.exe'
& $labPython -m pip install -r backend/requirements.txt -r lab/requirements.txt
Assert-ExitCode 'Install Python dependencies'
Push-Location -LiteralPath dashboard
try {
    npm.cmd ci --cache .npm-cache --no-audit --no-fund
    Assert-ExitCode 'Install dashboard dependencies'
    npm.cmd run build
    Assert-ExitCode 'Build dashboard'
} finally { Pop-Location }

if (-not $SkipAndroid) {
    & (Join-Path $PSScriptRoot 'build-benchmark.ps1')
}
Write-Host 'Ready. Run: .\scripts\start-lab.ps1' -ForegroundColor Green
