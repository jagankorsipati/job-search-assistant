[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$blocklistVersion = '2026.1'
$sourceCommit = '190c6f7bd58c847ceadfe57d9853592737f059e8'
$sourcePath = 'Passwords/Common-Credentials/10k-most-common.txt'
$sourceUrl = "https://raw.githubusercontent.com/danielmiessler/SecLists/$sourceCommit/$sourcePath"
$expectedSourceSha256 = '4adb3f0afb4a10cf19ebe48d8c69a46f934bbc8d77c694c210564f9583e7f4ba'
$expectedInputEntries = 10000
$expectedDigestEntries = 10000
$outputPath = Join-Path $repositoryRoot 'backend\src\main\resources\security\compromised-passwords-sha256.txt'
$temporaryPath = New-TemporaryFile

try {
    Invoke-WebRequest -Uri $sourceUrl -OutFile $temporaryPath
    $actualSourceSha256 = (Get-FileHash -Algorithm SHA256 $temporaryPath).Hash.ToLowerInvariant()
    if ($actualSourceSha256 -ne $expectedSourceSha256) {
        throw "Pinned password source checksum mismatch: $actualSourceSha256"
    }

    $sourceLines = @(Get-Content -LiteralPath $temporaryPath)
    if ($sourceLines.Count -ne $expectedInputEntries -or
        $sourceLines.Where({ [string]::IsNullOrEmpty($_) -or $_ -match '[\x00-\x08\x0B\x0C\x0E-\x1F]' }).Count -ne 0) {
        throw "Pinned password source is empty, malformed, or has an unexpected entry count ($($sourceLines.Count))."
    }

    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $digests = $sourceLines | ForEach-Object {
            $bytes = [Text.Encoding]::UTF8.GetBytes($_)
            ([BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
        } | Sort-Object -Unique
    }
    finally {
        $sha256.Dispose()
    }
    if ($digests.Count -ne $expectedDigestEntries -or $digests.Count -lt 9500) {
        throw "Generated blocklist has an unexpected digest count ($($digests.Count))."
    }
    if (@($digests | Select-Object -Unique).Count -ne $digests.Count) {
        throw 'Generated blocklist contains duplicate digests.'
    }
    if (@(Compare-Object $digests ($digests | Sort-Object)).Count -ne 0) {
        throw 'Generated blocklist is not sorted.'
    }

    $header = @(
        '# job-search-assistant compromised-password blocklist v2026.1'
        "# source=SecLists release $blocklistVersion commit $sourceCommit $sourcePath"
        '# source_sha256=4adb3f0afb4a10cf19ebe48d8c69a46f934bbc8d77c694c210564f9583e7f4ba'
        '# license=MIT'
        '# input_entries=10000'
        '# digest_entries=10000'
        '# generated=SHA-256(UTF-8 source line), lowercase hex, sorted unique'
    )
    if ($header[0] -ne "# job-search-assistant compromised-password blocklist v$blocklistVersion") {
        throw 'Generated metadata does not match the expected blocklist version.'
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null
    [IO.File]::WriteAllLines($outputPath, [string[]]($header + $digests), [Text.UTF8Encoding]::new($false))
}
finally {
    Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
}
