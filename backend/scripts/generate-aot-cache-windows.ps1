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

    Single-command mode (JEP 514, -XX:AOTCacheOutput) has a bug on Windows +
    PowerShell with argument escaping through JAVA_TOOL_OPTIONS for the child
    JVM, so we use the two-pass JEP 483 API, which is more stable.

.PARAMETER JarPath
    Path to mail-backend-*.jar.

.PARAMETER CachePath
    Path where the generated AOT cache (.aot) should be saved.

.PARAMETER TempDataDir
    Docasny adresar pro APP_DATA_DIR pri training (vytvori se prazdny).
    Default: %TEMP%\voxrox-aot-training.

.EXAMPLE
    .\generate-aot-cache-windows.ps1 -JarPath ..\target\mail-backend-0.1.0.jar `
                                      -CachePath ..\target\sidecar\app\mail.aot
#>

param(
    [Parameter(Mandatory=$true)][string] $JarPath,
    [Parameter(Mandatory=$true)][string] $CachePath,
    [string] $TempDataDir = (Join-Path ([System.IO.Path]::GetTempPath()) "voxrox-aot-training")
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not (Test-Path $JarPath)) {
    throw "AOT training: jar nenalezen: $JarPath"
}

$resolvedJar = (Resolve-Path $JarPath).Path
$cacheDir = Split-Path -Parent $CachePath
if ($cacheDir -and -not (Test-Path $cacheDir)) {
    New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
}
$configPath = "$CachePath.config"

# Cisty tmp data dir — predchozi training run mohl nechat session.json a DB
if (Test-Path $TempDataDir) {
    Remove-Item -Recurse -Force $TempDataDir
}
New-Item -ItemType Directory -Force -Path $TempDataDir | Out-Null

Write-Host "==> AOT training (record + create)"
Write-Host "    jar:        $resolvedJar"
Write-Host "    cache:      $CachePath"
Write-Host "    data dir:   $TempDataDir"

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

    # Args jako array kvuli PowerShell quoting bugum se znakem '=' pri space-split.
    $recordArgs = @(
        "-XX:AOTMode=record",
        "-XX:AOTConfiguration=$configPath",
        "-Dspring.aot.enabled=true",
        "-Dspring.profiles.active=prod",
        "-Dfile.encoding=UTF-8",
        "--enable-native-access=ALL-UNNAMED",
        "-jar",
        $resolvedJar
    )
    & java @recordArgs
    $recordExit = $LASTEXITCODE
    # The JVM record phase usually returns non-zero due to `Preload Warning:
    # Verification failed` for Spring Security classes (SAML, OAuth2 server,
    # LDAP) that are not on the classpath. These warnings are benign — the
    # config file is written anyway. We treat only a missing config file as
    # failure, not a non-zero exit code.
    if (-not (Test-Path $configPath)) {
        throw "AOT record phase failed (exit $recordExit) — config file was not produced: $configPath. Verify that AotTrainingExitListener calls System.exit and that there is no explicit Spring Security preload error on the classpath."
    }
    if ($recordExit -ne 0) {
        Write-Host "    (record finished with exit $recordExit, but config is written — continuing)"
    }

    Write-Host ""
    Write-Host "    [2/2] Creating AOT cache from configuration..."

    $createArgs = @(
        "-XX:AOTMode=create",
        "-XX:AOTConfiguration=$configPath",
        "-XX:AOTCache=$CachePath",
        "-Dspring.aot.enabled=true",
        "-Dspring.profiles.active=prod",
        "-Dfile.encoding=UTF-8",
        "--enable-native-access=ALL-UNNAMED",
        "-jar",
        $resolvedJar
    )
    & java @createArgs
    $createExit = $LASTEXITCODE
    # The create phase is similar — a non-zero exit often signals warnings
    # only. Validation happens below via Test-Path on CachePath.
    if ($createExit -ne 0) {
        Write-Host "    (create finished with exit $createExit, verifying the cache was produced)"
    }
} finally {
    if (Test-Path $configPath) {
        Remove-Item -Force $configPath
    }
    Remove-Item Env:MAIL_CRYPTO_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:MAIL_CRYPTO_SALT -ErrorAction SilentlyContinue
    Remove-Item Env:MAIL_AOT_TRAINING_RUN -ErrorAction SilentlyContinue
    Remove-Item Env:APP_DATA_DIR -ErrorAction SilentlyContinue
}

if (-not (Test-Path $CachePath)) {
    throw "AOT cache nebyla vygenerovana: $CachePath."
}

$cacheSize = (Get-Item $CachePath).Length
Write-Host ""
Write-Host "AOT cache hotova: $CachePath ($([math]::Round($cacheSize/1MB, 2)) MB)"
