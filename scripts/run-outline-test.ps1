[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8090',
    [Parameter(Mandatory = $true)]
    [string]$PdfPath,
    [string]$Email = 'admin',
    [int]$ModelConnectionId = 0,
    [int]$TimeoutSeconds = 900,
    [string]$OutputPath = '',
    [string]$Message = 'Choose one teachable topic from this material and generate a courseware outline. Ground it in the uploaded material. Include learning objectives, key points, teaching units, and per-slide topics. Proceed with defaults. If teacher confirmation is truly required, ask only one confirmation question: approve outline generation. The test script will answer it automatically.',
    [string]$TextAnswer = 'Approve outline generation'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
Add-Type -AssemblyName System.Net.Http

function ConvertFrom-SecureStringToPlainText {
    param([Security.SecureString]$SecureValue)
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

function Invoke-LessonForgeJson {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers,
        [object]$Body = $null
    )
    $client = [System.Net.Http.HttpClient]::new()
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$BaseUrl$Path")
    $content = $null
    try {
        foreach ($header in $Headers.GetEnumerator()) {
            $null = $request.Headers.TryAddWithoutValidation([string]$header.Key, [string]$header.Value)
        }
        if ($null -ne $Body) {
            $bodyText = $Body | ConvertTo-Json -Depth 20 -Compress
            $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($bodyText)
            $content = [System.Net.Http.ByteArrayContent]::new($bodyBytes)
            $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/json')
            $request.Content = $content
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $responseBytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $responseText = [System.Text.Encoding]::UTF8.GetString($responseBytes)
        if (-not $response.IsSuccessStatusCode) {
            throw "HTTP $([int]$response.StatusCode): $responseText"
        }
        return $responseText | ConvertFrom-Json
    }
    finally {
        if ($content) { $content.Dispose() }
        $request.Dispose()
        $client.Dispose()
    }
}

function Get-AccessToken {
    $cookieDb = Join-Path $env:APPDATA 'LessonForge\Network\Cookies'
    if (Test-Path -LiteralPath $cookieDb) {
        $sqlite = Get-Command sqlite3 -ErrorAction SilentlyContinue
        if ($sqlite) {
            $cookie = (& $sqlite.Source $cookieDb "select value from cookies where host_key='127.0.0.1' and name='lessonforge_session' limit 1" 2>$null).Trim()
            if ($cookie) {
                try {
                    $headers = @{ Cookie = "lessonforge_session=$cookie"; Accept = 'application/json' }
                    $null = Invoke-LessonForgeJson -Method Get -Path '/api/auth/me' -Headers $headers
                    return @{ Token = $null; Cookie = $cookie }
                }
                catch { }
            }
        }
    }

    $securePassword = Read-Host "Enter the LessonForge password for $Email" -AsSecureString
    $password = ConvertFrom-SecureStringToPlainText $securePassword
    try {
        $login = Invoke-LessonForgeJson -Method Post -Path '/api/auth/login' -Headers @{ Accept = 'application/json' } -Body @{ email = $Email; password = $password }
        if (-not $login.token) { throw 'Login response did not contain a session token.' }
        return @{ Token = [string]$login.token; Cookie = $null }
    }
    finally { $password = $null }
}

function New-AuthorizedHeaders {
    param([hashtable]$Session)
    $headers = @{ Accept = 'application/json' }
    if ($Session.Token) { $headers.Authorization = "Bearer $($Session.Token)" }
    if ($Session.Cookie) { $headers.Cookie = "lessonforge_session=$($Session.Cookie)" }
    return $headers
}

function Upload-Pdf {
    param([hashtable]$Headers, [string]$Path)
    $client = [System.Net.Http.HttpClient]::new()
    try {
        if ($Headers.Authorization) {
            $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', ($Headers.Authorization -replace '^Bearer\s+', ''))
        }
        if ($Headers.Cookie) { $client.DefaultRequestHeaders.Add('Cookie', $Headers.Cookie) }
        $form = [System.Net.Http.MultipartFormDataContent]::new()
        $stream = [IO.File]::OpenRead($Path)
        try {
            $file = [System.Net.Http.StreamContent]::new($stream)
            $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/pdf')
            $form.Add($file, 'file', [IO.Path]::GetFileName($Path))
            $response = $client.PostAsync("$BaseUrl/api/uploads", $form).GetAwaiter().GetResult()
            $json = [System.Text.Encoding]::UTF8.GetString($response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult())
            if (-not $response.IsSuccessStatusCode) { throw "Upload failed ($([int]$response.StatusCode)): $json" }
            return $json | ConvertFrom-Json
        }
        finally {
            $stream.Dispose()
            $form.Dispose()
        }
    }
    finally { $client.Dispose() }
}

if (-not $PdfPath) { throw 'PdfPath is required.' }
if (-not (Test-Path -LiteralPath $PdfPath -PathType Leaf)) { throw "PDF not found: $PdfPath" }

$session = Get-AccessToken
$headers = New-AuthorizedHeaders $session
$me = Invoke-LessonForgeJson -Method Get -Path '/api/auth/me' -Headers $headers
Write-Host "Authenticated as: $($me.user.email) (user $($me.user.id))"

$rawConnections = Invoke-LessonForgeJson -Method Get -Path '/api/model-connections' -Headers $headers
$connections = @($rawConnections | ForEach-Object {
    if ($_ -is [System.Array]) { $_ | ForEach-Object { $_ } } else { $_ }
})
$connection = if ($ModelConnectionId -gt 0) {
    $connections | Where-Object { @($_.id) -contains [int64]$ModelConnectionId } | Select-Object -First 1
}
else {
    $connections | Where-Object {
        $_.enabled -eq $true -and
        ([string]$_.verificationStatus -eq 'VERIFIED' -or [string]$_.verification_status -eq 'VERIFIED') -and
        ([string]$_.name -match 'DeepSeek')
    } | Select-Object -First 1
}
if (-not $connection) { throw 'No enabled and verified DeepSeek connection was found. Use -ModelConnectionId.' }
Write-Host "Planning model: $($connection.name) / $($connection.modelId) (connection $($connection.id))"

$upload = Upload-Pdf -Headers $headers -Path $PdfPath
Write-Host "Material uploaded: $($upload.file.name); upload id $($upload.uploadId)"

$missionBody = @{
    title = 'AI Agents course outline generation test'
    description = 'Generate a material-grounded course outline only; do not generate a PPT.'
    message = $Message
    uploadIds = @([string]$upload.uploadId)
    modelConnectionId = [int]$connection.id
}
$created = Invoke-LessonForgeJson -Method Post -Path '/api/missions' -Headers $headers -Body $missionBody
$missionId = [int]$created.missionId
Write-Host "Mission created: $missionId; initial AgentRun: $($created.agentRunId)"

$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$detail = $null
while ([DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Seconds 5
    $detail = Invoke-LessonForgeJson -Method Get -Path "/api/missions/$missionId" -Headers $headers
    $questions = @(Invoke-LessonForgeJson -Method Get -Path "/api/missions/$missionId/questions" -Headers $headers)
    $pendingQuestion = $questions |
        Where-Object { $null -eq $_.latestAnswer } |
        Sort-Object createdAt |
        Select-Object -First 1
    if ($pendingQuestion) {
        $selected = @()
        $textAnswer = ''
        if ([string]$pendingQuestion.type -eq 'TEXT') {
            $textAnswer = $TextAnswer
        }
        else {
            $options = @($pendingQuestion.options)
            $preferred = $options | Where-Object { [string]$_ -match '(?i)agree|yes|continue|generate|default|recommend|approve' } | Select-Object -First 1
            if (-not $preferred) { $preferred = $options | Select-Object -First 1 }
            if ($preferred) { $selected = @([string]$preferred) }
        }
        $answerBody = @{ selectedValues = $selected; textAnswer = $textAnswer }
        $answer = Invoke-LessonForgeJson -Method Post -Path "/api/questions/$($pendingQuestion.id)/answers" -Headers $headers -Body $answerBody
        $chosen = if ($selected.Count -gt 0) { $selected -join ', ' } else { $textAnswer }
        Write-Host "Auto-answered clarification: $chosen; continuing outline generation (AgentRun $($answer.agentRunId))"
        continue
    }

    $runs = @(Invoke-LessonForgeJson -Method Get -Path "/api/missions/$missionId/agent-runs" -Headers $headers)
    $latestRun = $runs | Sort-Object createdAt -Descending | Select-Object -First 1
    $status = if ($latestRun) { [string]$latestRun.status } else { 'UNKNOWN' }
    $draft = $detail.currentDraft
    Write-Host "[$([DateTime]::Now.ToString('HH:mm:ss'))] AgentRun=$status Draft=$([bool]$draft)"
    if ($draft) { break }
    if ($status -in @('FAILED', 'CANCELLED')) {
        throw "AgentRun did not complete: $status; $($latestRun.errorCode) $($latestRun.errorMessage)"
    }
}

if (-not $detail.currentDraft) { throw "No course outline draft was produced within $TimeoutSeconds seconds. Mission=$missionId" }
$result = [ordered]@{
    missionId = $missionId
    missionTitle = $detail.mission.title
    modelConnectionId = $connection.id
    model = $connection.modelId
    draft = $detail.currentDraft
}
$json = $result | ConvertTo-Json -Depth 50
if ($OutputPath) {
    $json | Set-Content -LiteralPath $OutputPath -Encoding UTF8
    Write-Host "Outline saved: $OutputPath"
}
Write-Output $json
