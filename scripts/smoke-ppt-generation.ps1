[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# This script intentionally uses the reverse proxy for every authenticated API call.
# It is opt-in for real PPT generation; the default mode exercises only the safe
# preflight and generic-artifact contract checks.
# Required environment: A12_SMOKE_BASE_URL and A12_SMOKE_PROJECT_ID, plus either
# A12_SMOKE_BEARER_TOKEN or A12_SMOKE_USERNAME/A12_SMOKE_PASSWORD. Set
# A12_RUN_PPT_GENERATION_SMOKE=true for one real Harness job. A12_SMOKE_TASK_ID
# resumes an already-created job without creating another one.
$script:SmokeToken = $null
$script:BaseUrl = $null
$script:ProjectId = $null
$script:PlanId = $null
$script:HarnessJobCreated = $false
$script:SupportedActiveStatuses = @(
    "QUEUED",
    "LOADING_REQUIREMENT",
    "LOADING_TEMPLATE",
    "BUILDING_TEMPLATE_CONTEXT",
    "GENERATING_SLIDE_SPEC",
    "VALIDATING_SLIDE_SPEC",
    "REPAIRING_SLIDE_SPEC",
    "RENDERING_PPTX",
    "RENDERING_PREVIEW",
    "RUNNING_DETERMINISTIC_QA",
    "VISUAL_REVIEW",
    "REVISING",
    "FINALIZING",
    "RETRY_PENDING"
)
$script:SupportedTerminalStatuses = @("SUCCEEDED", "FAILED", "CANCELLED")

function Fail-Smoke {
    param(
        [Parameter(Mandatory = $true)][string]$Classification,
        [Parameter(Mandatory = $true)][string]$Message
    )

    throw [System.InvalidOperationException]::new("SMOKE_FAILURE|$Classification|$Message")
}

function Get-EnvironmentValue {
    param([Parameter(Mandatory = $true)][string]$Name)

    return [Environment]::GetEnvironmentVariable($Name)
}

function Get-PropertyValue {
    param(
        [AllowNull()][object]$Object,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if ($null -eq $Object) {
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Get-ResponseStatusCode {
    param([AllowNull()][object]$Response)

    if ($null -eq $Response) {
        return 0
    }
    try {
        return [int]$Response.StatusCode
    }
    catch {
        return 0
    }
}

function Read-ResponseBody {
    param([AllowNull()][object]$Response)

    if ($null -eq $Response) {
        return $null
    }
    try {
        $stream = $Response.GetResponseStream()
        if ($null -eq $stream) {
            return $null
        }
        $reader = New-Object System.IO.StreamReader(
            $stream,
            [System.Text.Encoding]::UTF8,
            $true
        )
        try {
            return $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
    }
    catch {
        return $null
    }
}

function ConvertFrom-SmokeJson {
    param([AllowNull()][string]$RawBody)

    if ([string]::IsNullOrWhiteSpace($RawBody)) {
        return $null
    }
    try {
        return $RawBody | ConvertFrom-Json
    }
    catch {
        return $null
    }
}

function Get-SafeErrorMessage {
    param(
        [AllowNull()][string]$RawBody,
        [AllowNull()][object]$Envelope,
        [string]$Fallback = "The API request failed"
    )

    $candidate = [string](Get-PropertyValue $Envelope "message")
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        $candidate = [string](Get-PropertyValue $Envelope "error")
    }
    if ([string]::IsNullOrWhiteSpace($candidate) -and -not [string]::IsNullOrWhiteSpace($RawBody)) {
        $candidate = $RawBody.Trim()
    }
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        return $Fallback
    }

    $candidate = $candidate.Trim()
    if ($candidate -match "(?i)(exception|stack\s*trace|node:|java\.|at\s+.+\(|[A-Za-z]:\\|/app/|/workspace/|bearer\s+|token|api[_-]?key|password|secret)") {
        return $Fallback
    }
    if ($candidate.Length -gt 240) {
        return $candidate.Substring(0, 240)
    }
    return $candidate
}

function Get-ApiMessage {
    param([AllowNull()][object]$Envelope)

    return [string](Get-PropertyValue $Envelope "message")
}

function Invoke-SmokeJson {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][ValidateSet("GET", "POST", "PUT")][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowNull()][object]$Body = $null,
        [int[]]$AllowedStatus = @(200),
        [int]$TimeoutSec = 30,
        [string]$FailureClassification = "PROJECT_PRECONDITION_FAILED",
        [switch]$NoAuth
    )

    $url = "$script:BaseUrl$Path"
    Write-Host "$Method $Path ($Name)"

    $parameters = @{
        Uri = $url
        Method = $Method
        TimeoutSec = $TimeoutSec
        UseBasicParsing = $true
    }
    if (-not $NoAuth -and -not [string]::IsNullOrWhiteSpace($script:SmokeToken)) {
        # The token is kept in process memory and is never included in output.
        $parameters.Headers = @{ Authorization = "Bearer $script:SmokeToken" }
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json; charset=utf-8"
        $json = $Body | ConvertTo-Json -Depth 12 -Compress
        $parameters.Body = [System.Text.Encoding]::UTF8.GetBytes($json)
    }

    $webResponse = $null
    $rawBody = $null
    $status = 0
    try {
        $webResponse = Invoke-WebRequest @parameters
        $status = Get-ResponseStatusCode $webResponse
        $webResponse.RawContentStream.Position = 0
        $reader = New-Object System.IO.StreamReader(
            $webResponse.RawContentStream,
            [System.Text.Encoding]::UTF8,
            $true
        )
        try {
            $rawBody = $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
    }
    catch {
        $status = Get-ResponseStatusCode $_.Exception.Response
        $rawBody = Read-ResponseBody $_.Exception.Response
        if ($status -eq 0) {
            Fail-Smoke $FailureClassification "$Name could not reach the reverse proxy"
        }
    }

    $envelope = ConvertFrom-SmokeJson $rawBody
    if ($AllowedStatus -notcontains $status) {
        $safeMessage = Get-SafeErrorMessage $rawBody $envelope
        $httpFailureClassification = if ($status -eq 401 -or $status -eq 403) { "AUTH_FAILED" } else { $FailureClassification }
        Fail-Smoke $httpFailureClassification "$Name returned HTTP ${status}: $safeMessage"
    }
    if ($null -eq $envelope) {
        Fail-Smoke $FailureClassification "$Name returned HTTP $status with an invalid JSON envelope"
    }

    $apiCode = Get-PropertyValue $envelope "code"
    if ($status -ge 200 -and $status -lt 300 -and $null -ne $apiCode -and [int]$apiCode -ne 0) {
        $safeMessage = Get-SafeErrorMessage $rawBody $envelope
        Fail-Smoke $FailureClassification "$Name returned API code ${apiCode}: $safeMessage"
    }

    return [pscustomobject]@{
        StatusCode = $status
        Envelope = $envelope
        Data = Get-PropertyValue $envelope "data"
        RawBody = $rawBody
    }
}

function Invoke-HealthCheck {
    Write-Host "GET /healthz (reverse proxy health)"
    try {
        $response = Invoke-WebRequest -Uri "$script:BaseUrl/healthz" -Method GET -UseBasicParsing -TimeoutSec 20
        $status = Get-ResponseStatusCode $response
        if ($status -lt 200 -or $status -ge 300) {
            Fail-Smoke "PROJECT_PRECONDITION_FAILED" "Reverse proxy health returned HTTP $status"
        }
    }
    catch {
        $status = Get-ResponseStatusCode $_.Exception.Response
        if ($status -ne 0) {
            Fail-Smoke "PROJECT_PRECONDITION_FAILED" "Reverse proxy health returned HTTP $status"
        }
        Fail-Smoke "PROJECT_PRECONDITION_FAILED" "Reverse proxy health could not be reached"
    }
}

function Get-ArtifactItems {
    param([Parameter(Mandatory = $true)][string]$FailureClassification)

    $response = Invoke-SmokeJson `
        -Name "list artifacts" `
        -Method GET `
        -Path "/api/projects/$script:ProjectId/artifacts" `
        -FailureClassification $FailureClassification
    if ($null -eq $response.Data -or -not ($response.Data -is [System.Array])) {
        Fail-Smoke $FailureClassification "Artifact list response.data was not an array"
    }
    return @($response.Data)
}

function Get-ArtifactIds {
    param(
        [Parameter(Mandatory = $true)][object[]]$Artifacts,
        [string]$Type = ""
    )

    $selected = if ([string]::IsNullOrWhiteSpace($Type)) {
        @($Artifacts)
    }
    else {
        @($Artifacts | Where-Object { [string](Get-PropertyValue $_ "type") -eq $Type })
    }
    return @($selected | ForEach-Object { [string](Get-PropertyValue $_ "id") } | Sort-Object)
}

function Assert-NonPptArtifactResponse {
    param([Parameter(Mandatory = $true)][object]$Data)

    if ($null -eq $Data -or -not ($Data -is [System.Array])) {
        Fail-Smoke "GENERIC_CONTRACT_FAILED" "Generic artifact response.data was not an array"
    }
    $items = @($Data)
    if ($items.Count -ne 2) {
        Fail-Smoke "GENERIC_CONTRACT_FAILED" "Generic DOCX/INTERACTION response contained $($items.Count) artifacts instead of 2"
    }
    $types = @($items | ForEach-Object { [string](Get-PropertyValue $_ "type") } | Sort-Object)
    if (($types -join ",") -ne "DOCX,INTERACTION") {
        Fail-Smoke "GENERIC_CONTRACT_FAILED" "Generic artifact response contained an unexpected type set"
    }
    foreach ($item in $items) {
        if ([string]::IsNullOrWhiteSpace([string](Get-PropertyValue $item "id"))) {
            Fail-Smoke "GENERIC_CONTRACT_FAILED" "Generic artifact response contained an artifact without an id"
        }
    }
    return $items
}

function Get-PptJobPath {
    param([Parameter(Mandatory = $true)][string]$TaskId)

    $encodedTaskId = [Uri]::EscapeDataString($TaskId)
    return "/api/projects/$script:ProjectId/ppt-harness/jobs/$encodedTaskId"
}

function Assert-PptJob {
    param(
        [Parameter(Mandatory = $true)][object]$Job,
        [Parameter(Mandatory = $true)][string]$ExpectedTaskId
    )

    $taskId = [string](Get-PropertyValue $Job "taskId")
    if ([string]::IsNullOrWhiteSpace($taskId) -or $taskId -ne $ExpectedTaskId) {
        Fail-Smoke "HARNESS_CREATE_FAILED" "Harness status did not return the expected taskId"
    }
    $jobProjectId = Get-PropertyValue $Job "projectId"
    if ($null -eq $jobProjectId -or [long]$jobProjectId -ne [long]$script:ProjectId) {
        Fail-Smoke "HARNESS_CREATE_FAILED" "Harness job projectId did not match the requested project"
    }
    $status = [string](Get-PropertyValue $Job "status")
    if ($status -notin @($script:SupportedActiveStatuses + $script:SupportedTerminalStatuses)) {
        Fail-Smoke "HARNESS_JOB_FAILED" "Harness returned an unsupported job status"
    }
    $progress = Get-PropertyValue $Job "progressPercent"
    if ($null -eq $progress -or [int]$progress -lt 0 -or [int]$progress -gt 100) {
        Fail-Smoke "HARNESS_JOB_FAILED" "Harness returned progress outside the range 0..100"
    }
    return $status
}

function Get-PptJob {
    param(
        [Parameter(Mandatory = $true)][string]$TaskId,
        [int]$TimeoutSec = 30
    )

    $response = Invoke-SmokeJson `
        -Name "PPT Harness job status" `
        -Method GET `
        -Path (Get-PptJobPath $TaskId) `
        -TimeoutSec $TimeoutSec `
        -FailureClassification "HARNESS_JOB_FAILED"
    if ($null -eq $response.Data) {
        Fail-Smoke "HARNESS_JOB_FAILED" "Harness status response.data was empty"
    }
    return $response.Data
}

function Test-PptHarnessEvents {
    param([Parameter(Mandatory = $true)][string]$TaskId)

    $eventsPath = "$(Get-PptJobPath $TaskId)/events"
    $eventsUrl = "$script:BaseUrl$eventsPath"
    $client = New-Object System.Net.Http.HttpClient
    $request = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::Get,
        $eventsUrl
    )
    $response = $null
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(5)
        $request.Headers.Accept.ParseAdd("text/event-stream")
        $request.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue(
            "Bearer",
            $script:SmokeToken
        )
        $response = $client.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
        ).GetAwaiter().GetResult()
        $status = [int]$response.StatusCode
        Write-Host "GET $eventsPath (SSE auth probe) -> HTTP $status"
        if ($status -eq 401 -or $status -eq 403) {
            Fail-Smoke "AUTH_FAILED" "Authenticated Harness SSE probe returned HTTP $status"
        }
        if ($status -eq 404) {
            Fail-Smoke "HARNESS_CREATE_FAILED" "Authenticated Harness SSE probe returned HTTP 404"
        }
        if ($status -lt 200 -or $status -ge 300) {
            Write-Host "SSE connection error; status polling fallback will be used"
            return $false
        }
        return $true
    }
    catch {
        if ($_.Exception.Message -match "^SMOKE_FAILURE\|") {
            throw
        }
        # A streaming endpoint may not close within the short probe window. The
        # status endpoint remains the bounded fallback for terminal observation.
        Write-Host "SSE connection error; status polling fallback will be used"
        return $false
    }
    finally {
        if ($null -ne $response) {
            $response.Dispose()
        }
        $request.Dispose()
        $client.Dispose()
    }
}

function Get-SafeJobError {
    param([Parameter(Mandatory = $true)][object]$Job)

    $errorRef = Get-PropertyValue $Job "error"
    $errorCode = [string](Get-PropertyValue $errorRef "code")
    $errorMessage = Get-SafeErrorMessage $null $errorRef "PPT Harness job failed"
    if ([string]::IsNullOrWhiteSpace($errorCode) -or $errorCode -notmatch "^[A-Za-z0-9_.-]{1,64}$") {
        $errorCode = "UNKNOWN"
    }
    return "errorCode=$errorCode errorMessage=$errorMessage"
}

function Get-ArtifactMetadata {
    param(
        [Parameter(Mandatory = $true)][object]$Artifact,
        [Parameter(Mandatory = $true)][string]$TaskId
    )

    $content = Get-PropertyValue $Artifact "content"
    $jobArtifact = $script:LastPptJobArtifact
    $fileName = Get-PropertyValue $jobArtifact "fileName"
    $sizeBytes = Get-PropertyValue $jobArtifact "sizeBytes"
    $sha256 = Get-PropertyValue $jobArtifact "sha256"

    # The persisted Artifact content is the actual backend metadata contract.
    # Only read these fields when the response contains them; do not invent a
    # file schema for an endpoint that does not provide one.
    if ($null -eq $fileName) { $fileName = Get-PropertyValue $content "fileName" }
    if ($null -eq $sizeBytes) { $sizeBytes = Get-PropertyValue $content "sizeBytes" }
    if ($null -eq $sha256) { $sha256 = Get-PropertyValue $content "sha256" }

    if ($null -ne $fileName -and [string]::IsNullOrWhiteSpace([string]$fileName)) {
        Fail-Smoke "HARNESS_JOB_FAILED" "PPT Artifact fileName was empty"
    }
    if ($null -ne $sizeBytes -and [long]$sizeBytes -le 0) {
        Fail-Smoke "HARNESS_JOB_FAILED" "PPT Artifact sizeBytes was not positive"
    }
    if ($null -ne $sha256 -and [string]::IsNullOrWhiteSpace([string]$sha256)) {
        Fail-Smoke "HARNESS_JOB_FAILED" "PPT Artifact sha256 was empty"
    }

    $hashPrefix = "not-provided"
    if (-not [string]::IsNullOrWhiteSpace([string]$sha256)) {
        $hashText = [string]$sha256
        $hashPrefix = if ($hashText.Length -gt 12) { $hashText.Substring(0, 12) } else { $hashText }
    }
    $sizeText = if ($null -eq $sizeBytes) { "not-provided" } else { [string]$sizeBytes }
    $fileText = if ([string]::IsNullOrWhiteSpace([string]$fileName)) { "not-provided" } else { [string]$fileName }
    Write-Host "PPT Artifact id=$([string](Get-PropertyValue $Artifact 'id')) versionId=$([string](Get-PropertyValue $Artifact 'versionId')) fileName=$fileText sizeBytes=$sizeText sha256Prefix=$hashPrefix"

    return [pscustomobject]@{
        FileName = $fileName
        SizeBytes = $sizeBytes
        Sha256 = $sha256
    }
}

try {
    $rawBaseUrl = Get-EnvironmentValue "A12_SMOKE_BASE_URL"
    if ([string]::IsNullOrWhiteSpace($rawBaseUrl)) {
        Fail-Smoke "SMOKE_PRECONDITION_FAILED" "A12_SMOKE_BASE_URL is required"
    }
    try {
        $baseUri = [Uri]$rawBaseUrl.TrimEnd("/")
    }
    catch {
        Fail-Smoke "SMOKE_PRECONDITION_FAILED" "A12_SMOKE_BASE_URL is not a valid URL"
    }
    if (-not $baseUri.IsAbsoluteUri -or $baseUri.Scheme -notin @("http", "https") -or -not [string]::IsNullOrWhiteSpace($baseUri.Query) -or -not [string]::IsNullOrWhiteSpace($baseUri.Fragment)) {
        Fail-Smoke "SMOKE_PRECONDITION_FAILED" "A12_SMOKE_BASE_URL must be an absolute HTTP(S) origin without query or fragment"
    }
    $script:BaseUrl = $baseUri.AbsoluteUri.TrimEnd("/")
    Write-Host "baseHost=$($baseUri.GetLeftPart([UriPartial]::Authority))"

    $rawProjectId = Get-EnvironmentValue "A12_SMOKE_PROJECT_ID"
    [long]$parsedProjectId = 0
    if ([string]::IsNullOrWhiteSpace($rawProjectId) -or -not [long]::TryParse($rawProjectId, [Globalization.NumberStyles]::Integer, [Globalization.CultureInfo]::InvariantCulture, [ref]$parsedProjectId) -or $parsedProjectId -le 0) {
        Fail-Smoke "SMOKE_PRECONDITION_FAILED" "A12_SMOKE_PROJECT_ID must be a positive integer"
    }
    $script:ProjectId = $parsedProjectId
    Write-Host "projectId=$script:ProjectId"

    $rawPollSeconds = Get-EnvironmentValue "A12_SMOKE_POLL_INTERVAL_SECONDS"
    [double]$pollSeconds = 2.5
    if (-not [string]::IsNullOrWhiteSpace($rawPollSeconds)) {
        if (-not [double]::TryParse($rawPollSeconds, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$pollSeconds) -or $pollSeconds -lt 2 -or $pollSeconds -gt 3) {
            Fail-Smoke "SMOKE_PRECONDITION_FAILED" "A12_SMOKE_POLL_INTERVAL_SECONDS must be between 2 and 3"
        }
    }
    $pollMilliseconds = [int][Math]::Round($pollSeconds * 1000)

    $rawTimeoutSeconds = Get-EnvironmentValue "A12_SMOKE_TIMEOUT_SECONDS"
    [int]$timeoutSeconds = 600
    if (-not [string]::IsNullOrWhiteSpace($rawTimeoutSeconds)) {
        if (-not [int]::TryParse($rawTimeoutSeconds, [Globalization.NumberStyles]::Integer, [Globalization.CultureInfo]::InvariantCulture, [ref]$timeoutSeconds) -or $timeoutSeconds -lt 30 -or $timeoutSeconds -gt 600) {
            Fail-Smoke "SMOKE_PRECONDITION_FAILED" "A12_SMOKE_TIMEOUT_SECONDS must be between 30 and 600"
        }
    }
    $runPptGeneration = ([string](Get-EnvironmentValue "A12_RUN_PPT_GENERATION_SMOKE")).Trim().Equals("true", [StringComparison]::OrdinalIgnoreCase)

    Invoke-HealthCheck

    $bearerToken = Get-EnvironmentValue "A12_SMOKE_BEARER_TOKEN"
    if (-not [string]::IsNullOrWhiteSpace($bearerToken)) {
        $script:SmokeToken = $bearerToken
        Write-Host "auth=Bearer token from environment (value withheld)"
    }
    else {
        $username = Get-EnvironmentValue "A12_SMOKE_USERNAME"
        $password = Get-EnvironmentValue "A12_SMOKE_PASSWORD"
        if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) {
            Fail-Smoke "AUTH_FAILED" "Provide A12_SMOKE_BEARER_TOKEN or both A12_SMOKE_USERNAME and A12_SMOKE_PASSWORD"
        }
        $activeRole = Get-EnvironmentValue "A12_SMOKE_ACTIVE_ROLE"
        if ([string]::IsNullOrWhiteSpace($activeRole)) {
            $activeRole = "TEACHER"
        }
        $loginResponse = Invoke-SmokeJson `
            -Name "authenticated login" `
            -Method POST `
            -Path "/api/v1/auth/login" `
            -Body @{ username = $username; password = $password; activeRole = $activeRole } `
            -FailureClassification "AUTH_FAILED" `
            -NoAuth
        $loginData = $loginResponse.Data
        $script:SmokeToken = [string](Get-PropertyValue $loginData "token")
        if ([string]::IsNullOrWhiteSpace($script:SmokeToken)) {
            Fail-Smoke "AUTH_FAILED" "Authenticated login did not return a bearer token"
        }
        Write-Host "auth=login session (token withheld)"
    }

    $workspaceResponse = Invoke-SmokeJson `
        -Name "generation workspace preflight" `
        -Method GET `
        -Path "/api/projects/$script:ProjectId/generation/workspace" `
        -FailureClassification "PROJECT_PRECONDITION_FAILED"
    $workspace = $workspaceResponse.Data
    if ($null -eq $workspace -or [long](Get-PropertyValue $workspace "projectId") -ne [long]$script:ProjectId) {
        Fail-Smoke "PROJECT_PRECONDITION_FAILED" "Generation workspace did not match the requested project"
    }
    $plan = Get-PropertyValue $workspace "latestPlan"
    $planIdValue = Get-PropertyValue $plan "id"
    if ($null -eq $plan -or $null -eq $planIdValue -or -not [bool](Get-PropertyValue $plan "confirmed")) {
        Fail-Smoke "PROJECT_PRECONDITION_FAILED" "Generation workspace has no confirmed latest plan"
    }
    $script:PlanId = [long]$planIdValue
    Write-Host "planId=$script:PlanId confirmed=true"

    $genericBody = @{ planId = $script:PlanId; artifactTypes = @("DOCX", "INTERACTION") }
    $genericResponse = Invoke-SmokeJson `
        -Name "generic DOCX/INTERACTION generation" `
        -Method POST `
        -Path "/api/projects/$script:ProjectId/artifacts/generate" `
        -Body $genericBody `
        -TimeoutSec 180 `
        -FailureClassification "GENERIC_CONTRACT_FAILED"
    $firstGenericArtifacts = Assert-NonPptArtifactResponse $genericResponse.Data
    $firstGenericIds = (Get-ArtifactIds $firstGenericArtifacts) -join ","
    Write-Host "generic DOCX/INTERACTION HTTP $($genericResponse.StatusCode) artifactIds=$firstGenericIds"

    $secondGenericBefore = Get-ArtifactItems "GENERIC_CONTRACT_FAILED"
    $repeatedGenericResponse = Invoke-SmokeJson `
        -Name "generic DOCX/INTERACTION idempotence" `
        -Method POST `
        -Path "/api/projects/$script:ProjectId/artifacts/generate" `
        -Body $genericBody `
        -TimeoutSec 180 `
        -FailureClassification "GENERIC_CONTRACT_FAILED"
    $repeatedGenericArtifacts = Assert-NonPptArtifactResponse $repeatedGenericResponse.Data
    $repeatedGenericIds = (Get-ArtifactIds $repeatedGenericArtifacts) -join ","
    if ($firstGenericIds -ne $repeatedGenericIds) {
        Fail-Smoke "GENERIC_CONTRACT_FAILED" "Repeated DOCX/INTERACTION generation returned different artifact ids"
    }
    $secondGenericAfter = Get-ArtifactItems "GENERIC_CONTRACT_FAILED"
    $secondNonPptBeforeIds = (Get-ArtifactIds $secondGenericBefore "DOCX") + (Get-ArtifactIds $secondGenericBefore "INTERACTION")
    $secondNonPptAfterIds = (Get-ArtifactIds $secondGenericAfter "DOCX") + (Get-ArtifactIds $secondGenericAfter "INTERACTION")
    if (($secondNonPptBeforeIds | Sort-Object) -join "," -ne (($secondNonPptAfterIds | Sort-Object) -join ",")) {
        Fail-Smoke "GENERIC_CONTRACT_FAILED" "Repeated DOCX/INTERACTION generation changed the existing artifact id set"
    }
    Write-Host "generic idempotence=stable artifact ids"

    $pptBefore = Get-ArtifactItems "GENERIC_CONTRACT_FAILED"
    $pptRejectResponse = Invoke-SmokeJson `
        -Name "generic PPT rejection" `
        -Method POST `
        -Path "/api/projects/$script:ProjectId/artifacts/generate" `
        -Body @{ planId = $script:PlanId; artifactTypes = @("PPT") } `
        -AllowedStatus @(400) `
        -TimeoutSec 30 `
        -FailureClassification "GENERIC_CONTRACT_FAILED"
    $rejectMessage = Get-ApiMessage $pptRejectResponse.Envelope
    if ([string]::IsNullOrWhiteSpace($rejectMessage) -or $rejectMessage -notmatch "(?i)ppt|harness") {
        Fail-Smoke "GENERIC_CONTRACT_FAILED" "Generic PPT request returned HTTP 400 without the expected Harness contract message"
    }
    $pptAfter = Get-ArtifactItems "GENERIC_CONTRACT_FAILED"
    $pptBeforeIds = (Get-ArtifactIds $pptBefore "PPT") -join ","
    $pptAfterIds = (Get-ArtifactIds $pptAfter "PPT") -join ","
    if ($pptBeforeIds -ne $pptAfterIds) {
        Fail-Smoke "GENERIC_CONTRACT_FAILED" "Generic PPT rejection changed the PPT artifact id set"
    }
    Write-Host "generic PPT rejection HTTP $($pptRejectResponse.StatusCode) no Harness create request issued"

    if (-not $runPptGeneration) {
        Write-Host "PPT GENERATION SKIPPED (set A12_RUN_PPT_GENERATION_SMOKE=true to run one real Harness job)"
        Write-Host "SMOKE_PASSED mode=default projectId=$script:ProjectId planId=$script:PlanId"
        exit 0
    }

    $taskId = Get-EnvironmentValue "A12_SMOKE_TASK_ID"
    $job = $null
    if ([string]::IsNullOrWhiteSpace($taskId)) {
        $createResponse = Invoke-SmokeJson `
            -Name "PPT Harness create job" `
            -Method POST `
            -Path "/api/projects/$script:ProjectId/ppt-harness/jobs" `
            -TimeoutSec 60 `
            -FailureClassification "HARNESS_CREATE_FAILED"
        $job = $createResponse.Data
        $taskId = [string](Get-PropertyValue $job "taskId")
        if ([string]::IsNullOrWhiteSpace($taskId)) {
            Fail-Smoke "HARNESS_CREATE_FAILED" "Harness create response did not return taskId"
        }
        $script:HarnessJobCreated = $true
        Write-Host "Harness create HTTP $($createResponse.StatusCode) taskId=$taskId"
    }
    else {
        Write-Host "resuming existing taskId=$taskId; no second Harness job will be created"
    }

    if ($null -eq $job) {
        $job = Get-PptJob $taskId
    }
    $currentStatus = Assert-PptJob $job $taskId
    Write-Host "taskId=$taskId status=$currentStatus progress=$([int](Get-PropertyValue $job 'progressPercent'))"

    $sseConnected = Test-PptHarnessEvents $taskId
    if ($sseConnected) {
        Write-Host "SSE authenticated=HTTP 200; bounded status polling will observe terminal state"
    }

    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    while ($currentStatus -notin $script:SupportedTerminalStatuses) {
        if ([DateTime]::UtcNow -ge $deadline) {
            Fail-Smoke "HARNESS_TIMEOUT" "Harness taskId=$taskId did not reach a terminal status within $timeoutSeconds seconds"
        }
        Start-Sleep -Milliseconds $pollMilliseconds
        $job = Get-PptJob $taskId
        $currentStatus = Assert-PptJob $job $taskId
        Write-Host "taskId=$taskId status=$currentStatus progress=$([int](Get-PropertyValue $job 'progressPercent'))"
    }
    Write-Host "terminalStatus=$currentStatus pollingStopped=true"

    if ($currentStatus -ne "SUCCEEDED") {
        Fail-Smoke "HARNESS_JOB_FAILED" "taskId=$taskId status=$currentStatus $(Get-SafeJobError $job)"
    }

    $script:LastPptJobArtifact = Get-PropertyValue $job "artifact"
    if ($null -eq $script:LastPptJobArtifact) {
        Fail-Smoke "HARNESS_JOB_FAILED" "taskId=$taskId status=SUCCEEDED did not include a Harness artifact reference"
    }
    $finalArtifacts = Get-ArtifactItems "HARNESS_JOB_FAILED"
    $pptMatches = @($finalArtifacts | Where-Object {
        [string](Get-PropertyValue $_ "type") -eq "PPT" -and
        [string](Get-PropertyValue (Get-PropertyValue $_ "content") "harnessTaskId") -eq $taskId
    })
    if ($pptMatches.Count -ne 1) {
        Fail-Smoke "HARNESS_JOB_FAILED" "SUCCEEDED taskId=$taskId did not produce exactly one linked PPT Artifact"
    }
    $pptArtifact = $pptMatches[0]
    $metadata = Get-ArtifactMetadata $pptArtifact $taskId

    $versionIdValue = Get-PropertyValue $pptArtifact "versionId"
    if ($null -eq $versionIdValue) {
        Fail-Smoke "HARNESS_JOB_FAILED" "PPT Artifact did not provide versionId"
    }
    $versionResponse = Invoke-SmokeJson `
        -Name "list ArtifactVersion" `
        -Method GET `
        -Path "/api/v1/projects/$script:ProjectId/artifact-versions" `
        -FailureClassification "HARNESS_JOB_FAILED"
    if ($null -eq $versionResponse.Data -or -not ($versionResponse.Data -is [System.Array])) {
        Fail-Smoke "HARNESS_JOB_FAILED" "ArtifactVersion response.data was not an array"
    }
    $version = @($versionResponse.Data | Where-Object { [long](Get-PropertyValue $_ "id") -eq [long]$versionIdValue }) | Select-Object -First 1
    if ($null -eq $version) {
        Fail-Smoke "HARNESS_JOB_FAILED" "ArtifactVersion id=$versionIdValue was not returned for the generated PPT Artifact"
    }
    Write-Host "ArtifactVersion id=$([string](Get-PropertyValue $version 'id')) versionNumber=$([string](Get-PropertyValue $version 'versionNumber')) artifactCount=$([string](Get-PropertyValue $version 'artifactCount'))"
    Write-Host "SMOKE_PASSED mode=real projectId=$script:ProjectId planId=$script:PlanId taskId=$taskId terminalStatus=$currentStatus"
    exit 0
}
catch {
    $message = $_.Exception.Message
    $classification = "SMOKE_PRECONDITION_FAILED"
    $safeMessage = "Smoke harness failed before a classified result"
    if ($message -match "^SMOKE_FAILURE\|([^|]+)\|(.*)$") {
        $classification = $Matches[1]
        $safeMessage = $Matches[2]
    }
    Write-Error "SMOKE_FAILED classification=$classification message=$safeMessage"
    exit 1
}
