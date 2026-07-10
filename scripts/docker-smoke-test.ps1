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
        throw "Check failed: $Name at $url. Error: $($_.Exception.Message)"
    }
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
    projectName = "M1 Docker smoke project"
    courseName = "Biology"
    chapterTitle = "Photosynthesis"
    targetStudents = "Grade 8"
    lessonDuration = 45
    description = "Dynamic M1 smoke data"
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

Write-Host "M1 Docker smoke test passed."
Write-Host "projectId=$projectId requirementId=$($completeRequirement.id) summaryId=$($summary.id) sessionId=$sessionId"
