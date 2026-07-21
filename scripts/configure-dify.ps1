[CmdletBinding()]
param(
    [string]$BaseUrl = "https://api.dify.ai/v1",
    [switch]$CheckOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$envPath = Join-Path $repoRoot ".env"
$examplePath = Join-Path $repoRoot ".env.example"

$requiredKeys = [ordered]@{
    DIFY_APP01_REQUIREMENT_API_KEY = "APP-01 Requirement Intelligence"
    DIFY_APP02_MATERIAL_INTENT_API_KEY = "APP-02 Material and Intent"
    DIFY_APP03_GENERATION_PLAN_API_KEY = "APP-03 Generation Plan"
    DIFY_APP04_CONTENT_DRAFT_API_KEY = "APP-04 Structured Content Draft"
    DIFY_APP05_REVISION_API_KEY = "APP-05 Edit Intent"
}

function Read-DotEnv {
    param([Parameter(Mandatory = $true)][string]$Path)

    $entries = [ordered]@{}
    if (-not (Test-Path $Path)) {
        return $entries
    }

    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match '^\s*#' -or [string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        if ($line -match '^([^=]+)=(.*)$') {
            $entries[$matches[1].Trim()] = $matches[2]
        }
    }
    return $entries
}

function Set-DotEnvValue {
    param(
        [Parameter(Mandatory = $true)][System.Collections.Generic.List[string]]$Lines,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value
    )

    $replacement = "$Name=$Value"
    for ($index = 0; $index -lt $Lines.Count; $index++) {
        if ($Lines[$index] -match "^$([regex]::Escape($Name))=") {
            $Lines[$index] = $replacement
            return
        }
    }
    $Lines.Add($replacement)
}

function ConvertFrom-SecureValue {
    param([Parameter(Mandatory = $true)][Security.SecureString]$SecureValue)

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Assert-DifyKeys {
    param([Parameter(Mandatory = $true)][System.Collections.IDictionary]$Values)

    $seen = @{}
    foreach ($name in $requiredKeys.Keys) {
        $value = [string]$Values[$name]
        if ([string]::IsNullOrWhiteSpace($value)) {
            throw "$name is missing. Create an API key for $($requiredKeys[$name]) in Dify."
        }
        if (-not $value.StartsWith("app-", [StringComparison]::Ordinal)) {
            throw "$name does not look like a Dify application API key. Expected an app- prefix."
        }
        if ($seen.ContainsKey($value)) {
            throw "$name duplicates another application key. Each published Dify app needs its own API key."
        }
        $seen[$value] = $true
    }
}

if ($CheckOnly) {
    if (-not (Test-Path $envPath)) {
        throw ".env does not exist. Run this script without -CheckOnly to configure Dify."
    }
    $configured = Read-DotEnv -Path $envPath
    Assert-DifyKeys -Values $configured
    if ([string]$configured["AI_PROVIDER"] -ne "dify") {
        throw "AI_PROVIDER must be dify in .env."
    }
    Write-Host "Dify configuration check passed. Five distinct server-side application keys are present."
    exit 0
}

if (-not (Test-Path $examplePath)) {
    throw ".env.example is missing: $examplePath"
}

$lines = [System.Collections.Generic.List[string]]::new()
if (Test-Path $envPath) {
    foreach ($line in Get-Content -LiteralPath $envPath -Encoding UTF8) {
        $lines.Add($line)
    }
}
else {
    foreach ($line in Get-Content -LiteralPath $examplePath -Encoding UTF8) {
        $lines.Add($line)
    }
}

$values = [ordered]@{}
Write-Host "Enter the API key for each published Dify app. Input is hidden and keys are never printed."
foreach ($name in $requiredKeys.Keys) {
    $secureValue = Read-Host "$($requiredKeys[$name]) API key" -AsSecureString
    $values[$name] = ConvertFrom-SecureValue -SecureValue $secureValue
}

Assert-DifyKeys -Values $values
Set-DotEnvValue -Lines $lines -Name "AI_PROVIDER" -Value "dify"
Set-DotEnvValue -Lines $lines -Name "DIFY_BASE_URL" -Value $BaseUrl.TrimEnd('/')
foreach ($name in $requiredKeys.Keys) {
    Set-DotEnvValue -Lines $lines -Name $name -Value ([string]$values[$name])
}

[IO.File]::WriteAllLines($envPath, $lines, [Text.UTF8Encoding]::new($false))
Write-Host "Dify configuration saved to the Git-ignored .env file."
Write-Host "Run: powershell -ExecutionPolicy Bypass -File scripts/configure-dify.ps1 -CheckOnly"
Write-Host "Then start Docker Desktop and recreate the backend with: docker compose up --build -d"
