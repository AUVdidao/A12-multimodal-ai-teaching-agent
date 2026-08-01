[CmdletBinding()]
param(
    [ValidateRange(30, 600)]
    [int]$HealthTimeoutSeconds = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Security

function Unprotect-Secret {
    param([Parameter(Mandatory = $true)][string]$CipherText)

    $protected = [Convert]::FromBase64String($CipherText)
    $plain = $null
    try {
        $plain = [Security.Cryptography.ProtectedData]::Unprotect(
            $protected,
            $null,
            [Security.Cryptography.DataProtectionScope]::CurrentUser
        )
        return [Text.Encoding]::UTF8.GetString($plain)
    }
    finally {
        [Array]::Clear($protected, 0, $protected.Length)
        if ($null -ne $plain) {
            [Array]::Clear($plain, 0, $plain.Length)
        }
    }
}

function Get-ServiceState {
    param([Parameter(Mandatory = $true)][string]$ServiceName)

    $containerId = (& docker compose ps -q $ServiceName 2>$null | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($containerId)) {
        return 'missing'
    }

    $state = & docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $containerId 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($state)) {
        return 'unknown'
    }
    return ([string]$state).Trim()
}

function Wait-ServiceReady {
    param(
        [Parameter(Mandatory = $true)][string]$ServiceName,
        [Parameter(Mandatory = $true)][DateTime]$Deadline
    )

    while ([DateTime]::UtcNow -lt $Deadline) {
        $state = Get-ServiceState -ServiceName $ServiceName
        if ($state -eq 'healthy' -or $state -eq 'running') {
            Write-Output ("{0}: {1}" -f $ServiceName, $state)
            return
        }
        if ($state -eq 'unhealthy' -or $state -eq 'exited' -or $state -eq 'dead') {
            throw ("Service {0} entered state {1}." -f $ServiceName, $state)
        }
        Start-Sleep -Seconds 2
    }

    throw ("Service {0} did not become ready before timeout." -f $ServiceName)
}

$localAppData = [Environment]::GetFolderPath('LocalApplicationData')
$secretPath = Join-Path $localAppData 'A12TeachingAgent\secrets\kimi-api-keys.dpapi'
if (-not (Test-Path -LiteralPath $secretPath -PathType Leaf)) {
    throw 'Encrypted Kimi key configuration was not found. Run configure-kimi-keys.ps1 first.'
}

$activeKey = $null
$repoRoot = Split-Path -Parent $PSScriptRoot

try {
    $configuration = Get-Content -LiteralPath $secretPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $activeIndex = [int]$configuration.activeKeyIndex
    if ($activeIndex -lt 1 -or $activeIndex -gt 3) {
        throw 'Encrypted Kimi key configuration has an invalid active key index.'
    }

    $activeEntry = @($configuration.keys | Where-Object { [int]$_.slot -eq $activeIndex })
    if ($activeEntry.Count -ne 1) {
        throw 'Encrypted Kimi key configuration does not contain the selected key.'
    }

    $activeKey = Unprotect-Secret -CipherText ([string]$activeEntry[0].cipherText)
    if ([string]::IsNullOrWhiteSpace($activeKey)) {
        throw 'The selected encrypted Kimi key is empty.'
    }

    $env:AI_PROVIDER = 'KIMI'
    $env:A12_AI_PROVIDER = 'KIMI'
    $env:A12_AI_FALLBACK_TO_MOCK = 'false'
    $env:MOONSHOT_API_KEY = $activeKey
    $env:PPT_HARNESS_GENERATION_SOURCE = 'KIMI'

    Push-Location $repoRoot
    try {
        & docker compose up -d --build --force-recreate backend-api ppt-harness
        if ($LASTEXITCODE -ne 0) {
            throw 'Docker Compose failed to recreate backend-api and ppt-harness.'
        }

        & docker compose up -d frontend-web reverse-proxy
        if ($LASTEXITCODE -ne 0) {
            throw 'Docker Compose failed to start frontend-web and reverse-proxy.'
        }

        $deadline = [DateTime]::UtcNow.AddSeconds($HealthTimeoutSeconds)
        foreach ($service in @('backend-api', 'ppt-harness', 'frontend-web', 'reverse-proxy')) {
            Wait-ServiceReady -ServiceName $service -Deadline $deadline
        }
    }
    finally {
        Pop-Location
    }

    Write-Output 'A12_KIMI_SERVICES_READY'
    Write-Output ("ActiveKey={0}" -f $activeIndex)
    foreach ($entry in @($configuration.keys | Sort-Object { [int]$_.slot })) {
        Write-Output ("Key{0}=****{1}" -f ([int]$entry.slot), ([string]$entry.lastFour))
    }
}
finally {
    $activeKey = $null
    Remove-Item Env:MOONSHOT_API_KEY -ErrorAction SilentlyContinue
}
