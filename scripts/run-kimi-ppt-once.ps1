param(
    [switch]$NoExplorer,
    [string]$TemplateId = "a12-teaching-generic"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$harnessBaseUrl = "http://127.0.0.1:18091"
$downloadDirectory = Join-Path $projectRoot "output\kimi-ppt"
$secretPath = Join-Path $env:LOCALAPPDATA "A12TeachingAgent\secrets\moonshot-api-key.dpapi"

function Convert-SecureStringToPlainText([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Wait-HarnessHealth {
    for ($attempt = 1; $attempt -le 40; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri "$harnessBaseUrl/health" -TimeoutSec 5
            if ($health.status -eq "UP" -and $health.generationSource -eq "KIMI") { return $health }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw "ppt-harness did not become healthy in KIMI mode."
}

Set-Location $projectRoot
Write-Host "A12 Kimi -> SlideSpec -> PPT Harness -> PPTX" -ForegroundColor Cyan
Write-Host "The API key only enters this process and the temporary Harness container." -ForegroundColor Yellow

if (Test-Path -LiteralPath $secretPath) {
    $secureKey = Get-Content -LiteralPath $secretPath -Raw | ConvertTo-SecureString
    Write-Host "Using the encrypted API key saved for this Windows user." -ForegroundColor Green
} else {
    $secureKey = Read-Host "Enter MOONSHOT_API_KEY" -AsSecureString
    if ($null -eq $secureKey -or $secureKey.Length -eq 0) { throw "MOONSHOT_API_KEY cannot be empty." }
    New-Item -ItemType Directory -Path (Split-Path -Parent $secretPath) -Force | Out-Null
    $secureKey | ConvertFrom-SecureString | Set-Content -Path $secretPath -Encoding ascii -NoNewline
    Write-Host "Saved the API key encrypted for this Windows user." -ForegroundColor Green
}
$apiKey = Convert-SecureStringToPlainText $secureKey

$projectContext = @{
    projectId = 167
    courseName = "Grade 8 Biology"
    subject = "Biology"
    topic = "Photosynthesis"
    chapterTopic = "How plants convert light energy into chemical energy"
    targetAudience = "Grade 8 students"
    learnerProfile = "Students can identify chloroplasts after completing a basic plant-cell lesson."
    lessonDurationMinutes = 45
    teachingGoals = @(
        "Explain the inputs, outputs, and conditions of photosynthesis",
        "Use investigation evidence to explain how light affects photosynthesis",
        "Communicate a causal explanation with scientific vocabulary"
    )
    keyPoints = @("chloroplast", "light energy", "carbon dioxide", "water", "glucose", "oxygen")
    difficultPoints = @("energy conversion", "conditions versus products", "experimental evidence")
    teachingStyle = "Inquiry-led, evidence-based, with guided peer discussion"
    interactionDesign = @("prediction poll", "small-group evidence discussion", "exit ticket")
    outputTypes = @("PPT")
    requirementSummary = "Create an eight-slide Grade 8 biology lesson on photosynthesis with a motivating question, learning targets, process explanation, evidence activity, quick check, summary, and homework."
    teachingIntent = @{
        theme = "Photosynthesis investigation"
        pedagogicalApproach = "Question - evidence - explanation"
        assessment = "One prediction question, one group explanation, one exit ticket"
    }
    knowledgeEvidence = @(
        @{ title = "Photosynthesis core concept"; source = "Local biology knowledge base"; excerpt = "Plants use light energy in chloroplasts to convert carbon dioxide and water into glucose and oxygen." },
        @{ title = "Light condition investigation"; source = "Project material evidence"; excerpt = "A controlled light and dark comparison supports discussion of evidence and variables." }
    )
    templateSelection = @{ templateId = $TemplateId; templateVersion = "1.0.0" }
}

$oldSource = $env:PPT_HARNESS_GENERATION_SOURCE
$oldKey = $env:MOONSHOT_API_KEY
$oldModel = $env:KIMI_MODEL
$env:PPT_HARNESS_GENERATION_SOURCE = "KIMI"
$env:MOONSHOT_API_KEY = $apiKey
$env:KIMI_MODEL = "kimi-k3"

try {
    docker compose up -d --build --force-recreate ppt-harness | Out-Host
    $health = Wait-HarnessHealth
    Write-Host "Harness ready: generationSource=$($health.generationSource)" -ForegroundColor Green

    $request = @{
        requestId = "manual-kimi-$(New-Guid)"
        projectId = $projectContext.projectId
        templateId = $TemplateId
        templateVersion = "1.0.0"
        locale = "zh-CN"
        targetSlideCount = 8
        requirementSnapshot = $projectContext
    }
    $response = Invoke-RestMethod -Method Post -Uri "$harnessBaseUrl/api/v1/presentation-jobs" -ContentType "application/json" -Body ($request | ConvertTo-Json -Depth 8) -TimeoutSec 30
    $taskId = $response.taskId
    Write-Host "Task submitted: $taskId" -ForegroundColor Cyan

    $job = $null
    for ($attempt = 1; $attempt -le 96; $attempt++) {
        Start-Sleep -Seconds 5
        $job = Invoke-RestMethod -Uri "$harnessBaseUrl/api/v1/presentation-jobs/$taskId" -TimeoutSec 15
        Write-Host "[$($job.progressPercent)%] $($job.status)" -ForegroundColor DarkCyan
        if ($job.status -in @("SUCCEEDED", "FAILED", "CANCELLED")) { break }
    }
    if ($null -eq $job -or $job.status -ne "SUCCEEDED") {
        $message = if ($null -ne $job -and $null -ne $job.error) { "$($job.error.code): $($job.error.message)" } else { "Timed out waiting for presentation generation." }
        throw "PPT generation failed: $message"
    }

    New-Item -ItemType Directory -Path $downloadDirectory -Force | Out-Null
    $pptxPath = Join-Path $downloadDirectory "a12-kimi-$taskId.pptx"
    $qaPath = Join-Path $downloadDirectory "a12-kimi-$taskId-qa-report.json"
    Invoke-WebRequest -Uri "$harnessBaseUrl/api/v1/presentation-jobs/$taskId/artifact" -OutFile $pptxPath -TimeoutSec 90
    Invoke-RestMethod -Uri "$harnessBaseUrl/api/v1/presentation-jobs/$taskId/qa-report" -TimeoutSec 30 | ConvertTo-Json -Depth 16 | Set-Content -Path $qaPath -Encoding utf8
    $sha256 = (Get-FileHash -Algorithm SHA256 $pptxPath).Hash.ToLowerInvariant()
    $sizeBytes = (Get-Item $pptxPath).Length
    if ($sha256 -ne $job.artifact.sha256 -or $sizeBytes -ne $job.artifact.sizeBytes) {
        throw "Downloaded PPTX hash or size does not match Harness metadata."
    }

    Write-Host "PPTX created: $pptxPath" -ForegroundColor Green
    Write-Host "QA report:    $qaPath" -ForegroundColor Green
    Write-Host "SHA-256:      $sha256" -ForegroundColor Green
    if (-not $NoExplorer) { Start-Process explorer.exe "/select,`"$pptxPath`"" }
}
finally {
    $env:MOONSHOT_API_KEY = $oldKey
    $env:PPT_HARNESS_GENERATION_SOURCE = if ($null -eq $oldSource) { "FIXTURE" } else { $oldSource }
    $env:KIMI_MODEL = $oldModel
    Write-Host "Restoring Fixture Harness without the API key..." -ForegroundColor Yellow
    docker compose up -d --force-recreate ppt-harness | Out-Host
    $apiKey = $null
    $secureKey = $null
}
