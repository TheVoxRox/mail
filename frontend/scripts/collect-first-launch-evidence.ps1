<#
.SYNOPSIS
    Evidence for the "no window on the first launch after a fresh install"
    finding on the release candidate sheet.

.DESCRIPTION
    Observes, and never acts: it installs nothing, starts nothing and deletes
    nothing. The maintainer installs the candidate and starts it by hand, which
    is what RELEASE_CHECKLIST section 3 asks for; this script runs alongside and
    keeps what the incident on 2026-09-10 did not have.

    That incident cost the evidence twice over. The process ran for about three
    minutes with no window, an uninstall and reinstall fixed it, and the logs
    went with the uninstall -- so the two hypotheses on the sheet, WebView2 still
    installing and Microsoft Defender, both stayed unconfirmed. A fresh install
    happens once per machine, so the run that reproduces it is the only chance
    to read it.

    What it keeps, and why each item can decide something:
      - The Tauri log, copied before any uninstall, and scanned for
        "failed to create webview". That string is tauri-runtime 2.11's Display
        for Error::CreateWebview, and the known wry 0.55.1 bug (wry#1798, fixed
        in Tauri 2.12.0) ends in it with an inner 0x80070057. That bug has the
        same symptom as the finding -- process alive, tray icon, no window, only
        Task Manager gets you out -- and it was found on 2026-09-17, a week
        after the incident, so it is not among the hypotheses on the sheet. One
        line in the log separates it from the other two.
      - The WebView2 inventory, taken before AND after the observation window.
        A runtime version whose directory appears during the window is the
        WebView2 hypothesis confirmed, rather than argued about.
      - A Microsoft Defender performance recording spanning the install and the
        first launch, which is the only way to see the antivirus hypothesis. It
        needs an elevated session; without one the script says so and carries on
        with everything else.
      - When a visible window appeared, to the poll interval, next to when the
        sidecar process and the data directory markers appeared. "No window"
        and "no backend" look identical from a chair and are different bugs.
      - Whether a system Java is on PATH, which is the open half of section 1:
        the candidate has to run on a profile without one.

    It copies the logs, session.json and directory listings. It deliberately
    does not copy db/, attachments/ or crypto.bin: mail content and the key
    protecting it are not evidence about a missing window, and this output
    tends to get attached to an issue.

.EXAMPLE
    # In an elevated PowerShell 7 window, BEFORE running the installer:
    pwsh -NoProfile -File frontend\scripts\collect-first-launch-evidence.ps1
    # Then install the candidate and start it while this runs.

