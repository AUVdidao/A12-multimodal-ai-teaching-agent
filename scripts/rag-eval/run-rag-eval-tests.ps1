Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "rag-eval-lib.ps1")

$script:testCount = 0

function Assert-A12True {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:testCount++
    if (-not $Condition) {
        throw "Assertion failed: $Message"
    }
}

function Assert-A12Equal {
    param(
        [AllowNull()][object]$Actual,
        [AllowNull()][object]$Expected,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:testCount++
    if ([string]$Actual -ne [string]$Expected) {
        throw "Assertion failed: $Message. Expected '$Expected', got '$Actual'."
    }
}

$case = [pscustomobject]@{
    id = "test-001"
    question = "Why chunk documents?"
    expectedAnchors = @("Chunking")
    expectedConcepts = @("retrieval", "context", "document")
}
Assert-A12EvalCase -Case $case -Index 0

$strongHit = [pscustomobject]@{
    chunkId = 1
    materialId = 184
    chunkNo = 7
    sourceFilename = "AI-Agents-in-Depth-zh-CN.pdf"
    title = "RAG Chunking"
    content = "Chunking splits long documents for retrieval."
    scorePercent = 90
    hitReason = "title"
    keywords = @("Chunking", "RAG")
}
$weakHit = [pscustomobject]@{
    chunkId = 2
    materialId = 184
    chunkNo = 8
    sourceFilename = "AI-Agents-in-Depth-zh-CN.pdf"
    title = "Retrieval context"
    content = "Long document retrieval must preserve enough context."
    scorePercent = 70
    hitReason = "content"
    keywords = @("retrieval")
}
$missHit = [pscustomobject]@{
    chunkId = 3
    materialId = 999
    chunkNo = 1
    sourceFilename = "other.pdf"
    title = "Unrelated"
    content = "This paragraph is about classroom seating."
    scorePercent = 10
    hitReason = "content"
    keywords = @("classroom")
}

$strongMeasurement = Measure-A12Hit -Case $case -Hit $strongHit
Assert-A12Equal -Actual $strongMeasurement.Classification -Expected "STRONG_HIT" -Message "strong hit classification"

$weakMeasurement = Measure-A12Hit -Case $case -Hit $weakHit
Assert-A12Equal -Actual $weakMeasurement.Classification -Expected "WEAK_HIT" -Message "weak hit classification"

$missMeasurement = Measure-A12Hit -Case $case -Hit $missHit
Assert-A12Equal -Actual $missMeasurement.Classification -Expected "MISS" -Message "miss classification"

$queryResult = Measure-A12QueryResult -Case $case -Hits @($missHit, $weakHit, $strongHit) -TopK 3 -RequestedMaterialId 184
Assert-A12True -Condition $queryResult.hit -Message "query should have any hit"
Assert-A12True -Condition $queryResult.strongHit -Message "query should have strong hit"
Assert-A12Equal -Actual ([math]::Round($queryResult.reciprocalRank, 3)) -Expected "0.5" -Message "RR uses first matching rank"
Assert-A12Equal -Actual ([math]::Round($queryResult.materialPrecision, 3)) -Expected "0.667" -Message "material precision"
Assert-A12Equal -Actual $queryResult.returnedChunkCount -Expected "3" -Message "topK handling keeps returned count"

$emptyResult = Measure-A12QueryResult -Case $case -Hits @() -TopK 5 -RequestedMaterialId 184
Assert-A12Equal -Actual $emptyResult.returnedChunkCount -Expected "0" -Message "zero chunks should not crash"
Assert-A12Equal -Actual $emptyResult.classification -Expected "MISS" -Message "zero chunks are miss"

$aggregate = Measure-A12Aggregate -QueryResults @($queryResult, $emptyResult)
Assert-A12Equal -Actual ([math]::Round($aggregate.mrr, 3)) -Expected "0.25" -Message "MRR calculation"
Assert-A12Equal -Actual ([math]::Round($aggregate.anyHitAtK, 3)) -Expected "0.5" -Message "Any Hit@K calculation"

$longContent = "x" * 500
$preview = New-A12Preview -Value $longContent -MaxLength 120
Assert-A12True -Condition ($preview.Length -le 120) -Message "preview must be <= 120 chars"
Assert-A12True -Condition ($preview -ne $longContent) -Message "preview must not output full long content"

$cases = @(Read-A12EvalCases -Path (Join-Path $PSScriptRoot "ai-agent-book-eval.json"))
Assert-A12True -Condition ($cases.Count -ge 20) -Message "eval dataset contains at least 20 questions"

Write-Host "A12 RAG eval tests passed. Assertions: $script:testCount"
