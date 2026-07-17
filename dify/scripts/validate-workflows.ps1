[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$difyRoot = Split-Path -Parent $PSScriptRoot
$workflowRoot = Join-Path $difyRoot 'workflows'
$contractRoot = Join-Path $difyRoot 'contracts'
$failures = New-Object System.Collections.Generic.List[string]
$workflowCodes = 1..7 | ForEach-Object { 'WF-{0:D2}' -f $_ }

function Add-Failure {
    param([string]$Message)
    $failures.Add($Message)
}

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        Add-Failure $Message
    }
}

function Read-JsonFile {
    param([string]$Path)
    try {
        return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        Add-Failure ("Invalid JSON: {0} ({1})" -f $Path, $_.Exception.Message)
        return $null
    }
}

Assert-True (Test-Path -LiteralPath (Join-Path $difyRoot 'README.md')) 'Missing dify/README.md.'
Assert-True (Test-Path -LiteralPath $workflowRoot) 'Missing dify/workflows directory.'
Assert-True (Test-Path -LiteralPath $contractRoot) 'Missing dify/contracts directory.'

$schemaPath = Join-Path $contractRoot 'common-response-envelope.schema.json'
Assert-True (Test-Path -LiteralPath $schemaPath) 'Missing common response envelope schema.'
if (Test-Path -LiteralPath $schemaPath) {
    $null = Read-JsonFile $schemaPath
}

foreach ($code in $workflowCodes) {
    $workflowMatches = @(Get-ChildItem -LiteralPath $workflowRoot -File -Filter "$code-*.yml" -ErrorAction SilentlyContinue)
    Assert-True ($workflowMatches.Count -eq 1) ("{0}: expected exactly one workflow YAML, found {1}." -f $code, $workflowMatches.Count)

    if ($workflowMatches.Count -eq 1) {
        $workflowPath = $workflowMatches[0].FullName
        $yaml = Get-Content -LiteralPath $workflowPath -Raw -Encoding UTF8
        Assert-True ($yaml -match '(?m)^kind:\s*app\s*$') ("{0}: missing 'kind: app'." -f $code)
        Assert-True ($yaml -match '(?m)^version:\s*[0-9]+\.[0-9]+\.[0-9]+\s*$') ("{0}: missing DSL version." -f $code)
        Assert-True ($yaml -match '(?m)^\s+mode:\s*workflow\s*$') ("{0}: app mode is not workflow." -f $code)
        Assert-True ($yaml -match [regex]::Escape($code)) ("{0}: workflow code is absent from YAML." -f $code)
        Assert-True ($yaml -match '(?m)^\s+type:\s*start\s*$') ("{0}: missing Start node." -f $code)
        Assert-True ($yaml -match '(?m)^\s+type:\s*llm\s*$') ("{0}: missing LLM node." -f $code)
        Assert-True ($yaml -match '(?m)^\s+type:\s*end\s*$') ("{0}: missing End node." -f $code)
        Assert-True ($yaml -match '\brequest_json\b') ("{0}: missing request_json input." -f $code)
        Assert-True ($yaml -match '\bresult_json\b') ("{0}: missing result_json output." -f $code)
        Assert-True ($yaml -match '(?i)Return exactly one valid JSON object') ("{0}: prompt does not require a single JSON object." -f $code)
        Assert-True ($yaml -match '(?i)Do not use Markdown or code fences') ("{0}: prompt does not forbid Markdown/code fences." -f $code)
        Assert-True ($yaml -notmatch "`t") ("{0}: YAML contains tab characters." -f $code)
    }

    $contractPath = Join-Path $contractRoot "$code.contract.examples.json"
    Assert-True (Test-Path -LiteralPath $contractPath) ("{0}: missing contract examples." -f $code)
    if (Test-Path -LiteralPath $contractPath) {
        $contract = Read-JsonFile $contractPath
        if ($null -ne $contract) {
            Assert-True ($contract.request.workflowCode -eq $code) ("{0}: request workflowCode mismatch." -f $code)
            Assert-True ($contract.successResponse.workflowCode -eq $code) ("{0}: success workflowCode mismatch." -f $code)
            Assert-True ($contract.errorResponse.workflowCode -eq $code) ("{0}: error workflowCode mismatch." -f $code)
            Assert-True ($contract.successResponse.success -eq $true) ("{0}: successResponse.success must be true." -f $code)
            Assert-True ($null -ne $contract.successResponse.data) ("{0}: successResponse.data must be present." -f $code)
            Assert-True (@($contract.successResponse.errors).Count -eq 0) ("{0}: successResponse.errors must be empty." -f $code)
            Assert-True ($contract.errorResponse.success -eq $false) ("{0}: errorResponse.success must be false." -f $code)
            Assert-True ($null -eq $contract.errorResponse.data) ("{0}: errorResponse.data must be null." -f $code)
            Assert-True (@($contract.errorResponse.errors).Count -gt 0) ("{0}: errorResponse.errors must not be empty." -f $code)
        }
    }
}

$allFiles = @(Get-ChildItem -LiteralPath $difyRoot -Recurse -File)
$secretPatterns = @(
    '(?i)\bsk-[A-Za-z0-9_-]{20,}\b',
    '(?i)\bapp-[A-Za-z0-9_-]{20,}\b',
    '\bAKIA[0-9A-Z]{16}\b',
    '(?i)Bearer\s+[A-Za-z0-9._-]{20,}'
)

foreach ($file in $allFiles) {
    $content = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    foreach ($pattern in $secretPatterns) {
        if ($content -match $pattern) {
            Add-Failure ("Possible secret found in {0} (pattern: {1})." -f $file.FullName, $pattern)
        }
    }
}

if ($failures.Count -gt 0) {
    Write-Host ("Dify asset validation FAILED with {0} issue(s):" -f $failures.Count) -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host (" - {0}" -f $failure) -ForegroundColor Red
    }
    exit 1
}

Write-Host 'Dify asset validation PASSED.' -ForegroundColor Green
Write-Host ("Validated {0} workflow DSL files, {0} contract example files, and 1 common schema." -f $workflowCodes.Count)
Write-Host 'No common API-key pattern was detected. Target Dify import/runtime validation is still required.'
