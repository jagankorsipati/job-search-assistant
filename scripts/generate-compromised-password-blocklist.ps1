[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$sourceUrl = 'https://raw.githubusercontent.com/danielmiessler/SecLists/2026.1/Passwords/Common-Credentials/10k-most-common.txt'
$expectedSourceSha256 = '4adb3f0afb4a10cf19ebe48d8c69a46f934bbc8d77c694c210564f9583e7f4ba'
$outputPath = Join-Path $repositoryRoot 'backend\src\main\resources\security\compromised-passwords-sha256.txt'
$temporaryPath = New-TemporaryFile

try {
    Invoke-WebRequest -Uri $sourceUrl -OutFile $temporaryPath
    $actualSourceSha256 = (Get-FileHash -Algorithm SHA256 $temporaryPath).Hash.ToLowerInvariant()
    if ($actualSourceSha256 -ne $expectedSourceSha256) {
        throw "Pinned password source checksum mismatch: $actualSourceSha256"
    }

    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $digests = Get-Content -LiteralPath $temporaryPath | ForEach-Object {
            $bytes = [Text.Encoding]::UTF8.GetBytes($_)
            ([BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
        } | Sort-Object -Unique
    }
    finally {
        $sha256.Dispose()
    }

    $header = @(
        '# job-search-assistant compromised-password blocklist v2026.1'
        '# source=SecLists 2026.1 Passwords/Common-Credentials/10k-most-common.txt'
        '# source_sha256=4adb3f0afb4a10cf19ebe48d8c69a46f934bbc8d77c694c210564f9583e7f4ba'
        '# license=MIT'
        '# generated=SHA-256(UTF-8 source line), lowercase hex, sorted unique'
    )
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null
    [IO.File]::WriteAllLines($outputPath, [string[]]($header + $digests), [Text.UTF8Encoding]::new($false))
}
finally {
    Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
}
