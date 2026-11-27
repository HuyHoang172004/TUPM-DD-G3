param([switch]$SkipBackend, [switch]$OpenBrowser, [string]$BrowserPath = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$labPython = Join-Path $projectRoot '.lab/venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $labPython)) { throw 'Run .\scripts\setup-lab.ps1 first.' }
if (-not (Test-Path -LiteralPath 'dashboard/dist/index.html')) { throw 'Build dashboard before starting the runner.' }
if (-not $env:ADAPTIVE_NEWS_DATA_DIR) { $env:ADAPTIVE_NEWS_DATA_DIR = Join-Path $projectRoot 'backend/generated' }
if (-not (Test-Path -LiteralPath "$env:ADAPTIVE_NEWS_DATA_DIR\manifest.json")) {
    throw 'Generate the dataset first: .\.lab\venv\Scripts\python.exe -m backend.generate'
}
$backendProcess = $null
$browserJob = $null
try {
    if (-not $SkipBackend) {
        $backendProcess = Start-Process -FilePath $labPython -ArgumentList @('-m','uvicorn','backend.main:app','--host','127.0.0.1','--port','8000','--no-access-log') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$projectRoot\.lab\backend.log" -RedirectStandardError "$projectRoot\.lab\backend-error.log"
        Start-Sleep -Seconds 2
        if ($backendProcess.HasExited) { throw 'Backend did not start; see .lab/backend-error.log. Use -SkipBackend if one is already running.' }
    }
    Write-Host 'Open http://127.0.0.1:8765 in Chrome or Edge.' -ForegroundColor Green
    Write-Host 'Select the emulator window in Evidence, then calibrate its two screen corners.'
    if ($OpenBrowser) {
        $browserJob = Start-Job -ArgumentList $BrowserPath -ScriptBlock {
            param($browser)
            for ($attempt = 0; $attempt -lt 60; $attempt++) {
                try {
                    $ready = Invoke-RestMethod 'http://127.0.0.1:8765/api/instance' -TimeoutSec 1
                    if ($ready.app -eq 'adaptive-news-performance-lab') {
                        if ($browser) { Start-Process -FilePath $browser -ArgumentList @('--new-window','http://127.0.0.1:8765') }
                        else { Start-Process 'http://127.0.0.1:8765' }
                        break
                    }
                } catch {}
                Start-Sleep -Seconds 1
            }
        }
    }
    & $labPython -m uvicorn lab.app:app --host 127.0.0.1 --port 8765 --no-access-log
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1073741510) { throw "Lab server exited with code $LASTEXITCODE." }
} finally {
    if ($browserJob) { Stop-Job $browserJob -ErrorAction SilentlyContinue; Remove-Job $browserJob -Force -ErrorAction SilentlyContinue }
    if ($backendProcess -and -not $backendProcess.HasExited) { Stop-Process -Id $backendProcess.Id }
}
