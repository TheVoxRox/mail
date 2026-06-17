# ==============================================================================
# Dev wrapper — packages the Windows sidecar with the OAuth client identifiers
# from backend/.env baked into the launcher. Use this for a local build where
# OAuth login has to work:
#
#   .\package-sidecar-dev-windows.ps1 -SkipTests
#
# The packaging script itself (scripts/package-sidecar-windows.ps1) stays a pure
# function of the environment — CI provides the OAuth values as secrets, never
# from a file. This wrapper is the local-dev convenience that sources them from
# .env first, mirroring run-dev.ps1. All arguments are forwarded to the
# packaging script.
# ==============================================================================

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot 'scripts\lib\Import-DotEnv.ps1')

$envFile = Join-Path $PSScriptRoot '.env'
# Only the OAuth keys — crypto and other secrets must not leak into the packaging
# environment (the -Paot step boots the Spring context). Already-set env vars win.
$oauthKeys = @('GOOGLE_OAUTH_CLIENT_ID', 'GOOGLE_OAUTH_CLIENT_SECRET', 'MICROSOFT_OAUTH_CLIENT_ID')

$loaded = Import-DotEnv -Path $envFile -Only $oauthKeys -NoOverride
if ($loaded -eq 0) {
    Write-Warning "[package-dev] No OAuth values loaded from $envFile; packaging will fail unless they are already in the environment (or you pass -AllowPlaceholderOAuth)."
}
else {
    Write-Host "[package-dev] Loaded $loaded OAuth value(s) from .env." -ForegroundColor Green
}

& (Join-Path $PSScriptRoot 'scripts\package-sidecar-windows.ps1') @args
exit $LASTEXITCODE
