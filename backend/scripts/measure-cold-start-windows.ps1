<#
.SYNOPSIS
    Cold start of the backend sidecar jar, measured per JVM-flag variant.

.DESCRIPTION
    The metric is the one PERFORMANCE_BASELINE.md has used since 2026-05-19:
    wall clock from JVM process start to the `.ready` file appearing in a fresh
    APP_DATA_DIR, median over N rounds.

    Variants are measured INTERLEAVED, with the order flipped every round. A
    block design (all of A, then all of B) hands the machine's load drift to
    whichever variant ran second, which matters here because the differences
    under test are single-digit percentages.

    Three traps this script exists to avoid, each of which cost a run:
      - a redirected stdout/stderr nobody drains fills after a few KB of Spring
        console output and the JVM blocks mid-boot, which reads as a slow start
        rather than as a deadlock. Output is therefore left inherited, and
        diagnosis comes from the data dir's own mail.log.
      - CryptoProperties rejects a key under 32 characters, and the failure
        surfaces as a bean-binding error deep in the context refresh.
      - a median difference alone proves nothing at these effect sizes; the
        Mann-Whitney z below is what decides.

.EXAMPLE
    # From backend/, after building the jar and the AOT cache:
    #   .\scripts\package-sidecar-dev-windows.ps1 -SkipTests -EnableAotCache
    .\scripts\measure-cold-start-windows.ps1 -Runs 15

.NOTES
    Results belong in PERFORMANCE_BASELINE.md as a dated section, with the
    machine's state noted: absolute numbers move by 15 % between two runs an
    hour apart on the same laptop, so only the within-run comparison carries.
#>

