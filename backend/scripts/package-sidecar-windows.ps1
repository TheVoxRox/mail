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
    [switch] $EnableAotCache,
    # OAuth is required by default: the build fails if any OAuth client identifier
    # is missing or still the non-working "mail-local-*" placeholder. Shipping such
    # a build leaves OAuth login silently broken only in production — the
    # placeholder is sent to Google/Microsoft as the client_id and rejected with
    # invalid_client / "OAuth client was not found". Pass -AllowPlaceholderOAuth for
    # a local build that does not need a working OAuth login (packaging / AOT /
    # sidecar-lifecycle testing); it warns and ships the placeholder instead of
    # failing. Release packaging (windows-signed-release.yml) must never pass it.
    [switch] $AllowPlaceholderOAuth
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
    # -Paot enables the pom.xml profile that adds the spring-boot-maven-plugin
    # process-aot goal. The output is a jar with generated AOT classes; at
    # runtime they are activated by the -Dspring.aot.enabled=true JVM option
    # (see the jpackage invocation below).
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
    # By default a missing/placeholder value fails the build (see
    # -AllowPlaceholderOAuth); this prevents shipping a launcher that silently
    # falls back to the non-working placeholder and breaks OAuth only in
    # production.
    $script:oauthArgs = @()
    $oauthMappings = [ordered]@{
        "GOOGLE_OAUTH_CLIENT_ID"     = "spring.security.oauth2.client.registration.google.client-id"
        "GOOGLE_OAUTH_CLIENT_SECRET" = "spring.security.oauth2.client.registration.google.client-secret"
        "MICROSOFT_OAUTH_CLIENT_ID"  = "spring.security.oauth2.client.registration.microsoft.client-id"
    }

    foreach ($envName in $oauthMappings.Keys) {
        $value = [Environment]::GetEnvironmentVariable($envName)
        # An env var set to the application.properties placeholder is as broken
        # as an unset one — both leave OAuth non-functional in the shipped build.
        $isPlaceholder = (-not [string]::IsNullOrWhiteSpace($value)) -and ($value -like "mail-local-*")
        if ([string]::IsNullOrWhiteSpace($value) -or $isPlaceholder) {
            $reason = if ($isPlaceholder) { "is set to the non-working placeholder '$value'" } else { "is not set" }
            if (-not $AllowPlaceholderOAuth) {
                throw "OAuth client configuration is required for this build but $envName $reason. " +
                    "Set GOOGLE_OAUTH_CLIENT_ID, GOOGLE_OAUTH_CLIENT_SECRET and MICROSOFT_OAUTH_CLIENT_ID " +
                    "(CI secrets, or backend/.env via package-sidecar-dev-windows.ps1) before packaging, or " +
                    "pass -AllowPlaceholderOAuth for a local build without a working OAuth login."
            }
            Write-Warning "  $envName $reason; $($oauthMappings[$envName]) falls back to the placeholder and OAuth login will not work in this build."
            continue
        }
        $script:oauthArgs += "--java-options"
        $script:oauthArgs += "-D$($oauthMappings[$envName])=$value"
    }

    Write-Host "  Injecting $([int]($script:oauthArgs.Count / 2)) OAuth client value(s) into the launcher."
}

Invoke-Step "Creating Windows app-image sidecar with jpackage" {
    # JVM tuning for a single-user desktop sidecar:
    #   --enable-native-access=ALL-UNNAMED  Java 25 requires it for warning-free JNI.
    #   -Dfile.encoding=UTF-8               Consistent encoding across platforms.
    #   -XX:TieredStopAtLevel=1             C1 JIT only (no C2), ~10-15 % faster
    #                                       cold start; the sidecar does not need
    #                                       peak throughput like a server app.
    #   -Xms64m / -Xmx384m                  The sidecar is single-user for 1 mailbox;
    #                                       the default Xmx (1/4 RAM) is an order
    #                                       of magnitude more than needed and only
    #                                       prolongs GC pauses.
    #   -XX:+UseSerialGC                    1 GC thread = minimal startup overhead
    #                                       + small footprint, a good fit for a
    #                                       small heap and a low-throughput scenario.
    # AOT cache (JEP 483, Java 25) — path relative to the working directory of
    # the jpackage launcher, which is the app install dir. Default OFF (see the
    # -EnableAotCache parameter). The cache is generated by the "Generating AOT
    # class cache" step and copied into app/.
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
            throw "AOT cache was not generated: $cachePath"
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

Invoke-Step "Verifying OAuth client configuration in launcher" {
    # Defense in depth for the silent-prod-breakage class of bug: independently of
    # the bake step above, assert the *published* launcher .cfg actually carries a
    # non-placeholder Google client-id before the image is shipped. Without it the
    # runtime falls back to application.properties' "mail-local-*" placeholder and
    # Google rejects the login with invalid_client. -AllowPlaceholderOAuth opts a
    # local build out of this gate.
    if ($AllowPlaceholderOAuth) {
        Write-Host "  Skipped (-AllowPlaceholderOAuth)."
        return
    }
    $cfgPath = Join-Path $sidecarDir "app\$sidecarName.cfg"
    if (-not (Test-Path -LiteralPath $cfgPath)) {
        throw "Launcher config not found for OAuth verification: $cfgPath"
    }
    $cfg = Get-Content -LiteralPath $cfgPath -Raw
    $clientIdKey = "spring.security.oauth2.client.registration.google.client-id"
    if ($cfg -notmatch [regex]::Escape($clientIdKey)) {
        throw "Launcher $cfgPath does not bake $clientIdKey — OAuth login would fall back to the placeholder and fail in production. Package with the OAuth env vars set (CI secrets / package-sidecar-dev-windows.ps1)."
    }
    if ($cfg -match "mail-local-") {
        throw "Launcher $cfgPath still carries a 'mail-local-*' OAuth placeholder — OAuth login would fail in production."
    }
    Write-Host "  Google client-id baked into the launcher; no placeholder."
}

Write-Host ""
Write-Host "Sidecar image ready:"
Write-Host "  $sidecarDir"
Write-Host ""
Write-Host "Tauri integration note:"
Write-Host "  Copy the whole directory contents next to the frontend sidecar binary, not only the .exe."
Write-Host "  The .exe expects the generated app/ and runtime/ directories beside it."
