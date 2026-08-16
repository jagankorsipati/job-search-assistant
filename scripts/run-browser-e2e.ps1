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
$diagnosticRoot = Join-Path $frontendRoot 'test-results\sanitized'
$diagnosticPath = Join-Path $diagnosticRoot 'diagnostics.txt'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("job-search-assistant-e2e-" + [Guid]::NewGuid())
$bootstrapOutput = Join-Path $temporaryRoot 'bootstrap-backend.log'
$bootstrapError = Join-Path $temporaryRoot 'bootstrap-backend-error.log'
$backendOutput = Join-Path $temporaryRoot 'normal-backend.log'
$backendError = Join-Path $temporaryRoot 'normal-backend-error.log'
$frontendOutput = Join-Path $temporaryRoot 'frontend.log'
$frontendError = Join-Path $temporaryRoot 'frontend-error.log'
$backendProcess = $null
$frontendProcess = $null
$succeeded = $false

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

function Get-AdministratorCount {
    $query = "SELECT count(*) FROM job_search_assistant.user_account WHERE role = 'ADMIN' AND status = 'ACTIVE';"
    $result = & docker compose -p $projectName -f $composeFile exec -T postgres-e2e `
        psql -U job_search_assistant_e2e -d job_search_assistant_e2e -Atc $query 2>$null
    if ($LASTEXITCODE -ne 0) { return -1 }
    $count = 0
    if ([int]::TryParse(($result | Select-Object -Last 1), [ref]$count)) { return $count }
    return -1
}

function Wait-AdministratorBootstrap {
    param([Diagnostics.Process]$Process, [int]$Attempts = 120)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $Process.Refresh()
        if ($Process.HasExited) { throw 'Bootstrap backend exited before the administrator transaction committed.' }
        if ((Get-AdministratorCount) -eq 1) { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'Timed out waiting for the committed active administrator row.'
}

function Get-ProcessState {
    param([Diagnostics.Process]$Process)
    if ($null -eq $Process) { return 'not started' }
    $Process.Refresh()
    return $(if ($Process.HasExited) { "exited ($($Process.ExitCode))" } else { 'running' })
}

function Get-SafeLogTail {
    param([string]$Path, [int]$Count = 30)
    if (-not (Test-Path -LiteralPath $Path)) { return @('[no log file]') }
    $relevant = Get-Content -LiteralPath $Path | Where-Object {
        $_ -match '(?i)(started|starting|ready|flyway|migration|tomcat|hikari|bootstrap|warn|error|exception|failed|shutdown)'
    } | Select-Object -Last $Count
    if (-not $relevant) { return @('[no relevant lines]') }
    return $relevant | ForEach-Object {
        $_ -replace '(?i)(password|token|cookie|csrf|session)[=: ]+\S+', '$1=[REDACTED]' `
           -replace '#invite=[^\s"'']+', '#invite=[REDACTED]' `
           -replace '\b[0-9a-f]{64}\b', '[REDACTED-DIGEST]' `
           -replace '\b[0-9a-f]{8}-[0-9a-f-]{27,}\b', '[REDACTED-ID]'
    }
}

function Add-DiagnosticSection {
    param([string]$Title, [object[]]$Lines)
    Add-Content -LiteralPath $diagnosticPath -Value "`n[$Title]" -Encoding UTF8
    Add-Content -LiteralPath $diagnosticPath -Value $Lines -Encoding UTF8
}

