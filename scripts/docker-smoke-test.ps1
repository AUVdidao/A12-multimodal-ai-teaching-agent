Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$frontendPort = if ([string]::IsNullOrWhiteSpace($env:FRONTEND_PORT)) { "8081" } else { $env:FRONTEND_PORT }
$baseUrl = "http://localhost:$frontendPort"

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
