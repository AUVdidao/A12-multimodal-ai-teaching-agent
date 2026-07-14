Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$frontendPort = if ([string]::IsNullOrWhiteSpace($env:FRONTEND_PORT)) { "8081" } else { $env:FRONTEND_PORT }
$backendPort = if ([string]::IsNullOrWhiteSpace($env:BACKEND_PORT)) { "8080" } else { $env:BACKEND_PORT }
$baseUrl = "http://localhost:$frontendPort"
$readinessUrl = "http://localhost:$backendPort/api/health"
$deadline = [DateTime]::UtcNow.AddSeconds(60)
$lastReadinessError = "No response received"
$script:authToken = $null

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
        if (-not [string]::IsNullOrWhiteSpace($script:authToken)) {
            $parameters.Headers = @{ Authorization = "Bearer $script:authToken" }
        }
        if ($null -ne $Body) {
            $parameters.ContentType = "application/json; charset=utf-8"
            $json = $Body | ConvertTo-Json -Depth 10 -Compress
            $parameters.Body = [System.Text.Encoding]::UTF8.GetBytes($json)
        }

        # Windows PowerShell 5 may decode application/json without an explicit
        # charset as Windows-1252. Read the response bytes as UTF-8 so Chinese
        # plan content can be round-tripped without mojibake.
        $webResponse = Invoke-WebRequest @parameters -UseBasicParsing
        $webResponse.RawContentStream.Position = 0
        $reader = New-Object System.IO.StreamReader(
            $webResponse.RawContentStream,
            [System.Text.Encoding]::UTF8,
            $true
        )
        try {
            $response = $reader.ReadToEnd() | ConvertFrom-Json
        }
        finally {
            $reader.Dispose()
        }
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
    $raw = & curl.exe -sS --fail-with-body -X POST -H "Authorization: Bearer $script:authToken" -F "file=@$FilePath;type=$ContentType" -F "description=$Description" $url 2>&1
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

