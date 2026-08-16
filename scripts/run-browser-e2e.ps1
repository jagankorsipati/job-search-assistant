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
$composeFile = Join-Path $frontendRoot 'e2e\compose.yaml'
$jarPath = Join-Path $backendRoot 'target\backend-0.0.1-SNAPSHOT.jar'
$projectName = 'job-search-assistant-e2e'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("job-search-assistant-e2e-" + [Guid]::NewGuid())
$backendOutput = Join-Path $temporaryRoot 'backend.log'
$backendError = Join-Path $temporaryRoot 'backend-error.log'
$frontendOutput = Join-Path $temporaryRoot 'frontend.log'
$frontendError = Join-Path $temporaryRoot 'frontend-error.log'
$backendProcess = $null
$frontendProcess = $null

function Invoke-Checked {
    param([string]$Executable, [string[]]$CommandArguments, [string]$WorkingDirectory)
    Push-Location $WorkingDirectory
    try {
        & $Executable @CommandArguments
        if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE." }
    }
    finally { Pop-Location }
}

function Wait-HttpReady {
    param([string]$Url, [Diagnostics.Process]$Process, [int]$Attempts = 90)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        if ($Process.HasExited) { throw "A required process exited before $Url became ready." }
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 2 -UseBasicParsing
            if ($response.StatusCode -eq 200) { return }
        }
        catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for $Url."
}

function Start-Backend {
    param([bool]$Bootstrap)
    $env:DB_HOST = '127.0.0.1'
    $env:DB_PORT = '55433'
    $env:DB_NAME = 'job_search_assistant_e2e'
    $env:DB_USERNAME = 'job_search_assistant_e2e'
    $env:DB_PASSWORD = 'e2e_only_not_a_secret'
    $env:SESSION_COOKIE_SECURE = 'false'
    $env:IDENTITY_BOOTSTRAP_ENABLED = $Bootstrap.ToString().ToLowerInvariant()
    if ($Bootstrap) {
        $env:IDENTITY_BOOTSTRAP_LOGIN = 'e2e.admin'
        $env:IDENTITY_BOOTSTRAP_DISPLAY_NAME = 'E2E Administrator'
        $env:IDENTITY_BOOTSTRAP_PASSWORD = $env:E2E_ADMIN_PASSWORD
    }
    else {
        Remove-Item Env:IDENTITY_BOOTSTRAP_LOGIN, Env:IDENTITY_BOOTSTRAP_DISPLAY_NAME, Env:IDENTITY_BOOTSTRAP_PASSWORD -ErrorAction SilentlyContinue
    }
    return Start-Process -FilePath 'java' -ArgumentList @('-jar', $jarPath) -WorkingDirectory $backendRoot `
        -RedirectStandardOutput $backendOutput -RedirectStandardError $backendError -PassThru
}

New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    if (-not (Test-Path -LiteralPath $jarPath)) {
        throw 'Backend JAR is missing. Run backend Maven package or verify before browser E2E.'
    }
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker is required for the disposable browser E2E database.' }

    $env:E2E_ADMIN_PASSWORD = 'Nebula-' + [Guid]::NewGuid().ToString('N') + '!'
    $env:E2E_MEMBER_PASSWORD = 'Quasar-' + [Guid]::NewGuid().ToString('N') + '!'

    Write-Host 'Starting disposable PostgreSQL for browser E2E.' -ForegroundColor Cyan
    Invoke-Checked 'docker' @('compose', '-p', $projectName, '-f', $composeFile, 'up', '-d', '--wait') $repositoryRoot

    Write-Host 'Bootstrapping the one-time E2E administrator.' -ForegroundColor Cyan
    $backendProcess = Start-Backend $true
    Wait-HttpReady 'http://127.0.0.1:8080/actuator/health' $backendProcess
    Stop-Process -Id $backendProcess.Id -Force
    $backendProcess.WaitForExit()
    Write-Host 'Restarting backend with bootstrap disabled.' -ForegroundColor Cyan
    $backendProcess = Start-Backend $false
    Wait-HttpReady 'http://127.0.0.1:8080/actuator/health' $backendProcess

    $npmExecutable = if ($env:OS -eq 'Windows_NT') { 'npm.cmd' } else { 'npm' }
    Write-Host 'Starting the loopback Vite development proxy.' -ForegroundColor Cyan
    $viteEntry = Join-Path $frontendRoot 'node_modules\vite\bin\vite.js'
    $frontendProcess = Start-Process -FilePath 'node' -ArgumentList @($viteEntry, '--host', '127.0.0.1', '--port', '5173') `
        -WorkingDirectory $frontendRoot -RedirectStandardOutput $frontendOutput -RedirectStandardError $frontendError -PassThru
    Wait-HttpReady 'http://127.0.0.1:5173' $frontendProcess

    Write-Host 'Running Playwright identity security journeys.' -ForegroundColor Cyan
    Invoke-Checked $npmExecutable @('run', 'test:e2e') $frontendRoot
}
catch {
    Write-Output ("Browser E2E failed: " + $_.Exception.Message)
    if (Test-Path $backendError) { Get-Content $backendError | Select-Object -Last 40 }
    if (Test-Path $frontendError) { Get-Content $frontendError | Select-Object -Last 40 }
    throw
}
finally {
    foreach ($process in @($frontendProcess, $backendProcess)) {
        if ($null -ne $process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
    Remove-Item Env:E2E_ADMIN_PASSWORD, Env:E2E_MEMBER_PASSWORD, Env:IDENTITY_BOOTSTRAP_ENABLED, `
        Env:IDENTITY_BOOTSTRAP_LOGIN, Env:IDENTITY_BOOTSTRAP_DISPLAY_NAME, Env:IDENTITY_BOOTSTRAP_PASSWORD -ErrorAction SilentlyContinue
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & docker compose -p $projectName -f $composeFile down --volumes --remove-orphans *> $null
    $ErrorActionPreference = $previousErrorAction
    Remove-Item -LiteralPath (Join-Path $frontendRoot 'test-results'), `
        (Join-Path $frontendRoot 'playwright-report'), (Join-Path $frontendRoot 'blob-report') `
        -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host 'Browser E2E verification passed.' -ForegroundColor Green
