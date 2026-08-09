[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [Parameter(Mandatory = $true)][string]$Username,
    [Parameter(Mandatory = $true)][string]$Password,
    [Parameter(Mandatory = $true)][long]$ProjectId,
    [long]$MaterialId = 0,
    [string]$EvalFile = "",
    [ValidateRange(1, 50)][int]$TopK = 5,
    [string]$OutputFile,
    [ValidateSet("PRECISE", "BROAD", "DENSE")][string[]]$Modes = @("PRECISE", "BROAD"),
    [ValidateSet("TEACHER", "LEADER", "STUDENT")][string]$ActiveRole = "TEACHER",
    [switch]$BaselineSourceTruncated
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "rag-eval\rag-eval-lib.ps1")

if ([string]::IsNullOrWhiteSpace($EvalFile)) {
    $EvalFile = Join-Path $PSScriptRoot "rag-eval\ai-agent-book-eval.json"
}

$base = $BaseUrl.TrimEnd("/")
$lexicalEndpoint = "/api/projects/$ProjectId/knowledge/workspace-search"
$denseEndpoint = "/api/projects/$ProjectId/knowledge/dense-search"
$script:authToken = $null

function Invoke-A12ApiJson {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body = $null,
        [int]$TimeoutSec = 30
    )

    $url = "$base$Path"
    $parameters = @{
        Uri = $url
        Method = $Method
        TimeoutSec = $TimeoutSec
    }
    if (-not [string]::IsNullOrWhiteSpace($script:authToken)) {
        $parameters.Headers = @{ Authorization = "Bearer $script:authToken" }
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json; charset=utf-8"
        $json = $Body | ConvertTo-Json -Depth 20 -Compress
        $parameters.Body = [System.Text.Encoding]::UTF8.GetBytes($json)
    }

    try {
        $response = Invoke-WebRequest @parameters -UseBasicParsing
        $response.RawContentStream.Position = 0
        $reader = New-Object System.IO.StreamReader(
            $response.RawContentStream,
            [System.Text.Encoding]::UTF8,
            $true
        )
        try {
            $decoded = $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
        $payload = $decoded | ConvertFrom-Json
        if ($null -eq $payload -or $payload.code -ne 0) {
            throw "Unexpected API response code: $($payload.code); message: $($payload.message)"
        }
        return $payload.data
    }
    catch {
        $status = "n/a"
        if ($null -ne $_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
        }
        throw "HTTP API failed: $Method $url; status=$status; $($_.Exception.Message)"
    }
}

function Invoke-A12Login {
    $session = Invoke-A12ApiJson -Method "POST" -Path "/api/v1/auth/login" -Body @{
        username = $Username
        password = $Password
        activeRole = $ActiveRole
    }
    if ($null -eq $session -or [string]::IsNullOrWhiteSpace([string]$session.token)) {
        throw "Login did not return an auth token."
    }
    $script:authToken = $session.token
    return $session
}

function Invoke-A12KnowledgeSearch {
    param(
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][string]$Question
    )

    if ($Mode -eq "DENSE") {
        return Invoke-A12ApiJson -Method "POST" -Path $denseEndpoint -Body @{
            query = $Question
            limit = $TopK
        }
    }

    $body = @{
        query = $Question
        matchMode = $Mode
        caseSensitive = $false
        page = 0
        size = $TopK
    }
    if ($MaterialId -gt 0) {
        $body.materialId = $MaterialId
    }
    return Invoke-A12ApiJson -Method "POST" -Path $lexicalEndpoint -Body $body
}

$cases = @(Read-A12EvalCases -Path $EvalFile)
$session = Invoke-A12Login
$materialValue = $null
if ($MaterialId -gt 0) {
    $materialValue = $MaterialId
}