Write-Host "Checking unauthenticated teacher API rejection"
try {
    Invoke-WebRequest -Uri "$baseUrl/api/projects" -UseBasicParsing -TimeoutSec 20 | Out-Null
    throw "Unauthenticated teacher API unexpectedly returned success"
}
catch {
    $status = if ($null -ne $_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    if ($status -ne 401) {
        throw "Unauthenticated teacher API should return 401 but returned $status"
    }
}

$studentPassword = if ([string]::IsNullOrWhiteSpace($env:A12_DEMO_STUDENT_PASSWORD)) { "Student123!" } else { $env:A12_DEMO_STUDENT_PASSWORD }
$studentSession = Invoke-A12Api -Name "student login" -Method "POST" -Path "/api/v1/auth/login" -Body @{
    username = "student"
    password = $studentPassword
    activeRole = "STUDENT"
}
$script:authToken = $studentSession.token
Write-Host "Checking student access rejection on teacher API"
try {
    Invoke-WebRequest -Uri "$baseUrl/api/projects" -Headers @{ Authorization = "Bearer $script:authToken" } -UseBasicParsing -TimeoutSec 20 | Out-Null
    throw "Student token unexpectedly accessed the teacher project API"
}
catch {
    $status = if ($null -ne $_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    if ($status -ne 403) {
        throw "Student access to teacher API should return 403 but returned $status"
    }
}

$script:authToken = $null
$teacherPassword = if ([string]::IsNullOrWhiteSpace($env:A12_DEMO_TEACHER_PASSWORD)) { "Teacher123!" } else { $env:A12_DEMO_TEACHER_PASSWORD }
$teacherSession = Invoke-A12Api -Name "teacher login" -Method "POST" -Path "/api/v1/auth/login" -Body @{
    username = "teacher"
    password = $teacherPassword
    activeRole = "TEACHER"
}
if ([string]::IsNullOrWhiteSpace($teacherSession.token) -or $teacherSession.user.activeRole -ne "TEACHER") {
    throw "Teacher login did not return a TEACHER session"
}
$script:authToken = $teacherSession.token
$currentUser = Invoke-A12Api -Name "current authenticated user" -Method "GET" -Path "/api/v1/auth/me"
if ($currentUser.username -ne "teacher" -or $currentUser.activeRole -ne "TEACHER") {
    throw "Authenticated user profile does not match the teacher demo account"
}

$null = Invoke-A12Api -Name "AI workflow status" -Method "GET" -Path "/api/ai-workflow/status"
$null = Invoke-A12Api -Name "model modes" -Method "GET" -Path "/api/model-modes"

$project = Invoke-A12Api -Name "project creation" -Method "POST" -Path "/api/projects" -Body @{
    projectName = "M1 to M3 Docker smoke project"
    courseName = "Biology"
    chapterTitle = "Photosynthesis"
    targetStudents = "Grade 8"
    lessonDuration = 45
    description = "Dynamic M1 to M3 smoke data"
}
if ($null -eq $project.id) {
    throw "Project creation did not return an id"
}
$projectId = [long]$project.id

$workspaceOverview = Invoke-A12Api -Name "UI V6 teacher workspace" -Method "GET" -Path "/api/workspace/overview"
if ($workspaceOverview.metrics.projectCount -lt 1) {
    throw "Teacher workspace did not include the created project"
}

$workspaceProjects = Invoke-A12Api -Name "UI V6 project workspace list" -Method "GET" -Path "/api/workspace/projects?page=0&size=10&sort=UPDATED_DESC"
if (@($workspaceProjects.items | Where-Object { $_.id -eq $projectId }).Count -ne 1) {
    throw "Workspace project list did not include the created project"
}

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
$aiContent = "Please add the learner profile, prior knowledge, teaching style, interaction design, and expected outputs."
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

$requirementWorkspace = Invoke-A12Api -Name "UI V6 requirement workspace" -Method "GET" -Path "/api/projects/$projectId/requirements/workspace"
if ($requirementWorkspace.dialogues.Count -lt 2 -or $requirementWorkspace.completeness.total -ne 9) {
    throw "Requirement workspace aggregate is incomplete"
}

$completePayload = @{
    gradeLevel = "Grade 8"
    subject = "Biology"
    topic = "Photosynthesis"
    baselineLevel = "Basic biology knowledge and first exposure to inquiry variables"
    lessonDuration = "45 minutes"
    teachingGoals = "Explain the basic photosynthesis process"
    keyPoints = "Conditions for photosynthesis"
    difficultPoints = "Matter and energy conversion"
    stylePreference = "Inquiry-based visual teaching"
    interactionType = "Prediction, observation, discussion and feedback"
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
    baselineLevel = $summary.baselineLevel
    lessonDuration = $summary.lessonDuration
    teachingGoals = $summary.teachingGoals
    keyPoints = $summary.keyPoints
    difficultPoints = $summary.difficultPoints
    outputTypes = @($summary.outputTypes)
    stylePreference = "Technology style"
    interactionType = $summary.interactionType
}
if ($updatedSummary.topic -ne "Photosynthesis investigation") {
    throw "Summary update was not persisted"
}

$confirmedSummary = Invoke-A12Api -Name "summary confirmation" -Method "POST" -Path "/api/projects/$projectId/requirement-summaries/$($summary.id)/confirm"
if ($confirmedSummary.status -ne "CONFIRMED" -or [string]::IsNullOrWhiteSpace($confirmedSummary.confirmedAt)) {
    throw "Summary confirmation was not persisted"
}

$summaryWorkspace = Invoke-A12Api -Name "UI V6 summary workspace" -Method "GET" -Path "/api/projects/$projectId/requirement-summaries/workspace"
if ($summaryWorkspace.summary.id -ne $summary.id -or $summaryWorkspace.summary.status -ne "CONFIRMED") {
    throw "Summary workspace did not restore the confirmed summary"
}

$temporaryMaterial = Join-Path ([System.IO.Path]::GetTempPath()) "a12-m2-smoke-$([Guid]::NewGuid().ToString('N')).md"
$materialId = $null
$intentId = $null
$planId = $null
$versionId = $null
try {
    $materialText = @"
# Photosynthesis investigation evidence

Photosynthesis converts light energy into chemical energy in chloroplasts.
The inquiry compares light intensity, carbon dioxide availability, and oxygen production.
Students predict variables, observe evidence, explain energy conversion, and complete a classroom quiz.
"@
    [System.IO.File]::WriteAllText(
        $temporaryMaterial,
        $materialText,
        [System.Text.UTF8Encoding]::new($false)
    )

    $material = Invoke-A12Multipart -Name "M2 material upload" -Path "/api/projects/$projectId/materials" -FilePath $temporaryMaterial -ContentType "text/markdown" -Description "Generated smoke-test teaching text"
    if ($null -eq $material.id -or $material.originalFilename -notlike "a12-m2-smoke-*.md") {
        throw "Material upload did not return the generated smoke file metadata"
    }
    $materialId = [long]$material.id

    $materials = Invoke-A12Api -Name "M2 material list" -Method "GET" -Path "/api/projects/$projectId/materials"
    if (@($materials | Where-Object { $_.id -eq $materialId }).Count -ne 1) {
        throw "Uploaded material was not restored by the material list"
    }

    $usage = Invoke-A12Api -Name "M2 material usage binding" -Method "PUT" -Path "/api/projects/$projectId/materials/$materialId/usages" -Body @{
        usageTypes = @("TEXTBOOK_BASIS", "KNOWLEDGE_SUPPLEMENT")
        note = "Use extracted text for concept explanation and inquiry evidence"
    }
    if ($usage.usageTypes.Count -ne 2) {
        throw "Material usages were not persisted"
    }

    $parse = Invoke-A12Api -Name "M2 prototype parsing" -Method "POST" -Path "/api/projects/$projectId/materials/$materialId/parse"
    if ($parse.parseStatus -ne "SUCCEEDED" -or [string]::IsNullOrWhiteSpace($parse.summary) -or $parse.keywords.Count -lt 3) {
        throw "Prototype parse result is incomplete"
    }
    if ($parse.summary -notmatch "Markdown UTF-8" -or $parse.summary -notmatch "Photosynthesis investigation evidence") {
        throw "Prototype parse result did not use the uploaded document text"
    }
    $parseResult = Invoke-A12Api -Name "M2 parse result restore" -Method "GET" -Path "/api/projects/$projectId/materials/$materialId/parse-result"
    if ($parseResult.parseStatus -ne "SUCCEEDED" -or $parseResult.id -ne $parse.id) {
        throw "Parse result was not restored"
    }

    $materialWorkspace = Invoke-A12Api -Name "UI V6 material workspace" -Method "GET" -Path "/api/projects/$projectId/materials/workspace"
    if (-not $materialWorkspace.uploadPolicy.uploadEnabled -or $materialWorkspace.statistics.total -lt 1 -or @($materialWorkspace.materials | Where-Object { $_.id -eq $materialId }).Count -ne 1) {
        throw "Material workspace did not include the uploaded material"
    }

    $overview = Invoke-A12Api -Name "M2 knowledge overview" -Method "GET" -Path "/api/projects/$projectId/knowledge/overview"
    if ($overview.indexedMaterialCount -lt 1 -or $overview.chunkCount -lt 3) {
        throw "Knowledge chunks were not created from the uploaded material"
    }

    $search = Invoke-A12Api -Name "M2 knowledge search" -Method "POST" -Path "/api/projects/$projectId/knowledge/search" -Body @{
        query = "Photosynthesis investigation"
        limit = 5
    }

    $workspaceSearch = Invoke-A12Api -Name "UI V6 knowledge workspace search" -Method "POST" -Path "/api/projects/$projectId/knowledge/workspace-search" -Body @{
        query = "Photosynthesis investigation"
        matchMode = "PRECISE"
        caseSensitive = $false
        page = 0
        size = 10
    }
    if ($workspaceSearch.totalElements -lt 1 -or $workspaceSearch.hits.Count -lt 1) {
        throw "Knowledge workspace search did not return a real-source hit"
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

    $updatedIntentWorkspace = Invoke-A12Api -Name "UI V6 teaching intent workspace update" -Method "PUT" -Path "/api/projects/$projectId/teaching-intents/$intentId/workspace" -Body @{
        generationGoals = @("KNOWLEDGE_UNDERSTANDING", "CONCEPT_MASTERY", "APPLICATION_ABILITY")
        primaryBasis = "OFFICIAL_OUTLINE"
        supplementalBasis = @("LOCAL_KNOWLEDGE", "MATERIAL_EVIDENCE")
        targetAudience = "Grade 8"
        totalHours = 16
        teachingFormat = "MIXED"
        outputTypes = @("OUTLINE", "PPT", "ACTIVITY", "ASSESSMENT")
        stylePreference = "Clear inquiry-based technology style"
        notes = "Use observable evidence and classroom discussion to connect concepts with application."
    }
    if ($updatedIntentWorkspace.intent.generationGoals.Count -ne 3 -or $updatedIntentWorkspace.intent.primaryBasis -ne "OFFICIAL_OUTLINE") {
        throw "Teaching intent workspace update was not persisted"
    }

    $confirmedIntent = Invoke-A12Api -Name "M2 teaching intent confirmation" -Method "POST" -Path "/api/projects/$projectId/teaching-intents/$intentId/confirm"
    if ($confirmedIntent.status -ne "CONFIRMED" -or [string]::IsNullOrWhiteSpace($confirmedIntent.confirmedAt)) {
        throw "Teaching intent confirmation was not persisted"
    }
    $restoredIntent = Invoke-A12Api -Name "M2 confirmed intent restore" -Method "GET" -Path "/api/projects/$projectId/teaching-intents/latest"
    if ($restoredIntent.status -ne "CONFIRMED" -or $restoredIntent.id -ne $intentId) {
        throw "Confirmed teaching intent was not restored"
    }

    $intentWorkspace = Invoke-A12Api -Name "UI V6 teaching intent workspace" -Method "GET" -Path "/api/projects/$projectId/teaching-intents/workspace"
    if ($intentWorkspace.intent.id -ne $intentId -or $intentWorkspace.intent.status -ne "CONFIRMED") {
        throw "Teaching intent workspace did not restore the confirmed intent"
    }

    $projectWorkspace = Invoke-A12Api -Name "UI V6 project overview" -Method "GET" -Path "/api/projects/$projectId/workspace-overview"
    if ($projectWorkspace.project.id -ne $projectId -or $projectWorkspace.timeline.Count -lt 8) {
        throw "Project workspace overview is incomplete"
    }

    $generationWorkspace = Invoke-A12Api -Name "M3 generation workspace readiness" -Method "GET" -Path "/api/projects/$projectId/generation/workspace"
    if ($generationWorkspace.teachingIntent.status -ne "CONFIRMED" -or -not $generationWorkspace.capabilities.canCreatePlan) {
        throw "Generation workspace did not expose the confirmed teaching intent"
    }

    $plan = Invoke-A12Api -Name "M3 generation plan creation" -Method "POST" -Path "/api/projects/$projectId/generation-plans"
    if ($null -eq $plan.id -or $plan.confirmed -or $plan.provider -ne "MOCK") {
        throw "Generation plan creation returned an invalid plan"
    }
    $planId = [long]$plan.id

    $pptOutline = @($plan.pptOutline)
    $docOutline = @($plan.docOutline)
    $interactionPlan = @($plan.interactionPlan)
    $pptOutline[0].title = "Photosynthesis learning journey"
    $editedPlan = Invoke-A12Api -Name "M3 generation plan edit" -Method "PUT" -Path "/api/projects/$projectId/generation-plans/$planId" -Body @{
        pptOutline = $pptOutline
        docOutline = $docOutline
        interactionPlan = $interactionPlan
    }
    if ($editedPlan.pptOutline[0].title -ne "Photosynthesis learning journey") {
        throw "Generation plan edit was not persisted"
    }
    # Windows PowerShell 5 reads UTF-8 scripts without a BOM through the
    # active ANSI code page. Build the expected Chinese title from code points
    # so the assertion verifies the API round trip instead of the script parser.
    $expectedSecondTitle = -join @([char]0x60C5, [char]0x5883, [char]0x5BFC, [char]0x5165)
    if ($editedPlan.pptOutline[1].title -ne $expectedSecondTitle) {
        throw "Generation plan UTF-8 content was corrupted during the edit round trip"
    }

    $confirmedPlan = Invoke-A12Api -Name "M3 generation plan confirmation" -Method "POST" -Path "/api/projects/$projectId/generation-plans/$planId/confirm"
    if (-not $confirmedPlan.confirmed) {
        throw "Generation plan confirmation was not persisted"
    }

    $artifacts = @(Invoke-A12Api -Name "M3 artifact generation" -Method "POST" -Path "/api/projects/$projectId/artifacts/generate" -Body @{
        planId = $planId
    })
    if ($artifacts.Count -ne 3) {
        throw "Artifact generation did not return all three artifact types"
    }
    $pptArtifact = $artifacts | Where-Object { $_.type -eq "PPT" } | Select-Object -First 1
    $docArtifact = $artifacts | Where-Object { $_.type -eq "DOCX" } | Select-Object -First 1
    $interactionArtifact = $artifacts | Where-Object { $_.type -eq "INTERACTION" } | Select-Object -First 1
    if ($null -eq $pptArtifact -or @($pptArtifact.content.slides).Count -lt 7) {
        throw "PPT artifact does not contain the required slide structure"
    }
    if ($null -eq $docArtifact -or @($docArtifact.content.sections).Count -lt 9) {
        throw "Lesson-plan artifact does not contain the required sections"
    }
    if ($null -eq $interactionArtifact -or @($interactionArtifact.content.questions).Count -lt 3) {
        throw "Interaction artifact does not contain the required questions"
    }
    $versionId = [long]$pptArtifact.versionId

    $repeatedArtifacts = @(Invoke-A12Api -Name "M3 idempotent artifact generation" -Method "POST" -Path "/api/projects/$projectId/artifacts/generate" -Body @{
        planId = $planId
    })
    $firstIds = @($artifacts | ForEach-Object { [long]$_.id }) -join ","
    $repeatedIds = @($repeatedArtifacts | ForEach-Object { [long]$_.id }) -join ","
    if ($firstIds -ne $repeatedIds) {
        throw "Repeated artifact generation created different artifacts"
    }

    $generatedWorkspace = Invoke-A12Api -Name "M3 generated workspace restore" -Method "GET" -Path "/api/projects/$projectId/generation/workspace"
    if ($generatedWorkspace.projectStatus -ne "GENERATED" -or -not $generatedWorkspace.capabilities.canPreview -or @($generatedWorkspace.artifacts).Count -ne 3) {
        throw "Generated workspace did not restore the M3 result"
    }
}
finally {
    Remove-Item -LiteralPath $temporaryMaterial -Force -ErrorAction SilentlyContinue
}

Write-Host "M1 to M3 Docker smoke test passed."
Write-Host "projectId=$projectId requirementId=$($completeRequirement.id) summaryId=$($summary.id) materialId=$materialId intentId=$intentId planId=$planId versionId=$versionId sessionId=$sessionId"
