<#
.SYNOPSIS
    Dev wrapper around package-sidecar-windows.ps1: takes the OAuth client
    configuration from backend/.env instead of the ambient environment.

.DESCRIPTION
    package-sidecar-windows.ps1 reads the OAuth client id/secret from
    environment variables, because that is how CI supplies them (repository
    secrets). Locally there are no such variables, so packaging either fails or
    — with -AllowPlaceholderOAuth — produces a sidecar whose Google and
    Microsoft login is broken. Exporting the values by hand before every
    packaging run is both tedious and the kind of thing that ends up pasted
    into a shell history.

    This wrapper loads backend/.env (git-ignored, the same file the bare-mvn
    dev run uses) into the process environment and hands over to the real
    packaging script. Every parameter is forwarded, so
    `-SkipTests`, `-EnableAotCache` and the rest work exactly as they do there.

.EXAMPLE
    .\scripts\package-sidecar-dev-windows.ps1 -SkipTests

.NOTES
    Values are never printed — only the names of the variables that were set.
#>
# No param() block on purpose. Arguments are forwarded through the automatic
# $args, which is the only form that keeps switches as switches: splatting a
# [string[]] collected by ValueFromRemainingArguments passes its elements
# positionally, so `-SkipTests` would silently arrive as the value of the
# target's first parameter ($MavenCommand) and packaging would fail trying to
# run "-SkipTests" as maven.

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$backendRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$envFile = Join-Path $backendRoot ".env"
$packagingScript = Join-Path $PSScriptRoot "package-sidecar-windows.ps1"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "No $envFile. Copy backend/.env.example to backend/.env and fill in the OAuth client " +
        "values, or run scripts/package-sidecar-windows.ps1 directly with the environment already set."
}

# Only these three are taken from the file. The rest of .env (crypto key/salt)
# belongs to a running backend, not to packaging, and injecting it here would
# quietly widen what this script touches.
$wanted = @("GOOGLE_OAUTH_CLIENT_ID", "GOOGLE_OAUTH_CLIENT_SECRET", "MICROSOFT_OAUTH_CLIENT_ID")
$loaded = @()

foreach ($line in Get-Content -LiteralPath $envFile) {
    # KEY=VALUE, skipping blanks and # comments. Surrounding quotes are stripped
    # because .env files are written both ways and the value goes into a JVM
    # system property, where a stray quote becomes part of the client id.
    if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { continue }
    $name = $Matches[1]
    if ($wanted -notcontains $name) { continue }

    $value = $Matches[2].Trim()
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    if ([string]::IsNullOrWhiteSpace($value)) { continue }

    [Environment]::SetEnvironmentVariable($name, $value, "Process")
    $loaded += $name
}

$missing = $wanted | Where-Object { $loaded -notcontains $_ }
if ($missing) {
    # Fail here rather than let the packaging script report it: there the message
    # points at CI secrets, which is the wrong place to look when the value was
    # supposed to come out of this file.
    throw "$envFile does not define: $($missing -join ', '). Add them (see backend/.env.example), " +
        "or run scripts/package-sidecar-windows.ps1 -AllowPlaceholderOAuth for a build that does " +
        "not need a working OAuth login."
}

Write-Host "==> Loaded $($loaded.Count) OAuth value(s) from backend/.env: $($loaded -join ', ')"

& $packagingScript @args
exit $LASTEXITCODE