.EXAMPLE
    # Rehearsal on a machine that already has the app, against a process that
    # is about to be started by hand. The real run happens once, so the run
    # that has to work is worth rehearsing.
    pwsh -NoProfile -File frontend\scripts\collect-first-launch-evidence.ps1 `
        -ProcessName notepad -Minutes 2 -SkipDefenderRecording

.NOTES
    The output is words rather than symbols, because it is read with a screen
    reader. The findings belong on the candidate sheet in
    backend/RELEASE_CHECKLIST.md, under the finding this script serves.

    Two traps around the Defender recording, both found while rehearsing this
    script rather than during the run that matters:
      - New-MpPerformanceRecording drives wpr.exe and dies with "Cannot find
        dependency command" when PATH does not carry System32. The failure
        happens inside the child process, minutes in, where nobody is looking,
        so the path is resolved here and handed over as -WPRPath.
      - A recording that starts and writes no trace must not be summarised as
        one that was taken. The summary therefore states what is on disk, not
        what was asked for -- the run cannot be repeated on that machine.
#>

param(
    # The whole observation window: the Defender recording covers exactly this
    # much, and it cannot be stopped early, so the run takes this long unless
    # the recording is skipped.
    [int] $Minutes = 10,
    [string] $OutputRoot,
    # Parallel data root, as tauri:dev uses ('.dev'). Empty is the installed app.
    [string] $DataSuffix = '',
    [switch] $SkipDefenderRecording,
    # The installed desktop shell. A different name rehearses the collector
    # against any window-owning process; see the second example.
    [string] $ProcessName = 'voxrox-mail',
    [string] $BackendProcessName = 'voxrox-mail-backend',
    [int] $PollMilliseconds = 500
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# The output is English and gets pasted into an issue, and timeline.csv has a
# decimal number in a comma-separated file: on a Czech machine "2,4" lands in
# it and reads as two fields. Invariant formatting for the whole run settles
# both.
[System.Threading.Thread]::CurrentThread.CurrentCulture = [System.Globalization.CultureInfo]::InvariantCulture

$WEBVIEW2_CLIENT_KEY = '{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}'

function Get-ElevationState {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal] $identity
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-RegistryValue {
    param([string] $Path, [string] $Name)

    $item = Get-ItemProperty -Path $Path -Name $Name -ErrorAction SilentlyContinue
    if ($null -eq $item) { return $null }
    return $item.$Name
}

# Both scopes and the registry, because the three disagree in exactly the case
# this is about: a runtime installed minutes ago by the app's own installer.
function Get-WebView2Inventory {
    $directories = @()
    $roots = @(
        "${env:ProgramFiles(x86)}\Microsoft\EdgeWebView\Application",
        "$env:ProgramFiles\Microsoft\EdgeWebView\Application",
        "$env:LOCALAPPDATA\Microsoft\EdgeWebView\Application"
    )

    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        foreach ($dir in (Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue)) {
            # Version directories only; SetupMetrics and friends are not runtimes.
            if ($dir.Name -notmatch '^\d+(\.\d+)+$') { continue }
            $directories += [pscustomobject]@{
                root          = $root
                version       = $dir.Name
                creationTime  = $dir.CreationTime.ToString('o')
                lastWriteTime = $dir.LastWriteTime.ToString('o')
            }
        }
    }

    $registry = @()
    $keys = @(
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\$WEBVIEW2_CLIENT_KEY",
        "HKLM:\SOFTWARE\Microsoft\EdgeUpdate\Clients\$WEBVIEW2_CLIENT_KEY",
        "HKCU:\Software\Microsoft\EdgeUpdate\Clients\$WEBVIEW2_CLIENT_KEY"
    )
    foreach ($key in $keys) {
        $version = Get-RegistryValue -Path $key -Name 'pv'
        if ($null -ne $version) {
            $registry += [pscustomobject]@{ key = $key; pv = $version }
        }
    }

    return [pscustomobject]@{ directories = $directories; registry = $registry }
}

function Get-DefenderState {
    $status = $null
    $preference = $null

    try {
        $raw = Get-MpComputerStatus -ErrorAction Stop
        $status = [pscustomobject]@{
            amRunningMode              = $raw.AMRunningMode
            amEngineVersion            = $raw.AMEngineVersion
            realTimeProtectionEnabled  = $raw.RealTimeProtectionEnabled
            behaviorMonitorEnabled     = $raw.BehaviorMonitorEnabled
            antivirusSignatureLastUpdated = $raw.AntivirusSignatureLastUpdated
        }
    } catch {
        $status = "Get-MpComputerStatus failed: $($_.Exception.Message)"
    }

    try {
        $raw = Get-MpPreference -ErrorAction Stop
        $preference = [pscustomobject]@{
            disableRealtimeMonitoring = $raw.DisableRealtimeMonitoring
            exclusionPath             = @($raw.ExclusionPath)
            exclusionProcess          = @($raw.ExclusionProcess)
            exclusionExtension        = @($raw.ExclusionExtension)
        }
    } catch {
        $preference = "Get-MpPreference failed: $($_.Exception.Message)"
    }

    return [pscustomobject]@{ status = $status; preference = $preference }
}

function Get-ProcessSnapshot {
    param([string] $Name)

    $processes = @(Get-Process -Name $Name -ErrorAction SilentlyContinue)
    if ($processes.Count -eq 0) {
        return [pscustomobject]@{
            running      = $false
            pid          = 0
            windowHandle = 0
            windowTitle  = ''
            responding   = $false
        }
    }

    # MainWindowHandle is the top-level VISIBLE window, which is the whole
    # question here: a process showing only a tray icon reports zero.
    $withWindow = $processes | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
    $chosen = if ($null -ne $withWindow) { $withWindow } else { $processes[0] }

    return [pscustomobject]@{
        running      = $true
        pid          = $chosen.Id
        windowHandle = [int64] $chosen.MainWindowHandle
        windowTitle  = $chosen.MainWindowTitle
        responding   = $chosen.Responding
    }
}

# New-MpPerformanceRecording drives wpr.exe (Windows Performance Recorder) and
# fails with "Cannot find dependency command 'wpr.exe'" when it cannot resolve
# it -- inside the child process, minutes into the run, where nobody is looking.
# It ships in System32, so a PATH that does not carry System32 is enough to
# lose the recording; the cmdlet's own -WPRPath takes the resolved path and
# removes the question.
function Resolve-WprPath {
    $onPath = Get-Command 'wpr.exe' -ErrorAction SilentlyContinue
    if ($null -ne $onPath) { return $onPath.Source }

    $inSystem32 = Join-Path $env:SystemRoot 'System32\wpr.exe'
    if (Test-Path -LiteralPath $inSystem32) { return $inSystem32 }

    return $null
}

function Format-Seconds {
    param([double] $Value)
    return [math]::Round($Value, 1)
}

function Format-Minutes {
    param([int] $Value)
    if ($Value -eq 1) { return '1 minute' }
    return "$Value minutes"
}

$startedAt = Get-Date

if (-not $OutputRoot) {
    $OutputRoot = Join-Path $HOME 'voxrox-first-launch-evidence'
}
$outputDir = Join-Path $OutputRoot $startedAt.ToString('yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

$dataDir = Join-Path $env:LOCALAPPDATA "VoxRox\Mail$DataSuffix"
$installDir = Join-Path $env:LOCALAPPDATA 'Programs\VoxRox\Mail'
$elevated = Get-ElevationState
$javaCommand = Get-Command java -ErrorAction SilentlyContinue

Write-Host "First-launch evidence collector"
Write-Host "Writing to: $outputDir"
Write-Host "Data directory watched: $dataDir"
Write-Host ""

# --- Environment, before anything happens -----------------------------------

$webViewBefore = Get-WebView2Inventory

$operatingSystem = Get-CimInstance Win32_OperatingSystem

$environment = [pscustomobject]@{
    collectedAt        = $startedAt.ToString('o')
    computerName       = $env:COMPUTERNAME
    osCaption          = $operatingSystem.Caption
    osVersion          = [System.Environment]::OSVersion.Version.ToString()
    osBuild            = $operatingSystem.BuildNumber
    processorName      = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name
    memoryGb           = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
    powerShellVersion  = $PSVersionTable.PSVersion.ToString()
    elevated           = $elevated
    javaOnPath         = if ($null -ne $javaCommand) { $javaCommand.Source } else { $null }
    dataDirectory      = $dataDir
    dataDirectoryExists = Test-Path -LiteralPath $dataDir
    installDirectory   = $installDir
    installDirectoryExists = Test-Path -LiteralPath $installDir
    webView2           = $webViewBefore
    defender           = Get-DefenderState
}

$environment | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $outputDir 'environment.json') -Encoding utf8

$environmentLines = @()
$environmentLines += "Collected at $($environment.collectedAt) on $($environment.computerName)."
$environmentLines += "$($environment.osCaption), build $($environment.osBuild), $($environment.processorName), $($environment.memoryGb) GB."
$environmentLines += "PowerShell $($environment.powerShellVersion), elevated: $($environment.elevated)."
if ($null -ne $environment.javaOnPath) {
    $environmentLines += "A system Java is on PATH at $($environment.javaOnPath). Section 1 asks for a profile without one."
} else {
    $environmentLines += "No java on PATH, which is what section 1 asks of the clean profile."
}
$environmentLines += "Data directory $dataDir exists: $($environment.dataDirectoryExists)."
$environmentLines += "Installation directory $installDir exists: $($environment.installDirectoryExists)."
foreach ($entry in $webViewBefore.directories) {
    $environmentLines += "WebView2 $($entry.version) under $($entry.root), directory created $($entry.creationTime)."
}
foreach ($entry in $webViewBefore.registry) {
    $environmentLines += "WebView2 registry $($entry.key) reports version $($entry.pv)."
}
$environmentLines | Set-Content -LiteralPath (Join-Path $outputDir 'environment.txt') -Encoding utf8

foreach ($line in $environmentLines) { Write-Host $line }
Write-Host ""

if ($environment.dataDirectoryExists -or $environment.installDirectoryExists) {
    Write-Host "Warning: this is not a clean profile. The finding is about the FIRST launch after an install onto a profile that has neither directory."
    Write-Host ""
}

# --- Defender recording, if this session can take one ------------------------

$recordingPath = Join-Path $outputDir 'defender-recording.etl'
$recordingProcess = $null
$recordingNote = ''
$recordingSeconds = $Minutes * 60

$wprPath = Resolve-WprPath

if ($SkipDefenderRecording) {
    $recordingNote = 'Skipped on request.'
} elseif (-not (Get-Command New-MpPerformanceRecording -ErrorAction SilentlyContinue)) {
    $recordingNote = 'Not taken: New-MpPerformanceRecording is not available on this machine.'
} elseif ($null -eq $wprPath) {
    $recordingNote = 'Not taken: wpr.exe, the Windows Performance Recorder the cmdlet drives, was found neither on PATH nor in System32.'
} elseif (-not $elevated) {
    $recordingNote = "Not taken: this session is not elevated. In an elevated window: New-MpPerformanceRecording -RecordTo '$recordingPath' -Seconds $recordingSeconds"
} else {
    # A child process, because the cmdlet blocks for the whole recording and
    # this script has to poll meanwhile. It cannot be stopped early, which is
    # why the recording length is the observation window.
    $command = "New-MpPerformanceRecording -RecordTo '$recordingPath' -Seconds $recordingSeconds -WPRPath '$wprPath'"
    $recordingProcess = Start-Process -FilePath 'pwsh' `
        -ArgumentList '-NoProfile', '-NonInteractive', '-Command', $command `
        -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $outputDir 'defender-recording.out.txt') `
        -RedirectStandardError (Join-Path $outputDir 'defender-recording.err.txt')
    $recordingNote = "Started, for $recordingSeconds seconds into defender-recording.etl."
}

