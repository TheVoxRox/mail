# ==============================================================================
# Import-DotEnv — shared .env loader for the backend dev scripts.
#
# Parses a KEY=value .env file and sets the variables into the current process
# environment. Centralises the loop that used to live inline in run-dev.ps1 so
# every dev wrapper parses .env the same way. Dot-source it:
#
#   . (Join-Path $PSScriptRoot 'scripts\lib\Import-DotEnv.ps1')
#   $n = Import-DotEnv -Path $envFile
#
# Production tooling (package-sidecar-windows.ps1, mvn, CI) never calls this — it
# reads the environment directly, so secrets arrive as env vars, never from a
# committed file.
# ==============================================================================

function Import-DotEnv {
    [CmdletBinding()]
    [OutputType([int])]
    param(
        # Path to the .env file. A missing file is not an error — returns 0.
        [Parameter(Mandatory = $true)] [string] $Path,
        # When set, only these keys are loaded; any other key in the file is
        # ignored (keeps unrelated secrets out of the process environment).
        [string[]] $Only,
        # When set, keys already present in the environment are left untouched,
        # so an explicit env var / CI secret always wins over the file.
        [switch] $NoOverride
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return 0
    }

    $loaded = 0
    foreach ($raw in Get-Content -LiteralPath $Path) {
        $line = $raw.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) {
            continue
        }
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            continue
        }
        $name = $Matches[1]
        $value = $Matches[2]
        if ($value -match '^"(.*)"$' -or $value -match "^'(.*)'$") {
            $value = $Matches[1]
        }
        if ($Only -and ($Only -notcontains $name)) {
            continue
        }
        if ($NoOverride -and -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
            continue
        }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        $loaded++
    }
    return $loaded
}