function Write-SafeDiagnostics {
    param([string]$FailureMessage)
    $rawPlaywrightRoot = Join-Path $frontendRoot 'test-results'
    $safePlaywrightLines = @()
    if (Test-Path -LiteralPath $rawPlaywrightRoot) {
        $safePlaywrightLines = Get-ChildItem -LiteralPath $rawPlaywrightRoot -Recurse -File -Filter 'error-context.md' |
            ForEach-Object { Get-Content -LiteralPath $_.FullName } |
            Where-Object { $_ -match '^\s*(Error:|Locator:|Expected:|Timeout:|at .+identity-security\.spec\.ts)' } |
            ForEach-Object {
                $_ -replace '#invite=[^\s"'']+', '#invite=[REDACTED]' `
                   -replace '\b[0-9a-f]{64}\b', '[REDACTED-DIGEST]' `
                   -replace '\b[0-9a-f]{8}-[0-9a-f-]{27,}\b', '[REDACTED-ID]'
            }
    }
    Remove-Item -LiteralPath $rawPlaywrightRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $diagnosticRoot | Out-Null
    Set-Content -LiteralPath $diagnosticPath -Value 'Sanitized browser E2E diagnostics' -Encoding UTF8
    Add-DiagnosticSection 'failure' @($FailureMessage)
    Add-DiagnosticSection 'processes' @(
        "backend=$(Get-ProcessState $backendProcess)",
        "frontend=$(Get-ProcessState $frontendProcess)"
    )
    Add-DiagnosticSection 'bootstrap backend stdout' @(Get-SafeLogTail $bootstrapOutput)
    Add-DiagnosticSection 'bootstrap backend stderr' @(Get-SafeLogTail $bootstrapError)
    Add-DiagnosticSection 'normal backend stdout' @(Get-SafeLogTail $backendOutput)
    Add-DiagnosticSection 'normal backend stderr' @(Get-SafeLogTail $backendError)
    Add-DiagnosticSection 'vite stdout' @(Get-SafeLogTail $frontendOutput)
    Add-DiagnosticSection 'vite stderr' @(Get-SafeLogTail $frontendError)
    $containerState = & docker inspect --format 'status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' `
        "$projectName-postgres-e2e-1" 2>$null
    if ($LASTEXITCODE -ne 0) { $containerState = 'container unavailable' }
    Add-DiagnosticSection 'postgres' @($containerState, "active_admin_count=$(Get-AdministratorCount)")
    Add-DiagnosticSection 'playwright context' $(if ($safePlaywrightLines) { @($safePlaywrightLines) } else { @('[no safe context lines]') })
    Get-Content -LiteralPath $diagnosticPath
}

function Start-Backend {
    param([bool]$Bootstrap, [string]$StandardOutput, [string]$StandardError)
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
        -RedirectStandardOutput $StandardOutput -RedirectStandardError $StandardError -PassThru
}

Remove-Item -LiteralPath (Join-Path $frontendRoot 'test-results'), `
    (Join-Path $frontendRoot 'playwright-report'), (Join-Path $frontendRoot 'blob-report') `
    -Recurse -Force -ErrorAction SilentlyContinue
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
    $backendProcess = Start-Backend $true $bootstrapOutput $bootstrapError
    Wait-HttpReady 'http://127.0.0.1:8080/actuator/health' $backendProcess
    Wait-AdministratorBootstrap $backendProcess
    Write-Host 'Committed active administrator row observed.' -ForegroundColor Cyan
    Stop-Process -Id $backendProcess.Id -Force
    $backendProcess.WaitForExit()
    Write-Host 'Restarting backend with bootstrap disabled.' -ForegroundColor Cyan
    $backendProcess = Start-Backend $false $backendOutput $backendError
    Wait-HttpReady 'http://127.0.0.1:8080/actuator/health' $backendProcess

    $npmExecutable = if ($env:OS -eq 'Windows_NT') { 'npm.cmd' } else { 'npm' }
    Write-Host 'Starting the loopback Vite development proxy.' -ForegroundColor Cyan
    $viteEntry = Join-Path $frontendRoot 'node_modules\vite\bin\vite.js'
    $frontendProcess = Start-Process -FilePath 'node' -ArgumentList @($viteEntry, '--host', '127.0.0.1', '--port', '5173') `
        -WorkingDirectory $frontendRoot -RedirectStandardOutput $frontendOutput -RedirectStandardError $frontendError -PassThru
    Wait-HttpReady 'http://127.0.0.1:5173' $frontendProcess

    Write-Host 'Running Playwright identity security journeys.' -ForegroundColor Cyan
    Invoke-Checked $npmExecutable @('run', 'test:e2e') $frontendRoot
    $succeeded = $true
}
catch {
    Write-SafeDiagnostics ("Browser E2E failed: " + $_.Exception.Message)
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
    if ($succeeded) {
        Remove-Item -LiteralPath (Join-Path $frontendRoot 'test-results'), `
            (Join-Path $frontendRoot 'playwright-report'), (Join-Path $frontendRoot 'blob-report') `
            -Recurse -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host 'Browser E2E verification passed.' -ForegroundColor Green
