# ==============================================================================
# Dev wrapper — loads .env and runs mvn spring-boot:run.
# Usage: .\run-dev.ps1
# ==============================================================================

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "scripts\lib\Import-DotEnv.ps1")

$envFile = Join-Path $PSScriptRoot ".env"

if (-not (Test-Path $envFile)) {
    Write-Host "[run-dev] ERROR: .env file not found at $envFile" -ForegroundColor Red
    Write-Host "[run-dev] Copy .env.example to .env and fill in the values." -ForegroundColor Yellow
    exit 1
}

Write-Host "[run-dev] Loading variables from .env..." -ForegroundColor Cyan
$loaded = Import-DotEnv -Path $envFile
Write-Host "[run-dev] Loaded $loaded variables." -ForegroundColor Green
Write-Host "[run-dev] Starting mvn spring-boot:run..." -ForegroundColor Cyan
Write-Host ""

& mvn spring-boot:run $args
exit $LASTEXITCODE
