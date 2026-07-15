Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot

try {
    Write-Host "Stopping reverse-proxy, backend-api, frontend-web, and monitor-log."
    docker compose down
}
finally {
    Pop-Location
}
