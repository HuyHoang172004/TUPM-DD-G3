# Workaround for Gradle 8.13 Windows immutable-workspace rename failures.
# Only copies completed transform outputs inside this project's ignored cache.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$cacheRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '.lab/gradle/caches/8.13/transforms'))
if (-not $cacheRoot.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Cache is outside workspace.' }
if (-not (Test-Path -LiteralPath $cacheRoot)) { return }
$restored = 0
Get-ChildItem -LiteralPath $cacheRoot -Directory | Sort-Object LastWriteTime -Descending | ForEach-Object {
    if ($_.Name -match '^([a-f0-9]{32})-[a-f0-9-]{36}$' -and -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        $destination = [IO.Path]::GetFullPath((Join-Path $cacheRoot $Matches[1]))
        $sourcePath = [IO.Path]::GetFullPath($_.FullName)
        if ((Split-Path -Parent $destination) -ne $cacheRoot -or (Split-Path -Parent $sourcePath) -ne $cacheRoot) { throw 'Unexpected cache path.' }
        if (-not (Test-Path -LiteralPath $destination) -and (Test-Path -LiteralPath "$sourcePath\metadata.bin") -and (Test-Path -LiteralPath "$sourcePath\results.bin") -and (Test-Path -LiteralPath "$sourcePath\transformed")) {
            Copy-Item -LiteralPath $sourcePath -Destination $destination -Recurse
            $restored++
        }
    }
}
Write-Host "Restored transform workspaces: $restored"
