$ErrorActionPreference = 'Stop'

$frontendRoot = $PSScriptRoot
$devUrl = 'http://127.0.0.1:5173/'
$goRoot = 'D:\pri_work\LessonForge-go-backend'
$goHealthUrl = 'http://127.0.0.1:8090/healthz'
$goComposeFile = Join-Path $goRoot 'docker-compose.yml'
$goComposeEnvFile = Join-Path $goRoot '.env.lessonforge-desktop'

function Test-LessonForgeService {
    param([string]$Url)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 500
    } catch {
        return $false
    }
}

if (-not (Test-LessonForgeService $goHealthUrl)) {
    if (-not (Test-Path -LiteralPath $goComposeFile) -or -not (Test-Path -LiteralPath $goComposeEnvFile)) {
        throw 'LessonForge Go backend startup configuration is missing.'
    }
    $dockerCommand = (Get-Command docker.exe -ErrorAction Stop).Source
    & $dockerCommand compose --project-name lessonforge-bridge --file $goComposeFile --env-file $goComposeEnvFile up -d postgres server | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'LessonForge Go backend containers could not be started.'
    }
    $deadline = (Get-Date).AddSeconds(45)
    do {
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline -and -not (Test-LessonForgeService $goHealthUrl))
    if (-not (Test-LessonForgeService $goHealthUrl)) {
        throw 'LessonForge Go backend did not become ready within 45 seconds.'
    }
}

function Test-LessonForgeDevServer {
    return Test-LessonForgeService $devUrl
}

function Get-LessonForgeViteProcess {
    return Get-CimInstance Win32_Process | Where-Object {
        $_.Name -eq 'node.exe' -and
        $_.CommandLine -like "*$frontendRoot*vite*bin*vite.js*--port 5173*"
    }
}

$existingVite = @(Get-LessonForgeViteProcess)
if ($existingVite.Count -eq 0 -and (Test-LessonForgeDevServer)) {
    throw '5173 端口已被其他前端服务占用，未加载该服务以避免打开旧版 LessonForge 页面。'
}

if ($existingVite.Count -gt 0 -and -not (Test-LessonForgeDevServer)) {
    # The process can exist briefly after its HTTP listener has gone away. Wait
    # for the stale instance to release the port before starting a replacement.
    $existingVite | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    $deadline = (Get-Date).AddSeconds(10)
    do {
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline -and (Test-LessonForgeDevServer))
}

if (-not (Test-LessonForgeDevServer)) {
    $npmCommand = (Get-Command npm.cmd -ErrorAction Stop).Source
    Start-Process `
        -FilePath $npmCommand `
        -ArgumentList @('run', 'dev', '--', '--mode', 'desktop', '--host', '127.0.0.1', '--port', '5173') `
        -WorkingDirectory $frontendRoot `
        -WindowStyle Hidden | Out-Null

    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline -and -not (Test-LessonForgeDevServer))

    if (-not (Test-LessonForgeDevServer)) {
        throw 'LessonForge frontend server did not become ready within 30 seconds.'
    }
}

$env:LESSONFORGE_DEV_URL = $devUrl
$npmCommand = (Get-Command npm.cmd -ErrorAction Stop).Source
Start-Process `
    -FilePath $npmCommand `
    -ArgumentList @('run', 'desktop') `
    -WorkingDirectory $frontendRoot `
    -WindowStyle Hidden | Out-Null