param(
    [int] $Runs = 15,
    [string] $JarPath,
    [string] $CachePath,
    [int] $TimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'

$backendDir = Split-Path -Parent $PSScriptRoot

if (-not $JarPath) {
    $jar = Get-ChildItem (Join-Path $backendDir 'target') -Filter 'mail-backend-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
    if (-not $jar) {
        throw "No mail-backend-*.jar in $backendDir\target. Build it first: mvn -Paot package"
    }
    $JarPath = $jar.FullName
}

if (-not $CachePath) {
    $CachePath = Join-Path $backendDir 'target\sidecar\x86_64-pc-windows-msvc\app\mail.aot'
}

if (-not (Test-Path $JarPath)) { throw "Jar not found: $JarPath" }
if (-not (Test-Path $CachePath)) {
    throw "AOT cache not found: $CachePath. Build it: .\scripts\package-sidecar-dev-windows.ps1 -SkipTests -EnableAotCache"
}

$baseFlags = @(
    '--enable-native-access=ALL-UNNAMED',
    '-Dfile.encoding=UTF-8',
    '-Dspring.aot.enabled=true',
    '-Xms64m',
    '-Xmx384m',
    '-XX:+UseSerialGC'
)

$variants = [ordered]@{
    'AOT + TieredStopAtLevel=1 (shipped)' = $baseFlags + @("-XX:AOTCache=$CachePath", '-XX:TieredStopAtLevel=1')
    'AOT, full tiered (flag removed)'     = $baseFlags + @("-XX:AOTCache=$CachePath")
    'No AOT cache + TieredStopAtLevel=1'  = $baseFlags + @('-XX:TieredStopAtLevel=1')
}

function Get-LogTail {
    param([string] $DataDir)
    $log = Join-Path $DataDir 'logs\mail.log'
    if (-not (Test-Path $log)) { return "(no mail.log in $DataDir)" }
    return "last log lines:`n" + ((Get-Content $log -Tail 8) -join "`n")
}

function Measure-One {
    param([string[]] $JvmArgs)

    $dataDir = Join-Path $env:TEMP ("voxrox-coldstart-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Path $dataDir | Out-Null
    $readyFile = Join-Path $dataDir '.ready'

    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'java'
    foreach ($a in $JvmArgs) { $psi.ArgumentList.Add($a) }
    $psi.ArgumentList.Add('-jar')
    $psi.ArgumentList.Add($JarPath)
    $psi.EnvironmentVariables['APP_DATA_DIR'] = $dataDir
    $psi.EnvironmentVariables['MAIL_CRYPTO_KEY'] = 'measurement-only-key-0123456789-not-a-secret'
    $psi.EnvironmentVariables['MAIL_CRYPTO_SALT'] = 'measurement-only-salt-0123456789-not-a-secret'
    $psi.UseShellExecute = $false

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $proc = [Diagnostics.Process]::Start($psi)
    try {
        while (-not (Test-Path $readyFile)) {
            if ($sw.Elapsed.TotalSeconds -gt $TimeoutSeconds) {
                throw "Timed out waiting for .ready after $TimeoutSeconds s.`n$(Get-LogTail $dataDir)"
            }
            if ($proc.HasExited) {
                throw "JVM exited ($($proc.ExitCode)) before .ready.`n$(Get-LogTail $dataDir)"
            }
            Start-Sleep -Milliseconds 20
        }
        $sw.Stop()
        return [int] $sw.Elapsed.TotalMilliseconds
    } finally {
        if (-not $proc.HasExited) {
            & taskkill /PID $proc.Id /T /F 2>&1 | Out-Null
        }
        Start-Sleep -Milliseconds 400
        Remove-Item -Recurse -Force $dataDir -ErrorAction SilentlyContinue
    }
}

function Compare-Variant {
    param($Baseline, $Other, [string] $BaselineName, [string] $OtherName)

    $delta = $Other.Median - $Baseline.Median
    $pct = [Math]::Round(100.0 * $delta / $Baseline.Median, 1)

    $all = @()
    foreach ($v in $Baseline.Samples) { $all += [pscustomobject]@{ Value = $v; Group = 'a' } }
    foreach ($v in $Other.Samples) { $all += [pscustomobject]@{ Value = $v; Group = 'b' } }
    $rank = 1
    $rankSumA = 0.0
    foreach ($item in ($all | Sort-Object Value)) {
        if ($item.Group -eq 'a') { $rankSumA += $rank }
        $rank++
    }
    $na = $Baseline.Samples.Count
    $nb = $Other.Samples.Count
    $uA = $rankSumA - ($na * ($na + 1) / 2)
    $u = [Math]::Min($uA, ($na * $nb) - $uA)
    $meanU = $na * $nb / 2.0
    $sdU = [Math]::Sqrt($na * $nb * ($na + $nb + 1) / 12.0)
    $z = if ($sdU -gt 0) { [Math]::Round(($u - $meanU) / $sdU, 2) } else { 0 }

    Write-Host ("{0}`n  vs baseline ({1}): {2:+#;-#;0} ms ({3} %), U={4}, z={5} (|z| >= 1.96 = significant at 5 %)" -f `
            $OtherName, $BaselineName, $delta, $pct, $u, $z)
}

$names = @($variants.Keys)
$collected = @{}
foreach ($n in $names) { $collected[$n] = @() }

for ($i = 1; $i -le $Runs; $i++) {
    $order = if ($i % 2 -eq 1) { $names } else { $names[($names.Count - 1)..0] }
    foreach ($name in $order) {
        $ms = Measure-One -JvmArgs $variants[$name]
        $collected[$name] += $ms
        Write-Host ("round {0,-3} {1,-38} {2} ms" -f $i, $name, $ms)
    }
}

$results = [ordered]@{}
foreach ($name in $names) {
    $sorted = $collected[$name] | Sort-Object
    $results[$name] = [pscustomobject]@{
        Samples = $collected[$name]
        Median  = $sorted[[int][Math]::Floor($sorted.Count / 2)]
        Min     = $sorted[0]
        Max     = $sorted[-1]
    }
}

Write-Host ''
Write-Host 'Medians:'
foreach ($name in $names) {
    $r = $results[$name]
    Write-Host ("  {0,-38} median {1} ms (min {2}, max {3}, n={4})" -f $name, $r.Median, $r.Min, $r.Max, $r.Samples.Count)
}

Write-Host ''
foreach ($other in $names[1..($names.Count - 1)]) {
    Compare-Variant -Baseline $results[$names[0]] -Other $results[$other] -BaselineName $names[0] -OtherName $other
}
