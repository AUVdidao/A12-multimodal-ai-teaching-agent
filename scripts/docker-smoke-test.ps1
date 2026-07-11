Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$frontendPort = if ([string]::IsNullOrWhiteSpace($env:FRONTEND_PORT)) { "8081" } else { $env:FRONTEND_PORT }
$backendPort = if ([string]::IsNullOrWhiteSpace($env:BACKEND_PORT)) { "8080" } else { $env:BACKEND_PORT }
$baseUrl = "http://localhost:$frontendPort"
$readinessUrl = "http://localhost:$backendPort/api/health"
$deadline = [DateTime]::UtcNow.AddSeconds(60)
$lastReadinessError = "No response received"

function Invoke-A12Api {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body = $null
    )

    $url = "$baseUrl$Path"
    Write-Host "Checking $Name`: $Method $url"
    try {
        $parameters = @{
            Uri = $url
            Method = $Method
            TimeoutSec = 20
        }
        if ($null -ne $Body) {
            $parameters.ContentType = "application/json; charset=utf-8"
            $parameters.Body = $Body | ConvertTo-Json -Depth 10 -Compress
        }

        $response = Invoke-RestMethod @parameters
        if ($null -eq $response -or $response.code -ne 0) {
            throw "Unexpected API response code: $($response.code)"
        }
        return $response.data
    }
    catch {
        $status = if ($null -ne $_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { "n/a" }
        $details = if (-not [string]::IsNullOrWhiteSpace($_.ErrorDetails.Message)) { $_.ErrorDetails.Message } else { $_.Exception.Message }
        throw "Check failed: $Name at $url. HTTP: $status. Response: $details"
    }
}

function Invoke-A12Multipart {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$ContentType,
        [string]$Description = "Docker smoke material"
    )

    $url = "$baseUrl$Path"
    Write-Host "Checking $Name`: POST $url"
    $raw = & curl.exe -sS --fail-with-body -X POST -F "file=@$FilePath;type=$ContentType" -F "description=$Description" $url 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Check failed: $Name at $url. curl exit code: $LASTEXITCODE. Response: $($raw -join ' ')"
    }
    try {
        $response = ($raw -join "`n") | ConvertFrom-Json
    }
    catch {
        throw "Check failed: $Name at $url. Invalid JSON response: $($raw -join ' ')"
    }
    if ($null -eq $response -or $response.code -ne 0) {
        throw "Check failed: $Name at $url. API response: $($raw -join ' ')"
    }
    return $response.data
}

Write-Host "Waiting for backend readiness: $readinessUrl"
while ([DateTime]::UtcNow -lt $deadline) {
    try {
        $response = Invoke-WebRequest -Uri $readinessUrl -UseBasicParsing -TimeoutSec 5
        if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
            Write-Host "Backend is ready."
            $lastReadinessError = $null
            break
        }
        $lastReadinessError = "HTTP $($response.StatusCode)"
    }
    catch {
        $lastReadinessError = $_.Exception.Message
    }
    Start-Sleep -Seconds 2
}

if ($null -ne $lastReadinessError) {
    Write-Host "Backend readiness timed out after 60 seconds. Last error: $lastReadinessError"
    Write-Host "docker compose ps:"
    & docker compose ps
    Write-Host "Last 100 backend log lines:"
    & docker compose logs backend --tail=100
    throw "Backend readiness check failed: $readinessUrl"
}

Write-Host "Checking frontend: $baseUrl/"
$frontendResponse = Invoke-WebRequest -Uri "$baseUrl/" -UseBasicParsing -TimeoutSec 20
if ($frontendResponse.StatusCode -lt 200 -or $frontendResponse.StatusCode -ge 300) {
    throw "Frontend check failed with HTTP $($frontendResponse.StatusCode)"
}

$null = Invoke-A12Api -Name "AI workflow status" -Method "GET" -Path "/api/ai-workflow/status"
$null = Invoke-A12Api -Name "model modes" -Method "GET" -Path "/api/model-modes"

$project = Invoke-A12Api -Name "project creation" -Method "POST" -Path "/api/projects" -Body @{
    projectName = "M1 and M2 Docker smoke project"
    courseName = "Biology"
    chapterTitle = "Photosynthesis"
    targetStudents = "Grade 8"
    lessonDuration = 45
    description = "Dynamic M1 and M2 smoke data"
}
if ($null -eq $project.id) {
    throw "Project creation did not return an id"
}
$projectId = [long]$project.id

$null = Invoke-A12Api -Name "save model mode" -Method "PUT" -Path "/api/projects/$projectId/model-mode" -Body @{
    mode = "STANDARD"
}

$initialRequirement = Invoke-A12Api -Name "save incomplete requirement" -Method "POST" -Path "/api/projects/$projectId/requirements" -Body @{
    topic = "Photosynthesis"
    rawRequirementText = "Use classroom examples"
    outputTypes = @()
}

