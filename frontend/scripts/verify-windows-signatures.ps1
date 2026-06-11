param(
    [string[]] $Path = @("src-tauri\target\release\bundle")
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$targets = @()

foreach ($item in $Path) {
    if (-not (Test-Path -LiteralPath $item)) {
        throw "Signature verification path does not exist: $item"
    }

    $resolved = (Resolve-Path -LiteralPath $item).Path
    $file = Get-Item -LiteralPath $resolved
    if ($file.PSIsContainer) {
        $targets += Get-ChildItem -LiteralPath $resolved -Recurse -File -Include *.exe
    } else {
        $targets += $file
    }
}

if ($targets.Count -eq 0) {
    throw "No .exe files found for signature verification."
}

foreach ($target in $targets) {
    $signature = Get-AuthenticodeSignature -LiteralPath $target.FullName
    if ($signature.Status -ne "Valid") {
        throw "Signature verification failed for $($target.FullName): $($signature.Status) $($signature.StatusMessage)"
    }
    Write-Host "Valid signature: $($target.FullName)"
}
