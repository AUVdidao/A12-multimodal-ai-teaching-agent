param(
    [ValidateSet(1, 2, 3, 4, 5)]
    [int]$AppSlot = 1,

    [string]$WorkflowCode = "WF-01",

    [string]$Operation = "clarification",

    [switch]$ShowRecentLogSummary
)

$ErrorActionPreference = "Stop"

function Read-DotEnv {
    param([string]$Path)

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([^#][A-Za-z0-9_]+)=(.*)$') {
            $values[$matches[1]] = $matches[2].Trim()
        }
    }
    return $values
}

function Get-ResultLength {
    param($Response)

    $runData = Get-RunData -Response $Response
    if ($null -eq $runData -or $null -eq $runData.outputs) {
        return 0
    }
    return ([string]$runData.outputs.result_json).Length
}

function Get-RunData {
    param($Response)

    if ($null -eq $Response) {
        return $null
    }
    if ($null -ne $Response.data) {
        return $Response.data
    }
    return $Response
}

function ConvertFrom-DifyEventStream {
    param([string]$Content)

    $finishedEvent = $null
    foreach ($line in ($Content -split "`r?`n")) {
        if ($line -notmatch '^data:\s*(.+)$') {
            continue
        }
        $eventJson = $matches[1].Trim()
        if ([string]::IsNullOrWhiteSpace($eventJson) -or $eventJson -eq '[DONE]') {
            continue
        }
        $event = $eventJson | ConvertFrom-Json
        if ($event.event -eq 'error') {
            throw "Dify stream reported an error."
        }
        if ($event.event -eq 'workflow_finished') {
            $finishedEvent = $event
        }
    }

    if ($null -eq $finishedEvent -or $null -eq $finishedEvent.data) {
        throw "Dify stream ended before workflow_finished."
    }

    return [pscustomobject]@{
        data = $finishedEvent.data
        workflow_run_id = $finishedEvent.workflow_run_id
        task_id = $finishedEvent.task_id
    }
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $repositoryRoot ".env"
if (-not (Test-Path -LiteralPath $envPath)) {
    throw "Missing .env. Run scripts/configure-dify.ps1 first."
}

$envValues = Read-DotEnv -Path $envPath
$keyNames = @{
    1 = "DIFY_APP01_REQUIREMENT_API_KEY"
    2 = "DIFY_APP02_MATERIAL_INTENT_API_KEY"
    3 = "DIFY_APP03_GENERATION_PLAN_API_KEY"
    4 = "DIFY_APP04_CONTENT_DRAFT_API_KEY"
    5 = "DIFY_APP05_REVISION_API_KEY"
}
$apiKey = $envValues[$keyNames[$AppSlot]]
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    throw "The selected Dify application key is missing."
}

$baseUrl = $envValues["DIFY_BASE_URL"]
if ([string]::IsNullOrWhiteSpace($baseUrl)) {
    $baseUrl = "https://api.dify.ai/v1"
}
$baseUrl = $baseUrl.TrimEnd('/')

$requestEnvelope = [ordered]@{
    workflowCode = $WorkflowCode
    traceHint = "a12-direct-diagnostic"
    operation = $Operation
    input = [ordered]@{
        projectId = 0
        projectInfo = [ordered]@{
            projectName = "A12 direct diagnostic"
            courseName = "Artificial Intelligence"
            chapterTitle = "Introduction"
        }
        rawRequirement = "Create a 90 minute introductory AI lesson for first-year university students."
        knownFields = [ordered]@{
            courseName = "Artificial Intelligence"
            chapterTitle = "Introduction"
            lessonDuration = 90
        }
        requestedMissingFields = @("teachingGoals", "interactionType", "outputTypes")
        dialogHistory = @()
    }
}

$requestBody = [ordered]@{
    inputs = [ordered]@{
        request_json = ($requestEnvelope | ConvertTo-Json -Depth 20 -Compress)
    }
    response_mode = "streaming"
    user = "a12-direct-diagnostic"
} | ConvertTo-Json -Depth 20

$headers = @{
    Authorization = "Bearer $apiKey"
    "Content-Type" = "application/json"
}