$latestInitial = Invoke-A12Api -Name "requirement latest" -Method "GET" -Path "/api/projects/$projectId/requirements/latest"
if ($latestInitial.id -ne $initialRequirement.id) {
    throw "Requirement latest did not return the newest version"
}

$incompletePayload = @{
    topic = "Photosynthesis"
    rawRequirementText = "Use classroom examples"
    outputTypes = @()
}
$clarificationCheck = Invoke-A12Api -Name "clarification check" -Method "POST" -Path "/api/projects/$projectId/clarification/check" -Body $incompletePayload
if ($clarificationCheck.complete -or $clarificationCheck.missingFields.Count -eq 0) {
    throw "Incomplete requirement was not identified"
}

$clarificationQuestions = Invoke-A12Api -Name "clarification questions" -Method "POST" -Path "/api/projects/$projectId/clarification/questions" -Body $incompletePayload
if ($clarificationQuestions.questions.Count -eq 0) {
    throw "Clarification questions were not generated"
}

$sessionId = "project-$projectId-clarification"
$aiContent = ($clarificationQuestions.questions -join "`n")
$null = Invoke-A12Api -Name "save AI dialogue" -Method "POST" -Path "/api/projects/$projectId/dialogues" -Body @{
    sessionId = $sessionId
    sender = "AI"
    content = $aiContent
    roundNo = 1
}
$null = Invoke-A12Api -Name "save teacher dialogue" -Method "POST" -Path "/api/projects/$projectId/dialogues" -Body @{
    sessionId = $sessionId
    sender = "TEACHER"
    content = "Grade: Grade 8; subject: Biology; duration: 45 minutes; goal: explain photosynthesis; output: PPT."
    roundNo = 1
}
$dialogues = Invoke-A12Api -Name "dialogue history" -Method "GET" -Path "/api/projects/$projectId/dialogues"
if ($dialogues.Count -lt 2) {
    throw "Dialogue history did not persist both messages"
}

$completePayload = @{
    gradeLevel = "Grade 8"
    subject = "Biology"
    topic = "Photosynthesis"
    lessonDuration = "45 minutes"
    teachingGoals = "Explain the basic photosynthesis process"
    keyPoints = "Conditions for photosynthesis"
    difficultPoints = "Matter and energy conversion"
    outputTypes = @("PPT", "LESSON_PLAN")
    rawRequirementText = "Use classroom examples and a technology style"
}
$completeRequirement = Invoke-A12Api -Name "save completed requirement" -Method "POST" -Path "/api/projects/$projectId/requirements" -Body $completePayload
$latestComplete = Invoke-A12Api -Name "completed requirement latest" -Method "GET" -Path "/api/projects/$projectId/requirements/latest"
if ($latestComplete.id -ne $completeRequirement.id) {
    throw "Completed requirement was not returned as latest"
}

$completeCheck = Invoke-A12Api -Name "completed clarification check" -Method "POST" -Path "/api/projects/$projectId/clarification/check" -Body $completePayload
if (-not $completeCheck.complete) {
    throw "Completed requirement was still marked incomplete"
}

$summary = Invoke-A12Api -Name "summary generation" -Method "POST" -Path "/api/projects/$projectId/requirement-summaries/generate"
$latestSummary = Invoke-A12Api -Name "summary latest" -Method "GET" -Path "/api/projects/$projectId/requirement-summaries/latest"
if ($latestSummary.id -ne $summary.id) {
    throw "Summary latest did not return the generated summary"
}

$updatedSummary = Invoke-A12Api -Name "summary update" -Method "PUT" -Path "/api/projects/$projectId/requirement-summaries/$($summary.id)" -Body @{
    gradeLevel = $summary.gradeLevel
    subject = $summary.subject
    topic = "Photosynthesis investigation"
    lessonDuration = $summary.lessonDuration
    teachingGoals = $summary.teachingGoals
    keyPoints = $summary.keyPoints
    difficultPoints = $summary.difficultPoints
    outputTypes = @($summary.outputTypes)
    stylePreference = "Technology style"
}
if ($updatedSummary.topic -ne "Photosynthesis investigation") {
    throw "Summary update was not persisted"
}

$confirmedSummary = Invoke-A12Api -Name "summary confirmation" -Method "POST" -Path "/api/projects/$projectId/requirement-summaries/$($summary.id)/confirm"
if ($confirmedSummary.status -ne "CONFIRMED" -or [string]::IsNullOrWhiteSpace($confirmedSummary.confirmedAt)) {
    throw "Summary confirmation was not persisted"
}