$modeReports = @()
foreach ($mode in $Modes) {
    $queryResults = @()
    foreach ($case in $cases) {
        $searchResult = Invoke-A12KnowledgeSearch -Mode $mode -Question $case.question
        $hits = @()
        if ($null -ne $searchResult -and $searchResult.PSObject.Properties.Name -contains "hits") {
            $hits = @(ConvertTo-A12Array $searchResult.hits)
        }
        $queryResults += Measure-A12QueryResult -Case $case -Hits $hits -TopK $TopK -RequestedMaterialId $materialValue
    }
    $aggregate = Measure-A12Aggregate -QueryResults $queryResults
    $modeReports += [pscustomobject]@{
        mode = $mode
        endpoint = if ($mode -eq "DENSE") { $denseEndpoint } else { $lexicalEndpoint }
        retrievalStrategy = if ($mode -eq "DENSE") { "DENSE" } else { "LEXICAL" }
        aggregate = $aggregate
        failedQueryIds = @($queryResults | Where-Object { -not $_.hit } | ForEach-Object { $_.id })
        perQueryResults = @($queryResults)
    }
}

$runMetadata = [pscustomobject]@{
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    baseUrl = $base
    username = $Username
    activeRole = $ActiveRole
    projectId = $ProjectId
    materialId = $materialValue
    evalFile = (Resolve-Path -LiteralPath $EvalFile).Path
    topK = $TopK
    modes = @($Modes)
    endpoints = [pscustomobject]@{
        lexical = $lexicalEndpoint
        dense = $denseEndpoint
    }
    retrievalStrategy = if (@($Modes | Where-Object { $_ -eq "DENSE" }).Count -gt 0) { "DENSE" } else { "LEXICAL" }
    baselineSourceTruncated = [bool]$BaselineSourceTruncated
    kimiScoringUsed = $false
    authUser = if ($null -ne $session.user) { $session.user.username } else { $Username }
}

$report = [pscustomobject]@{
    metadata = $runMetadata
    modeReports = @($modeReports)
}

Write-Host "========================================"
Write-Host "A12 RAG Retrieval Evaluation"
Write-Host "========================================"
Write-Host "Project: $ProjectId"
Write-Host "Material: $(if ($MaterialId -gt 0) { $MaterialId } else { 'all project materials' })"
Write-Host "Lexical endpoint: $lexicalEndpoint"
Write-Host "Dense endpoint: $denseEndpoint"
Write-Host "Strategies: $($Modes -join ', ')"
Write-Host "TopK: $TopK"
Write-Host "Questions: $($cases.Count)"
$truncatedLabel = if ([bool]$BaselineSourceTruncated) { "true" } else { "false" }
Write-Host "BASELINE_SOURCE_TRUNCATED=$truncatedLabel"
Write-Host ""

foreach ($modeReport in $modeReports) {
    Write-Host "Mode: $($modeReport.mode)"
    Write-Host "Strong Hit@$TopK`: $(Format-A12Metric $modeReport.aggregate.strongHitAtK)"
    Write-Host "Any Hit@$TopK`: $(Format-A12Metric $modeReport.aggregate.anyHitAtK)"
    Write-Host "MRR: $(Format-A12Metric $modeReport.aggregate.mrr)"
    Write-Host "Material Precision: $(Format-A12Metric $modeReport.aggregate.materialPrecision)"
    Write-Host "Failed query IDs: $($modeReport.failedQueryIds -join ', ')"
    Write-Host ""
    Write-Host "PASS / MISS questions:"
    foreach ($result in $modeReport.perQueryResults) {
        $status = if ($result.hit) { "PASS" } else { "MISS" }
        Write-Host ("- [{0}] {1} {2} rr={3}" -f $status, $result.id, $result.classification, (Format-A12Metric $result.reciprocalRank))
    }
    Write-Host ""
}

if (-not [string]::IsNullOrWhiteSpace($OutputFile)) {
    $outputDirectory = Split-Path -Parent $OutputFile
    if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
        New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    }
    $report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $OutputFile -Encoding UTF8
    Write-Host "JSON report written: $OutputFile"
}