Write-Host "Defender recording: $recordingNote"
Write-Host ""
Write-Host "Install the candidate and start it now. Observing for $(Format-Minutes $Minutes), until $($startedAt.AddMinutes($Minutes).ToString('HH:mm:ss'))."
Write-Host ""

# --- Observation -------------------------------------------------------------

$timelinePath = Join-Path $outputDir 'timeline.csv'
$timeline = @()
$deadline = $startedAt.AddMinutes($Minutes)
$lastState = $null
$lastHeartbeat = $startedAt

$shellFirstSeen = $null
$windowFirstSeen = $null
$backendFirstSeen = $null
$readyFirstSeen = $null

# What was already there when the collector started. The maintainer may well
# start it after launching the app, and "appeared after 2 seconds" would then
# be a measurement of this script's own startup rather than of the app.
$initialShell = Get-ProcessSnapshot -Name $ProcessName
$initialBackend = Get-ProcessSnapshot -Name $BackendProcessName
$shellAtStart = $initialShell.running
$windowAtStart = $initialShell.windowHandle -ne 0
$backendAtStart = $initialBackend.running
$readyAtStart = Test-Path -LiteralPath (Join-Path $dataDir '.ready')

if ($shellAtStart) {
    $shellFirstSeen = $startedAt
    Write-Host "The shell process $ProcessName was already running when the collector started."
}
if ($windowAtStart) {
    $windowFirstSeen = $startedAt
    Write-Host "A visible window was already there when the collector started."
}
if ($backendAtStart) {
    $backendFirstSeen = $startedAt
    Write-Host "The sidecar process $BackendProcessName was already running when the collector started."
}
if ($readyAtStart) {
    $readyFirstSeen = $startedAt
    Write-Host "The data directory already had a .ready file when the collector started."
}

