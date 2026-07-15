Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot

try {
    Write-Host "Starting reverse-proxy single entry, backend-api, frontend-web, and monitor-log."
    docker compose up --build -d
}
finally {
    Pop-Location
}