$temporaryMaterial = Join-Path ([System.IO.Path]::GetTempPath()) "a12-m2-smoke-$([Guid]::NewGuid().ToString('N')).png"
$materialId = $null
$intentId = $null
try {
    $pngBytes = [Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z5f8AAAAASUVORK5CYII=")
    [System.IO.File]::WriteAllBytes($temporaryMaterial, $pngBytes)

    $material = Invoke-A12Multipart -Name "M2 material upload" -Path "/api/projects/$projectId/materials" -FilePath $temporaryMaterial -ContentType "image/png" -Description "Non-sensitive generated smoke image"
    if ($null -eq $material.id -or $material.originalFilename -notlike "a12-m2-smoke-*.png") {
        throw "Material upload did not return the generated smoke file metadata"
    }
    $materialId = [long]$material.id

    $materials = Invoke-A12Api -Name "M2 material list" -Method "GET" -Path "/api/projects/$projectId/materials"
    if (@($materials | Where-Object { $_.id -eq $materialId }).Count -ne 1) {
        throw "Uploaded material was not restored by the material list"
    }

    $usage = Invoke-A12Api -Name "M2 material usage binding" -Method "PUT" -Path "/api/projects/$projectId/materials/$materialId/usages" -Body @{
        usageTypes = @("TEXTBOOK_BASIS", "IMAGE_ASSET")
        note = "Use for concept explanation and visual observation"
    }
    if ($usage.usageTypes.Count -ne 2) {
        throw "Material usages were not persisted"
    }

    $parse = Invoke-A12Api -Name "M2 prototype parsing" -Method "POST" -Path "/api/projects/$projectId/materials/$materialId/parse"
    if ($parse.parseStatus -ne "SUCCEEDED" -or [string]::IsNullOrWhiteSpace($parse.summary) -or $parse.keywords.Count -lt 3) {
        throw "Prototype parse result is incomplete"
    }
    $parseResult = Invoke-A12Api -Name "M2 parse result restore" -Method "GET" -Path "/api/projects/$projectId/materials/$materialId/parse-result"
    if ($parseResult.parseStatus -ne "SUCCEEDED" -or $parseResult.id -ne $parse.id) {
        throw "Parse result was not restored"
    }

    $overview = Invoke-A12Api -Name "M2 knowledge overview" -Method "GET" -Path "/api/projects/$projectId/knowledge/overview"
    if ($overview.indexedMaterialCount -lt 1 -or $overview.chunkCount -lt 3) {
        throw "Knowledge chunks were not created from the uploaded material"
    }

    $search = Invoke-A12Api -Name "M2 knowledge search" -Method "POST" -Path "/api/projects/$projectId/knowledge/search" -Body @{
        query = "Photosynthesis investigation"
        limit = 5
    }
    if ($search.hits.Count -lt 1 -or [string]::IsNullOrWhiteSpace($search.hits[0].sourceFilename) -or [string]::IsNullOrWhiteSpace($search.hits[0].hitReason)) {
        throw "Knowledge search did not return an explainable real-source hit"
    }

    $intent = Invoke-A12Api -Name "M2 teaching intent generation" -Method "POST" -Path "/api/projects/$projectId/teaching-intents/generate"
    if ($intent.status -ne "DRAFT" -or $intent.evidenceItems.Count -lt 1) {
        throw "Teaching intent draft did not contain evidence"
    }
    $intentId = [long]$intent.id
    $latestIntent = Invoke-A12Api -Name "M2 teaching intent latest" -Method "GET" -Path "/api/projects/$projectId/teaching-intents/latest"
    if ($latestIntent.id -ne $intentId) {
        throw "Teaching intent latest did not restore the draft"
    }

    $updatedIntent = Invoke-A12Api -Name "M2 teaching intent update" -Method "PUT" -Path "/api/projects/$projectId/teaching-intents/$intentId" -Body @{
        generationGoal = "Explain photosynthesis with observable evidence"
        contentBasis = $intent.contentBasis
        teachingApproach = "Concept explanation and visual evidence analysis"
        interactionMode = "Teacher prompts, student observation, discussion and feedback"
        outputTypes = @("PPT", "LESSON_PLAN")
        stylePreference = "Clear technology style"
    }
    if ($updatedIntent.generationGoal -ne "Explain photosynthesis with observable evidence") {
        throw "Teaching intent update was not persisted"
    }

    $confirmedIntent = Invoke-A12Api -Name "M2 teaching intent confirmation" -Method "POST" -Path "/api/projects/$projectId/teaching-intents/$intentId/confirm"
    if ($confirmedIntent.status -ne "CONFIRMED" -or [string]::IsNullOrWhiteSpace($confirmedIntent.confirmedAt)) {
        throw "Teaching intent confirmation was not persisted"
    }
    $restoredIntent = Invoke-A12Api -Name "M2 confirmed intent restore" -Method "GET" -Path "/api/projects/$projectId/teaching-intents/latest"
    if ($restoredIntent.status -ne "CONFIRMED" -or $restoredIntent.id -ne $intentId) {
        throw "Confirmed teaching intent was not restored"
    }
}
finally {
    Remove-Item -LiteralPath $temporaryMaterial -Force -ErrorAction SilentlyContinue
}

Write-Host "M1 and M2 Docker smoke test passed."
Write-Host "projectId=$projectId requirementId=$($completeRequirement.id) summaryId=$($summary.id) materialId=$materialId intentId=$intentId sessionId=$sessionId"
