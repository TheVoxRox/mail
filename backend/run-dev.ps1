# ==============================================================================
# Dev wrapper — loads .env and runs mvn spring-boot:run.
# Usage: .\run-dev.ps1
# ==============================================================================

$ErrorActionPreference = "Stop"

$envFile = Join-Path $PSScriptRoot ".env"

if (-not (Test-Path $envFile)) {
    Write-Host "[run-dev] ERROR: .env file not found at $envFile" -ForegroundColor Red
    Write-Host "[run-dev] Copy .env.example to .env and fill in the values." -ForegroundColor Yellow
    exit 1
}

Write-Host "[run-dev] Loading variables from .env..." -ForegroundColor Cyan

$loaded = 0
Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }

    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
        $name = $Matches[1]
        $value = $Matches[2]
        if ($value -match '^"(.*)"$' -or $value -match "^'(.*)'$") {
            $value = $Matches[1]
        }
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
        $script:loaded++
    }
}

Write-Host "[run-dev] Loaded $loaded variables." -ForegroundColor Green
Write-Host "[run-dev] Starting mvn spring-boot:run..." -ForegroundColor Cyan
Write-Host ""

& mvn spring-boot:run $args
exit $LASTEXITCODE