function Add-TimelineRow {
    param([string] $Event, [datetime] $At, $Shell, $Backend, [bool] $Ready, [bool] $Session)

    $script:timeline += [pscustomobject]@{
        elapsedSeconds = Format-Seconds ($At - $startedAt).TotalSeconds
        event          = $Event
        shellPid       = $Shell.pid
        windowHandle   = $Shell.windowHandle
        windowTitle    = $Shell.windowTitle
        responding     = $Shell.responding
        backendPid     = $Backend.pid
        readyFile      = $Ready
        sessionJson    = $Session
    }
}

try {
    while ((Get-Date) -lt $deadline) {
        $now = Get-Date
        $shell = Get-ProcessSnapshot -Name $ProcessName
        $backend = Get-ProcessSnapshot -Name $BackendProcessName
        $ready = Test-Path -LiteralPath (Join-Path $dataDir '.ready')
        $session = Test-Path -LiteralPath (Join-Path $dataDir 'session.json')

        $state = "$($shell.running)|$($shell.windowHandle -ne 0)|$($shell.responding)|$($backend.running)|$ready|$session"

        if ($null -eq $shellFirstSeen -and $shell.running) {
            $shellFirstSeen = $now
            Write-Host "The shell process $ProcessName appeared after $(Format-Seconds ($now - $startedAt).TotalSeconds) seconds."
        }
        if ($null -eq $windowFirstSeen -and $shell.windowHandle -ne 0) {
            $windowFirstSeen = $now
            Write-Host "A visible window appeared after $(Format-Seconds ($now - $startedAt).TotalSeconds) seconds."
        }
        if ($null -eq $backendFirstSeen -and $backend.running) {
            $backendFirstSeen = $now
            Write-Host "The sidecar process $BackendProcessName appeared after $(Format-Seconds ($now - $startedAt).TotalSeconds) seconds."
        }
        if ($null -eq $readyFirstSeen -and $ready) {
            $readyFirstSeen = $now
            Write-Host "The data directory has a .ready file after $(Format-Seconds ($now - $startedAt).TotalSeconds) seconds."
        }

        if ($state -ne $lastState) {
            Add-TimelineRow -Event 'change' -At $now -Shell $shell -Backend $backend -Ready $ready -Session $session
            $lastState = $state
            $lastHeartbeat = $now
        } elseif (($now - $lastHeartbeat).TotalSeconds -ge 30) {
            # A heartbeat keeps "nothing happened for three minutes" in the
            # record, which is the observation the finding is about.
            Add-TimelineRow -Event 'heartbeat' -At $now -Shell $shell -Backend $backend -Ready $ready -Session $session
            $lastHeartbeat = $now
        }

        Start-Sleep -Milliseconds $PollMilliseconds
    }
} finally {
    # Runs on Ctrl+C too: the logs and the summary are the point of the run,
    # and the maintainer may well stop once they have seen enough.
    $endedAt = Get-Date

    if ($timeline.Count -gt 0) {
        $timeline | Export-Csv -LiteralPath $timelinePath -NoTypeInformation -Encoding utf8
    }

    if ($null -ne $recordingProcess -and -not $recordingProcess.HasExited) {
        $remaining = [math]::Max(0, $recordingSeconds - ($endedAt - $startedAt).TotalSeconds)
        Write-Host ""
        Write-Host "Waiting up to $([math]::Ceiling($remaining) + 60) seconds for the Defender recording to finish writing the trace."
        try {
            Wait-Process -Id $recordingProcess.Id -Timeout ([int] ($remaining + 60)) -ErrorAction Stop
        } catch {
            Write-Host "The Defender recording did not finish in time: $($_.Exception.Message)"
        }
    }

    # --- What the run leaves behind -----------------------------------------

    $logsOut = Join-Path $outputDir 'logs'
    New-Item -ItemType Directory -Path $logsOut -Force | Out-Null

    $logSource = Join-Path $dataDir 'logs'
    $copiedLogs = @()
    if (Test-Path -LiteralPath $logSource) {
        foreach ($file in (Get-ChildItem -LiteralPath $logSource -File -ErrorAction SilentlyContinue)) {
            Copy-Item -LiteralPath $file.FullName -Destination $logsOut -Force
            $copiedLogs += $file.Name
        }
    }
    $sessionFile = Join-Path $dataDir 'session.json'
    if (Test-Path -LiteralPath $sessionFile) {
        Copy-Item -LiteralPath $sessionFile -Destination $logsOut -Force
        $copiedLogs += 'session.json'
    }

    foreach ($pair in @(@{ Path = $dataDir; Name = 'data-directory-listing.txt' },
                        @{ Path = $installDir; Name = 'install-directory-listing.txt' })) {
        $listing = if (Test-Path -LiteralPath $pair.Path) {
            Get-ChildItem -LiteralPath $pair.Path -Recurse -ErrorAction SilentlyContinue |
                Select-Object FullName, Length, CreationTime, LastWriteTime |
                Format-Table -AutoSize | Out-String -Width 400
        } else {
            "$($pair.Path) does not exist."
        }
        $listing | Set-Content -LiteralPath (Join-Path $outputDir $pair.Name) -Encoding utf8
    }

    # The one line that can settle the finding on its own.
    $frontendLog = Join-Path $logsOut 'mail-frontend.log'
    $webviewFailures = @()
    $errorLines = @()
    if (Test-Path -LiteralPath $frontendLog) {
        $logLines = Get-Content -LiteralPath $frontendLog -ErrorAction SilentlyContinue
        $webviewFailures = @($logLines | Where-Object { $_ -match 'failed to create webview|0x80070057' })
        $errorLines = @($logLines | Where-Object { $_ -match '\bERROR\b' })
    }

    $webViewAfter = Get-WebView2Inventory
    $beforeVersions = @($webViewBefore.directories | ForEach-Object { "$($_.root)|$($_.version)" })
    $newRuntimes = @($webViewAfter.directories | Where-Object { $beforeVersions -notcontains "$($_.root)|$($_.version)" })
    $webViewAfter | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $outputDir 'webview2-after.json') -Encoding utf8

    $summary = @()
    $summary += "First-launch evidence, $($startedAt.ToString('o')) to $($endedAt.ToString('o')) on $($environment.computerName)."
    $summary += "Observed process: $ProcessName, sidecar: $BackendProcessName, data directory: $dataDir."
    $summary += ''

    if ($shellAtStart) {
        $summary += "The shell process was already running when the collector started, so nothing here measures the time from the launch."
    } elseif ($null -eq $shellFirstSeen) {
        $summary += "The shell process never appeared during the window. Either the install or the start did not happen while this ran, and the rest of this summary says nothing about the finding."
    } else {
        $summary += "The shell process appeared after $(Format-Seconds ($shellFirstSeen - $startedAt).TotalSeconds) seconds."
    }

    if ($null -ne $shellFirstSeen) {
        if ($windowAtStart) {
            $summary += "A visible window was already there when the collector started."
        } elseif ($null -eq $windowFirstSeen) {
            $summary += "NO VISIBLE WINDOW appeared in the $(Format-Minutes $Minutes) observed. This is the finding reproducing."
        } else {
            $delay = ($windowFirstSeen - $shellFirstSeen).TotalSeconds
            $summary += "A visible window appeared $(Format-Seconds $delay) seconds after the process, at $(Format-Seconds ($windowFirstSeen - $startedAt).TotalSeconds) seconds into the run."
        }

        if ($backendAtStart) {
            $summary += "The sidecar process was already running when the collector started."
        } elseif ($null -ne $backendFirstSeen) {
            $summary += "The sidecar process appeared after $(Format-Seconds ($backendFirstSeen - $startedAt).TotalSeconds) seconds."
        } else {
            $summary += "The sidecar process was never seen, which is a different bug from a missing window."
        }

        if ($readyAtStart) {
            $summary += "The data directory already had a .ready file when the collector started, so this was not a first launch."
        } elseif ($null -ne $readyFirstSeen) {
            $summary += "The .ready file appeared after $(Format-Seconds ($readyFirstSeen - $startedAt).TotalSeconds) seconds."
        } else {
            $summary += "No .ready file appeared, so the backend did not reach readiness."
        }
    }
    $summary += ''

    if ($webviewFailures.Count -gt 0) {
        $summary += "The Tauri log reports a webview that failed to be created. That is the known wry bug (wry#1798, fixed in Tauri 2.12.0), not WebView2 and not Defender:"
        foreach ($line in $webviewFailures) { $summary += "    $line" }
    } elseif (Test-Path -LiteralPath $frontendLog) {
        $summary += "The Tauri log has no webview creation failure in it, so the known wry bug is not what happened."
    } else {
        $summary += "No Tauri log was found under $logSource, so the webview creation failure could not be looked for."
    }
    if ($errorLines.Count -gt 0) {
        $summary += "Other error lines in the Tauri log: $($errorLines.Count). They are in logs/mail-frontend.log."
    }
    $summary += ''

    if ($newRuntimes.Count -gt 0) {
        $summary += "A WebView2 runtime directory appeared DURING the window, which is the WebView2 hypothesis confirmed:"
        foreach ($entry in $newRuntimes) {
            $summary += "    $($entry.version) under $($entry.root), created $($entry.creationTime)"
        }
    } else {
        $summary += "No new WebView2 runtime directory appeared during the window."
    }
    $summary += ''

    if ($null -eq $recordingProcess) {
        $summary += "Defender recording: $recordingNote"
    } elseif (Test-Path -LiteralPath $recordingPath) {
        $summary += "Defender recording: written to defender-recording.etl."
        $summary += "Read it with: Get-MpPerformanceReport -Path '$recordingPath' -TopProcesses 20 -TopFiles 20"
    } else {
        # A recording that was started but produced no trace must not be
        # summarised as one that was taken; the run cannot be repeated.
        $failure = ''
        $errorFile = Join-Path $outputDir 'defender-recording.err.txt'
        if (Test-Path -LiteralPath $errorFile) {
            $failure = (@(Get-Content -LiteralPath $errorFile | Where-Object { $_.Trim().Length -gt 0 }) | Select-Object -First 1)
        }
        $childState = if ($recordingProcess.HasExited) { "exited with code $($recordingProcess.ExitCode)" } else { 'is still running' }
        $summary += "Defender recording: STARTED BUT NO TRACE WAS WRITTEN. The child $childState. See defender-recording.err.txt."
        if ($failure) { $summary += "    $failure" }
    }
    $summary += ''
    if ($copiedLogs.Count -eq 0) {
        $summary += "Copied nothing: the log directory was empty or absent."
    } elseif ($copiedLogs.Count -le 8) {
        $summary += "Copied into logs: $($copiedLogs -join ', ')."
    } else {
        # A clean profile has a handful; a long list means the profile carried
        # older runs, which the warning above has already said.
        $summary += "Copied $($copiedLogs.Count) files into logs, including $(($copiedLogs | Select-Object -First 4) -join ', ')."
    }
    $summary += "Not copied on purpose: db/, attachments/ and crypto.bin. Mail content and its key are not evidence about a missing window."
    $summary += "Everything is under $outputDir."

    $summary | Set-Content -LiteralPath (Join-Path $outputDir 'summary.txt') -Encoding utf8

    Write-Host ""
    foreach ($line in $summary) { Write-Host $line }
}