$run = $null
$maxAttempts = 2
for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
    $streamResponse = Invoke-WebRequest `
        -Method Post `
        -Uri "$baseUrl/workflows/run" `
        -Headers $headers `
        -Body $requestBody `
        -TimeoutSec 240 `
        -UseBasicParsing

    $run = ConvertFrom-DifyEventStream -Content $streamResponse.Content
    $attemptData = Get-RunData -Response $run
    $providerOverloaded = ([string]$attemptData.error) -match 'engine_overloaded_error|status code 429'
    if ($attemptData.status -eq "succeeded" -or -not $providerOverloaded -or $attempt -eq $maxAttempts) {
        break
    }
    Write-Output "PROVIDER_RETRY=$attempt/$maxAttempts"
    Start-Sleep -Seconds 5
}

$postData = Get-RunData -Response $run
$runFailed = $postData.status -ne "succeeded"
$runId = if ($run.workflow_run_id) {
    $run.workflow_run_id
} elseif ($postData.id) {
    $postData.id
} else {
    $null
}

Write-Output "APP_SLOT=$AppSlot"
Write-Output "WORKFLOW_CODE=$WorkflowCode"
Write-Output "POST_STATUS=$($postData.status)"
Write-Output "POST_ERROR=$($postData.error)"
Write-Output "POST_TOTAL_STEPS=$($postData.total_steps)"
Write-Output "POST_TOTAL_TOKENS=$($postData.total_tokens)"
Write-Output "POST_RESULT_LENGTH=$(Get-ResultLength -Response $run)"
Write-Output "RUN_ID_PRESENT=$([bool]$runId)"

if ($runId) {
    $detail = Invoke-RestMethod `
        -Method Get `
        -Uri "$baseUrl/workflows/run/$runId" `
        -Headers @{ Authorization = "Bearer $apiKey" } `
        -TimeoutSec 60

    $detailData = Get-RunData -Response $detail
    Write-Output "DETAIL_STATUS=$($detailData.status)"
    Write-Output "DETAIL_ERROR=$($detailData.error)"
    Write-Output "DETAIL_TOTAL_STEPS=$($detailData.total_steps)"
    Write-Output "DETAIL_TOTAL_TOKENS=$($detailData.total_tokens)"
    Write-Output "DETAIL_RESULT_LENGTH=$(Get-ResultLength -Response $detail)"
}

try {
    $logs = Invoke-RestMethod `
        -Method Get `
        -Uri "$baseUrl/workflows/logs?page=1&limit=5" `
        -Headers @{ Authorization = "Bearer $apiKey" } `
        -TimeoutSec 60

    $items = @()
    if ($logs.PSObject.Properties.Name -contains "data") {
        if ($logs.data -is [System.Array]) {
            $items = @($logs.data)
        }
        elseif (
            $null -ne $logs.data -and
            $logs.data.PSObject.Properties.Name -contains "data"
        ) {
            $items = @($logs.data.data)
        }
        elseif ($null -ne $logs.data) {
            $items = @($logs.data)
        }
    }
    Write-Output "LOG_COUNT=$($items.Count)"
    if ($ShowRecentLogSummary) {
        if ($items.Count -gt 0) {
            Write-Output "LOG_FIELDS=$($items[0].PSObject.Properties.Name -join ',')"
        }
        foreach ($item in $items) {
            $runData = if ($item.workflow_run) { $item.workflow_run } else { $item }
            $errorText = ([string]$runData.error).Replace("`r", " ").Replace("`n", " ")
            if ($errorText.Length -gt 180) {
                $errorText = $errorText.Substring(0, 180)
            }
            $createdAt = if ($item.created_at) { $item.created_at } else { $runData.created_at }
            Write-Output "LOG_STATUS=$($runData.status); CREATED_AT=$createdAt; ERROR=$errorText"
        }
    }
} catch {
    Write-Output "LOG_QUERY_ERROR=$($_.Exception.Message)"
}

if ($runFailed) {
    throw "Dify workflow $WorkflowCode did not succeed."
}
