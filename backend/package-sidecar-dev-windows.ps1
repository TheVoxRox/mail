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
#
# Arguments are forwarded through the automatic $args on purpose, which is the
# only form that keeps switches as switches: splatting a [string[]] collected by
# ValueFromRemainingArguments passes its elements positionally, so `-SkipTests`
# would arrive as the value of the target's first parameter ($MavenCommand) and
# packaging would fail trying to run "-SkipTests" as maven.
# ==============================================================================

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot 'scripts\lib\Import-DotEnv.ps1')

$envFile = Join-Path $PSScriptRoot '.env'
# Only the OAuth keys — crypto and other secrets must not leak into the packaging
# environment (the -Paot step boots the Spring context). Already-set env vars win.
$oauthKeys = @('GOOGLE_OAUTH_CLIENT_ID', 'GOOGLE_OAUTH_CLIENT_SECRET', 'MICROSOFT_OAUTH_CLIENT_ID')

$loaded = Import-DotEnv -Path $envFile -Only $oauthKeys -NoOverride

# Check the environment, not the load count: -NoOverride means an already
# exported value (or a CI secret) legitimately wins over the file, and such a run
# loads nothing while being perfectly configured.
$missing = @($oauthKeys | Where-Object {
        [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
    })

# The packaging script requires all three unless -AllowPlaceholderOAuth, which is
# the deliberate opt-out for a build that does not need a working OAuth login.
# Forward it and let the packaging script warn per value instead of stopping here.
# Matched in full rather than as a prefix, even though PowerShell would bind an
# abbreviation: an abbreviated switch then stops here instead, and the message
# names the flag to type, which is a readable outcome rather than a silent one.
$allowPlaceholder = @($args) -contains '-AllowPlaceholderOAuth'

if ($missing.Count -eq 0) {
    Write-Host "[package-dev] OAuth client configuration ready ($loaded value(s) from .env)." -ForegroundColor Green
}
elseif ($allowPlaceholder) {
    Write-Warning ("[package-dev] Not set: $($missing -join ', '). Continuing because " +
        "-AllowPlaceholderOAuth was passed — OAuth login will not work in this build.")
}
else {
    # Stop here rather than let the packaging script report it: its message points
    # at CI secrets, which is the wrong place to look when the value was supposed
    # to come out of this file.
    $where = if (Test-Path -LiteralPath $envFile) { "$envFile does not define" } else { "No $envFile, so nothing defines" }
    throw "${where}: $($missing -join ', '). Add them (see backend/.env.example), or pass " +
        "-AllowPlaceholderOAuth for a local build without a working OAuth login."
}

& (Join-Path $PSScriptRoot 'scripts\package-sidecar-windows.ps1') @args
exit $LASTEXITCODE
