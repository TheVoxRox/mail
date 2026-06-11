param(
    [Parameter(Mandatory = $true)]
    [string[]] $Path,
    [string] $CertificateThumbprint = $env:WINDOWS_CERTIFICATE_THUMBPRINT,
    [string] $DigestAlgorithm = $env:WINDOWS_DIGEST_ALGORITHM,
    [string] $TimestampUrl = $env:WINDOWS_TIMESTAMP_URL
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($CertificateThumbprint)) {
    throw "WINDOWS_CERTIFICATE_THUMBPRINT is required."
}

if ([string]::IsNullOrWhiteSpace($DigestAlgorithm)) {
    $DigestAlgorithm = "sha256"
}

if ([string]::IsNullOrWhiteSpace($TimestampUrl)) {
    $TimestampUrl = "http://timestamp.digicert.com"
}

$windowsKitsRoot = Join-Path ${env:ProgramFiles(x86)} "Windows Kits\10\bin"
$signtool = Get-ChildItem -LiteralPath $windowsKitsRoot -Recurse -Filter signtool.exe |
    Where-Object { $_.FullName -match "\\x64\\signtool\.exe$" } |
    Sort-Object FullName -Descending |
    Select-Object -First 1

if ($null -eq $signtool) {
    throw "signtool.exe was not found under $windowsKitsRoot."
}

foreach ($item in $Path) {
    $resolved = (Resolve-Path -LiteralPath $item).Path
    Write-Host "Signing $resolved"
    & $signtool.FullName sign `
        /sha1 $CertificateThumbprint `
        /fd $DigestAlgorithm `
        /tr $TimestampUrl `
        /td $DigestAlgorithm `
        $resolved

    if ($LASTEXITCODE -ne 0) {
        throw "signtool failed for $resolved with exit code $LASTEXITCODE."
    }

    $signature = Get-AuthenticodeSignature -LiteralPath $resolved
    if ($signature.Status -ne "Valid") {
        throw "Signature verification failed for ${resolved}: $($signature.Status) $($signature.StatusMessage)"
    }
}
