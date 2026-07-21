[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$difyRoot = Split-Path -Parent $PSScriptRoot
$workflowRoot = Join-Path $difyRoot 'workflows'
$appRoot = Join-Path $difyRoot 'apps'
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
Assert-True (Test-Path -LiteralPath $appRoot) 'Missing dify/apps directory.'
Assert-True (Test-Path -LiteralPath $contractRoot) 'Missing dify/contracts directory.'

$schemaPath = Join-Path $contractRoot 'common-response-envelope.schema.json'
Assert-True (Test-Path -LiteralPath $schemaPath) 'Missing common response envelope schema.'
if (Test-Path -LiteralPath $schemaPath) {
    $null = Read-JsonFile $schemaPath
}

$routingPath = Join-Path $appRoot 'app-routing.json'
Assert-True (Test-Path -LiteralPath $routingPath) 'Missing five-app routing manifest.'
$routing = $null
if (Test-Path -LiteralPath $routingPath) {
    $routing = Read-JsonFile $routingPath
}

if ($null -ne $routing) {
    $routes = @($routing.apps)
    $physicalApps = @(Get-ChildItem -LiteralPath $appRoot -File -Filter 'APP-*.yml' -ErrorAction SilentlyContinue)
    Assert-True ($routing.physicalAppCount -eq 5) 'Routing manifest physicalAppCount must be 5.'
    Assert-True ($routes.Count -eq 5) ("Routing manifest must contain 5 apps, found {0}." -f $routes.Count)
    Assert-True ($physicalApps.Count -eq 5) ("Expected exactly 5 deployable app DSL files, found {0}." -f $physicalApps.Count)

    $routedWorkflowCodes = New-Object System.Collections.Generic.List[string]
    foreach ($route in $routes) {
        $appPath = Join-Path $appRoot $route.file
        Assert-True (Test-Path -LiteralPath $appPath) ("{0}: mapped app file is missing ({1})." -f $route.appCode, $route.file)
        foreach ($logicalWorkflow in @($route.logicalWorkflows)) {
            $routedWorkflowCodes.Add([string] $logicalWorkflow)
        }

        if (Test-Path -LiteralPath $appPath) {
            $yaml = Get-Content -LiteralPath $appPath -Raw -Encoding UTF8
            Assert-True ($yaml -match '(?m)^kind:\s*app\s*$') ("{0}: missing 'kind: app'." -f $route.appCode)
            Assert-True ($yaml -match '(?m)^\s+mode:\s*workflow\s*$') ("{0}: app mode is not workflow." -f $route.appCode)
            Assert-True ($yaml -match '(?m)^\s+type:\s*start\s*$') ("{0}: missing Start node." -f $route.appCode)
            Assert-True ($yaml -match '(?m)^\s+type:\s*llm\s*$') ("{0}: missing LLM node." -f $route.appCode)
            Assert-True ($yaml -match '(?m)^\s+type:\s*end\s*$') ("{0}: missing End node." -f $route.appCode)
            Assert-True ($yaml -match '\brequest_json\b') ("{0}: missing request_json input." -f $route.appCode)
            Assert-True ($yaml -match '\bresult_json\b') ("{0}: missing result_json output." -f $route.appCode)
            Assert-True ($yaml -match '(?m)^\s+name:\s*GPT-5\.6 Sol\s*$') ("{0}: LLM model is not GPT-5.6 Sol." -f $route.appCode)
            Assert-True ($yaml -match '(?m)^\s+provider:\s*langgenius/openai_api_compatible/openai_api_compatible\s*$') ("{0}: LLM provider is not OpenAI-API-compatible." -f $route.appCode)
            Assert-True ($yaml -match '(?m)^\s+max_tokens:\s*(4096|8192)\s*$') ("{0}: max_tokens must be explicitly bounded." -f $route.appCode)
            Assert-True ($yaml -match '(?m)^\s+temperature:\s*0\.2\s*$') ("{0}: temperature must be 0.2 for stable JSON output." -f $route.appCode)
            Assert-True ($yaml -match '(?i)Return exactly one valid JSON object') ("{0}: prompt does not require a single JSON object." -f $route.appCode)
            Assert-True ($yaml -match '(?i)Do not use Markdown or code fences') ("{0}: prompt does not forbid Markdown/code fences." -f $route.appCode)
            Assert-True ($yaml -notmatch "`t") ("{0}: YAML contains tab characters." -f $route.appCode)

            foreach ($logicalWorkflow in @($route.logicalWorkflows)) {
                Assert-True ($yaml -match [regex]::Escape([string] $logicalWorkflow)) ("{0}: logical workflow {1} is absent from YAML." -f $route.appCode, $logicalWorkflow)
            }
            foreach ($operation in @($route.operations)) {
                Assert-True ($yaml -match [regex]::Escape([string] $operation)) ("{0}: operation {1} is absent from YAML." -f $route.appCode, $operation)
            }
        }
    }

    Assert-True ($routedWorkflowCodes.Count -eq 7) ("Expected 7 routed logical workflow entries, found {0}." -f $routedWorkflowCodes.Count)
    Assert-True (@($routedWorkflowCodes | Sort-Object -Unique).Count -eq 7) 'Logical workflows must be routed exactly once across the five apps.'
    foreach ($code in $workflowCodes) {
        Assert-True ($routedWorkflowCodes.Contains($code)) ("{0}: missing from five-app routing manifest." -f $code)
    }
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
        Assert-True ($yaml -match '(?m)^\s+name:\s*GPT-5\.6 Sol\s*$') ("{0}: LLM model is not GPT-5.6 Sol." -f $code)
        Assert-True ($yaml -match '(?m)^\s+provider:\s*langgenius/openai_api_compatible/openai_api_compatible\s*$') ("{0}: LLM provider is not OpenAI-API-compatible." -f $code)
        Assert-True ($yaml -match '(?m)^\s+max_tokens:\s*(4096|8192)\s*$') ("{0}: max_tokens must be explicitly bounded." -f $code)
        Assert-True ($yaml -match '(?m)^\s+temperature:\s*0\.2\s*$') ("{0}: temperature must be 0.2 for stable JSON output." -f $code)
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
    '(?i)\bapp-[A-Za-z0-9]{20,}\b',
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
Write-Host ("Validated {0} logical workflow DSL files, {0} contract example files, 5 deployable app DSL files, and 1 common schema." -f $workflowCodes.Count)
Write-Host 'No common API-key pattern was detected. Target Dify import/runtime validation is still required.'
