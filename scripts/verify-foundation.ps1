[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (Test-Path Variable:\PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $repositoryRoot 'backend'
$frontendRoot = Join-Path $repositoryRoot 'frontend'

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory)]
        [string]$Label,

        [Parameter(Mandatory)]
        [string]$WorkingDirectory,

        [Parameter(Mandatory)]
        [string]$Executable,

        [Parameter(Mandatory)]
        [string[]]$CommandArguments
    )

    Write-Host "`n==> $Label" -ForegroundColor Cyan
    Push-Location $WorkingDirectory
    try {
        & $Executable @CommandArguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Label failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

Invoke-CheckedCommand `
    -Label 'Backend fast tests' `
    -WorkingDirectory $backendRoot `
    -Executable (Join-Path $backendRoot 'mvnw.cmd') `
    -CommandArguments @('--batch-mode', '--no-transfer-progress', 'test')

Invoke-CheckedCommand `
    -Label 'Compose configuration validation' `
    -WorkingDirectory $repositoryRoot `
    -Executable 'docker' `
    -CommandArguments @('compose', 'config', '--quiet')

& docker info *> $null
$dockerAvailable = $LASTEXITCODE -eq 0

if ($dockerAvailable) {
    Invoke-CheckedCommand `
        -Label 'Backend full verification with Testcontainers' `
        -WorkingDirectory $backendRoot `
        -Executable (Join-Path $backendRoot 'mvnw.cmd') `
        -CommandArguments @('--batch-mode', '--no-transfer-progress', 'verify')

}
else {
    Write-Warning 'Docker Engine is unavailable; skipping backend Testcontainers integration tests. Start Docker Desktop and rerun for a complete foundation check.'
}

Invoke-CheckedCommand `
    -Label 'Locked frontend dependency installation' `
    -WorkingDirectory $frontendRoot `
    -Executable 'npm.cmd' `
    -CommandArguments @('ci')

$frontendChecks = @(
    @{ Label = 'Frontend formatting check'; Script = 'format:check' },
    @{ Label = 'Frontend lint'; Script = 'lint' },
    @{ Label = 'Frontend strict TypeScript validation'; Script = 'typecheck' },
    @{ Label = 'Frontend component tests'; Script = 'test:run' },
    @{ Label = 'Frontend production build'; Script = 'build' }
)

foreach ($check in $frontendChecks) {
    Invoke-CheckedCommand `
        -Label $check.Label `
        -WorkingDirectory $frontendRoot `
        -Executable 'npm.cmd' `
        -CommandArguments @('run', $check.Script)
}

if ($dockerAvailable) {
    Invoke-CheckedCommand `
        -Label 'Full-stack browser identity, profile, and base resume verification' `
        -WorkingDirectory $repositoryRoot `
        -Executable 'powershell.exe' `
        -CommandArguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'run-browser-e2e.ps1'))
}

Write-Host "`nFoundation verification passed." -ForegroundColor Green
if (-not $dockerAvailable) {
    Write-Host 'Result is partial because Docker-dependent backend integration tests were skipped.' -ForegroundColor Yellow
}
