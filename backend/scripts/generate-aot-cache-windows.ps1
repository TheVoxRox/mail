<#
.SYNOPSIS
    Generates a JEP 483 AOT cache for the mail-backend sidecar (Java 25+).

.DESCRIPTION
    Two-pass workflow:
      Phase 1 (record): JVM starts with MAIL_AOT_TRAINING_RUN=1,
        AotTrainingExitListener calls System.exit after ApplicationReadyEvent,
        and the JVM writes AOTConfiguration.
      Phase 2 (create): JVM reads AOTConfiguration and assembles the binary AOT
        cache; the application is not restarted (only the config is processed).

    The AOT cache contains class-loading + linking metadata for every class
    loaded up to ApplicationReady. At production runtime with -XX:AOTCache=<file>
    the JVM reads metadata from the cache instead of rebuilding it from the jar,
    which cuts cold start by ~20-40 % beyond Spring AOT (verify in
    PERFORMANCE_BASELINE.md).

    A cache is usable only by the runtime it was trained on. The JVM checks the
    runtime image's lib\modules and the application jar against what it saw
    while training, and refuses the cache on a mismatch -- with a warning, not
    an error, so the process starts and runs without it. The jar's
    modification time counts, in whole seconds; package-sidecar-windows.ps1
    rounds the image's times to the installer's 2 seconds before calling this,
    and says why. Two modes follow:

      -LauncherPath  trains through a jpackage app image's own launcher, so the
                     cache matches the jlinked runtime, the jar and the JVM
                     options that ship. This is what packaging uses. Until
                     2026-10-17 packaging trained with the system `java -jar`
                     instead, and every release build refused its cache
                     ("runtime\lib\modules ... size has changed").
      -JarPath       trains with the `java` on PATH and the given jar, for
                     measuring JVM flags against the system JDK
                     (measure-cold-start-windows.ps1). Such a cache does not fit
                     the app image.

    The launcher takes the training options through JAVA_TOOL_OPTIONS, which the
    JVM reads ahead of the options in the launcher's .cfg. The .cfg must
    therefore not carry -XX:AOTCache yet: "AOTCache must not be specified when
    using -XX:AOTMode=record". The phases run in a scratch directory and name
    their files relative to it, so no path in the options has to survive the
    whitespace splitting of JAVA_TOOL_OPTIONS.

    Single-command mode (JEP 514, -XX:AOTCacheOutput) has a bug on Windows +
    PowerShell with argument escaping through JAVA_TOOL_OPTIONS for the child
    JVM, so we use the two-pass JEP 483 API, which is more stable.

.PARAMETER LauncherPath
    Path to the jpackage app image's launcher (.exe).

.PARAMETER JarPath
    Path to mail-backend-*.jar, trained with the system `java`.

.PARAMETER CachePath
    Path where the generated AOT cache (.aot) should be saved.

.PARAMETER TempDataDir
    Temporary directory used as APP_DATA_DIR during training (created empty).
    Default: %TEMP%\voxrox-aot-training.

.EXAMPLE
    .\generate-aot-cache-windows.ps1 -LauncherPath "..\target\sidecar\jpackage\VoxRox Mail\VoxRox Mail.exe" `
                                      -CachePath "..\target\sidecar\jpackage\VoxRox Mail\app\mail.aot"
#>

[CmdletBinding(DefaultParameterSetName = 'Launcher')]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'Launcher')][string] $LauncherPath,
    [Parameter(Mandatory = $true, ParameterSetName = 'Jar')][string] $JarPath,
    [Parameter(Mandatory = $true)][string] $CachePath,
    [string] $TempDataDir = (Join-Path ([System.IO.Path]::GetTempPath()) "voxrox-aot-training")
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$useLauncher = $PSCmdlet.ParameterSetName -eq 'Launcher'
$program = if ($useLauncher) { $LauncherPath } else { $JarPath }
if (-not (Test-Path -LiteralPath $program)) {
    throw "AOT training: $(if ($useLauncher) { 'launcher' } else { 'jar' }) not found: $program"
}
$resolvedProgram = (Resolve-Path -LiteralPath $program).Path

$cacheDir = Split-Path -Parent $CachePath
if ($cacheDir -and -not (Test-Path -LiteralPath $cacheDir)) {
    New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
}

# Clean tmp data dir — a previous training run may have left session.json and a DB
if (Test-Path -LiteralPath $TempDataDir) {
    Remove-Item -LiteralPath $TempDataDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $TempDataDir | Out-Null
$workDir = "$TempDataDir-work"
if (Test-Path -LiteralPath $workDir) {
    Remove-Item -LiteralPath $workDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $workDir | Out-Null
$configName = "mail.aotconf"
$cacheName = "mail.aot"

Write-Host "==> AOT training (record + create)"
Write-Host "    $(if ($useLauncher) { 'launcher' } else { 'jar:     ' }): $resolvedProgram"
Write-Host "    cache:      $CachePath"
Write-Host "    data dir:   $TempDataDir"

# Runs one phase and returns its exit code. The launcher is a GUI-subsystem
# program, which the call operator does not wait for, and a redirected stream
# nobody drains blocks the JVM mid-boot; Start-Process -Wait into files does
# neither.
function Invoke-Phase {
    param(
        [string] $Name,
        [string[]] $AotOptions
    )

    $stdout = Join-Path $workDir "$Name.out.log"
    $stderr = Join-Path $workDir "$Name.err.log"
    if ($useLauncher) {
        $env:JAVA_TOOL_OPTIONS = $AotOptions -join ' '
        $arguments = @()
        $file = $resolvedProgram
    } else {
        $file = "java"
        $arguments = $AotOptions + @(
            "-Dspring.aot.enabled=true",
            "-Dspring.profiles.active=prod",
            "-Dfile.encoding=UTF-8",
            "--enable-native-access=ALL-UNNAMED",
            "-jar",
            "`"$resolvedProgram`""
        )
    }
    try {
        $startArgs = @{
            FilePath               = $file
            WorkingDirectory       = $workDir
            RedirectStandardOutput = $stdout
            RedirectStandardError  = $stderr
            NoNewWindow            = $true
            Wait                   = $true
            PassThru               = $true
        }
        if ($arguments.Count -gt 0) {
            $startArgs.ArgumentList = $arguments
        }
        $process = Start-Process @startArgs
        return $process.ExitCode
    } finally {
        Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    }
}

