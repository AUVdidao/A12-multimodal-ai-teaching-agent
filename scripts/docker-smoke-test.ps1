Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$frontendPort = if ([string]::IsNullOrWhiteSpace($env:FRONTEND_PORT)) { "8081" } else { $env:FRONTEND_PORT }
$backendPort = if ([string]::IsNullOrWhiteSpace($env:BACKEND_PORT)) { "8080" } else { $env:BACKEND_PORT }
$baseUrl = "http://localhost:$frontendPort"
$readinessUrl = "http://localhost:$backendPort/api/health"
$deadline = [DateTime]::UtcNow.AddSeconds(60)
$lastReadinessError = "No response received"

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

$checks = @(
    @{ Name = "frontend"; Url = "$baseUrl/" },
    @{ Name = "ai workflow status"; Url = "$baseUrl/api/ai-workflow/status" },
    @{ Name = "projects"; Url = "$baseUrl/api/projects" },
    @{ Name = "model modes"; Url = "$baseUrl/api/model-modes" }
)

foreach ($check in $checks) {
    Write-Host "Checking $($check.Name): $($check.Url)"
    try {
        $response = Invoke-WebRequest -Uri $check.Url -UseBasicParsing -TimeoutSec 20
        if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
            throw "HTTP $($response.StatusCode)"
        }
    }
    catch {
        throw "Check failed: $($check.Name) at $($check.Url). Error: $($_.Exception.Message)"
    }
}

Write-Host "Docker smoke test passed."
