param(
    [string] $MavenCommand = "mvn.cmd",
    [string] $MavenRepoLocal = ".m2repo",
    [string] $TargetTriple = "x86_64-pc-windows-msvc",
    [string] $OutputRoot = "target\sidecar",
    [switch] $SkipTests,
    # JEP 483/514 AOT class cache (experimental, default OFF). The cache is tied
    # to the exact jar hash + Java version — it must be regenerated whenever
    # the jar is rebuilt, and ~115 MB lands next to the jar. Before enabling,
    # read the OPERATIONS.md section "JEP 483 AOT class cache" and verify that
    # the runtime startup does not throw exceptions from Spring AOT proxy
    # generation.
    [switch] $EnableAotCache
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$repoLocalPath = Join-Path $repoRoot $MavenRepoLocal
$outputRootPath = Join-Path $repoRoot $OutputRoot
$sidecarName = "mail-$TargetTriple"
$jpackageInputDir = Join-Path $outputRootPath "jpackage-input"
$jpackageWorkDir = Join-Path $outputRootPath "jpackage"
$sidecarDir = Join-Path $outputRootPath $TargetTriple

function Invoke-Step {
    param(
        [string] $Title,
        [scriptblock] $Body
    )

    Write-Host "==> $Title"
    & $Body
}

function Remove-ItemWithRetry {
    # On Windows the freshly built sidecar .exe is briefly locked by Defender's
    # real-time scan, so a plain Remove-Item intermittently fails with
    # "being used by another process". Retry a few times with a short backoff
    # (plus a GC to drop any lingering handles) before giving up.
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [int] $Retries = 5,
        [int] $DelayMs = 800
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    for ($attempt = 1; $attempt -le $Retries; $attempt++) {
        try {
            Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop
            return
        } catch {
            if ($attempt -eq $Retries) {
                throw "Failed to delete '$Path' after $Retries attempts: $($_.Exception.Message)"
            }
            Write-Host "  delete locked ($attempt/$Retries), retrying: $Path"
            [System.GC]::Collect()
            Start-Sleep -Milliseconds $DelayMs
        }
    }
}

Invoke-Step "Checking jpackage" {
    $null = Get-Command jpackage -ErrorAction Stop
}

Invoke-Step "Building Spring Boot jar" {
    # -Paot zapne profil v pom.xml ktery pridava spring-boot-maven-plugin
    # process-aot goal. Vystupem je jar s vygenerovanymi AOT classes; pri behu
    # se aktivuji JVM optnou -Dspring.aot.enabled=true (viz jpackage volani nize).
    $mavenArgs = @(
        "-Dmaven.repo.local=$repoLocalPath",
        "-Paot",
        "package"
    )

    if ($SkipTests) {
        $mavenArgs += "-DskipTests"
    }

    Push-Location $repoRoot
    try {
        & $MavenCommand @mavenArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Maven package failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
}

$jar = Get-ChildItem -LiteralPath (Join-Path $repoRoot "target") -Filter "mail-backend-*.jar" |
    Where-Object { $_.Name -notlike "*.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $jar) {
    throw "No mail-backend jar found in target."
}

Invoke-Step "Preparing output directory" {
    Remove-ItemWithRetry -Path $jpackageInputDir
    Remove-ItemWithRetry -Path $jpackageWorkDir
    Remove-ItemWithRetry -Path $sidecarDir
    New-Item -ItemType Directory -Force -Path $jpackageInputDir | Out-Null
    New-Item -ItemType Directory -Force -Path $jpackageWorkDir | Out-Null
    New-Item -ItemType Directory -Force -Path $sidecarDir | Out-Null
    Copy-Item -LiteralPath $jar.FullName -Destination $jpackageInputDir -Force
}

Invoke-Step "Resolving OAuth client configuration" {
    # Bake the production OAuth client identifiers into the launcher as JVM system
    # properties (they override application.properties at runtime). Values come
    # from the environment (CI secrets), never from committed files.
    #   - Microsoft is a PUBLIC client (no secret) — only the client-id is shipped.
    #   - Google's "Desktop app" secret is non-confidential per Google's own
    #     installed-app guidance and is required by Google's token endpoint; PKCE
    #     protects the flow. See backend application.properties for the rationale.
    # A missing value falls back to the non-working placeholder, so an unsigned
    # local build still packages — OAuth login just will not work until the env
    # vars are set.
    $script:oauthArgs = @()
    $oauthMappings = [ordered]@{
        "GOOGLE_OAUTH_CLIENT_ID"     = "spring.security.oauth2.client.registration.google.client-id"
        "GOOGLE_OAUTH_CLIENT_SECRET" = "spring.security.oauth2.client.registration.google.client-secret"
        "MICROSOFT_OAUTH_CLIENT_ID"  = "spring.security.oauth2.client.registration.microsoft.client-id"
    }

    foreach ($envName in $oauthMappings.Keys) {
        $value = [Environment]::GetEnvironmentVariable($envName)
        if ([string]::IsNullOrWhiteSpace($value)) {
            Write-Warning "  $envName is not set; packaging with the placeholder for $($oauthMappings[$envName]). OAuth login will not work in this build."
            continue
        }
        $script:oauthArgs += "--java-options"
        $script:oauthArgs += "-D$($oauthMappings[$envName])=$value"
    }

    Write-Host "  Injecting $([int]($script:oauthArgs.Count / 2)) OAuth client value(s) into the launcher."
}

Invoke-Step "Creating Windows app-image sidecar with jpackage" {
    # JVM tuning pro single-user desktop sidecar:
    #   --enable-native-access=ALL-UNNAMED  Java 25 vyzaduje pro JNI bez warningu.
    #   -Dfile.encoding=UTF-8               Konzistentni encoding napric platformami.
    #   -XX:TieredStopAtLevel=1             Jen C1 JIT (zadny C2), ~10-15 % rychlejsi
    #                                       cold start; sidecar nepotrebuje peak
    #                                       throughput jako serverova aplikace.
    #   -Xms64m / -Xmx384m                  Sidecar je single-user pro 1 mailbox;
    #                                       defaultni Xmx (1/4 RAM) je radove vetsi
    #                                       nez potreba, jen prodluzuje GC pause.
    #   -XX:+UseSerialGC                    1 GC vlakno = minimalni overhead pri
    #                                       startu + maly footprint, vhodne pro
    #                                       maly heap a low-throughput scenar.
    # AOT cache (JEP 483, Java 25) — relativni cesta vuci pracovni directory
    # jpackage launcheru, ktera je install dir aplikace. Default OFF (viz
    # parameter -EnableAotCache). Cache se generuje krokem "Generating AOT
    # class cache" a zkopiruje do app/.
    $aotArgs = @()
    if ($EnableAotCache) {
        $aotArgs += "--java-options"
        $aotArgs += "-XX:AOTCache=app\mail.aot"
    }

    & jpackage `
        --type app-image `
        --name $sidecarName `
        --input $jpackageInputDir `
        --main-jar $jar.Name `
        --dest $jpackageWorkDir `
        --java-options "--enable-native-access=ALL-UNNAMED" `
        --java-options "-Dfile.encoding=UTF-8" `
        --java-options "-Dspring.aot.enabled=true" `
        --java-options "-XX:TieredStopAtLevel=1" `
        --java-options "-Xms64m" `
        --java-options "-Xmx384m" `
        --java-options "-XX:+UseSerialGC" `
        @aotArgs `
        @oauthArgs

    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed with exit code $LASTEXITCODE."
    }
}

$imageDir = Join-Path $jpackageWorkDir $sidecarName
if (-not (Test-Path $imageDir)) {
    throw "jpackage output image was not created: $imageDir"
}

if ($EnableAotCache) {
    Invoke-Step "Generating AOT class cache (JEP 483)" {
        $cachePath = Join-Path $imageDir "app\mail.aot"
        & (Join-Path $PSScriptRoot "generate-aot-cache-windows.ps1") `
            -JarPath $jar.FullName `
            -CachePath $cachePath

        if (-not (Test-Path $cachePath)) {
            throw "AOT cache nebyla vygenerovana: $cachePath"
        }
    }
}

Invoke-Step "Publishing sidecar image" {
    Copy-Item -Path (Join-Path $imageDir "*") -Destination $sidecarDir -Recurse -Force
}

$exePath = Join-Path $sidecarDir "$sidecarName.exe"
if (-not (Test-Path $exePath)) {
    throw "Expected sidecar executable was not created: $exePath"
}

Write-Host ""
Write-Host "Sidecar image ready:"
Write-Host "  $sidecarDir"
Write-Host ""
Write-Host "Tauri integration note:"
Write-Host "  Copy the whole directory contents next to the frontend sidecar binary, not only the .exe."
Write-Host "  The .exe expects the generated app/ and runtime/ directories beside it."