function Get-PhaseTail {
    param([string] $Name)
    $lines = @()
    foreach ($stream in "out", "err") {
        $log = Join-Path $workDir "$Name.$stream.log"
        if (Test-Path -LiteralPath $log) {
            $lines += Get-Content -LiteralPath $log -Tail 10
        }
    }
    return ($lines -join "`n")
}

# Training needs dummy crypto keys — the Spring properties MAIL_CRYPTO_KEY/SALT
# are required. The values are never used outside the local training; the AOT
# cache holds class-loading info, not plaintext keys (verifiable via hexdump).
$env:MAIL_CRYPTO_KEY = "training-only-dummy-32byte-key__"
$env:MAIL_CRYPTO_SALT = "training-only-dummy-salt"
$env:MAIL_AOT_TRAINING_RUN = "1"
$env:APP_DATA_DIR = $TempDataDir

try {
    Write-Host ""
    Write-Host "    [1/2] Recording AOT configuration..."

    $recordExit = Invoke-Phase -Name "record" -AotOptions @(
        "-XX:AOTMode=record",
        "-XX:AOTConfiguration=$configName"
    )
    # The JVM record phase usually returns non-zero due to `Preload Warning:
    # Verification failed` for Spring Security classes (SAML, OAuth2 server,
    # LDAP) that are not on the classpath. These warnings are benign — the
    # config file is written anyway. We treat only a missing config file as
    # failure, not a non-zero exit code.
    if (-not (Test-Path -LiteralPath (Join-Path $workDir $configName))) {
        throw "AOT record phase failed (exit $recordExit) — config file was not produced. Verify that AotTrainingExitListener calls System.exit and that there is no explicit Spring Security preload error on the classpath. Last output:`n$(Get-PhaseTail 'record')"
    }
    if ($recordExit -ne 0) {
        Write-Host "    (record finished with exit $recordExit, but config is written — continuing)"
    }

    Write-Host ""
    Write-Host "    [2/2] Creating AOT cache from configuration..."

    $createExit = Invoke-Phase -Name "create" -AotOptions @(
        "-XX:AOTMode=create",
        "-XX:AOTConfiguration=$configName",
        "-XX:AOTCache=$cacheName"
    )
    # The create phase is similar — a non-zero exit often signals warnings
    # only. Validation happens below via Test-Path on the cache.
    if ($createExit -ne 0) {
        Write-Host "    (create finished with exit $createExit, verifying the cache was produced)"
    }
    $createdCache = Join-Path $workDir $cacheName
    if (-not (Test-Path -LiteralPath $createdCache)) {
        throw "AOT cache was not generated (create exit $createExit). Last output:`n$(Get-PhaseTail 'create')"
    }
    Move-Item -LiteralPath $createdCache -Destination $CachePath -Force
} finally {
    Remove-Item Env:MAIL_CRYPTO_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:MAIL_CRYPTO_SALT -ErrorAction SilentlyContinue
    Remove-Item Env:MAIL_AOT_TRAINING_RUN -ErrorAction SilentlyContinue
    Remove-Item Env:APP_DATA_DIR -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $workDir -Recurse -Force -ErrorAction SilentlyContinue
}

$cacheSize = (Get-Item -LiteralPath $CachePath).Length
Write-Host ""
Write-Host "AOT cache ready: $CachePath ($([math]::Round($cacheSize/1MB, 2)) MB)"
