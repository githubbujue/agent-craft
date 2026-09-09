# AgentCraft one-click background start: all services run as hidden processes, no popup windows
# Usage: powershell -ExecutionPolicy Bypass -File .\start-all.ps1
# Tail logs: Get-Content .\logs\python.log -Wait -Tail 30   (same for java/frontend/redis)
$ErrorActionPreference = "SilentlyContinue"
$root = $PSScriptRoot
$logs = Join-Path $root "logs"
New-Item -ItemType Directory -Force -Path $logs | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $root "python-service\logs") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $root "frontend\logs") | Out-Null

Write-Host "== [0/5] Killing old instances ==" -ForegroundColor Cyan
taskkill /IM redis-server.exe /F 2>$null | Out-Null
foreach ($port in @(3000, 8080, 8000)) {
    Get-NetTCPConnection -LocalPort $port -State Listen |
        Select-Object -ExpandProperty OwningProcess -Unique |
        ForEach-Object { Stop-Process -Id $_ -Force }
}
Start-Sleep -Seconds 2

Write-Host "== [1/5] MySQL ==" -ForegroundColor Cyan
$svc = Get-Service -Name MySQL84
if ($svc.Status -ne "Running") { Start-Service MySQL84; Start-Sleep -Seconds 3 }
Write-Host ("  MySQL84: " + (Get-Service MySQL84).Status)

Write-Host "== [2/5] Redis (6379) ==" -ForegroundColor Cyan
$redisExe = "C:\Users\Administrator\AppData\Local\Microsoft\WinGet\Packages\taizod1024.redis-windows-fork_Microsoft.Winget.Source_8wekyb3d8bbwe\Redis-8.10.1-Windows-x64-msys2\redis-server.exe"
Start-Process -FilePath $redisExe -ArgumentList "--port", "6379" -WindowStyle Hidden -WorkingDirectory (Split-Path $redisExe) -RedirectStandardOutput (Join-Path $logs "redis.log") -RedirectStandardError (Join-Path $logs "redis.err.log")
Write-Host "  started (hidden)"

Write-Host "== [3/5] Java backend (8080) ==" -ForegroundColor Cyan
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar target\ai-knowledge-system-0.0.1-SNAPSHOT.jar > logs\java.log 2>&1" -WorkingDirectory $root -WindowStyle Hidden
Write-Host "  started (hidden)"

Write-Host "== [4/5] Python AI service (8000) ==" -ForegroundColor Cyan
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "set PYTHONUTF8=1&& venv\Scripts\python -m uvicorn main:app --host 127.0.0.1 --port 8000 > logs\python.log 2>&1" -WorkingDirectory (Join-Path $root "python-service") -WindowStyle Hidden
Write-Host "  started (hidden)"

Write-Host "== [5/5] React frontend (3000) ==" -ForegroundColor Cyan
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "npm run dev > logs\frontend.log 2>&1" -WorkingDirectory (Join-Path $root "frontend") -WindowStyle Hidden
Write-Host "  started (hidden)"

Write-Host ""
Write-Host "All services started in background. Ready in ~30-40s:" -ForegroundColor Green
Write-Host "  User:    http://localhost:3000/login"
Write-Host "  Admin:   http://localhost:3000/admin/login"
Write-Host ""
Write-Host "Tail a service log in THIS terminal:"
Write-Host "  Get-Content .\logs\python.log -Wait -Tail 30"   -ForegroundColor Yellow
Write-Host "  Get-Content .\logs\java.log -Wait -Tail 30"      -ForegroundColor Yellow
Write-Host "  Get-Content .\logs\frontend.log -Wait -Tail 30"  -ForegroundColor Yellow
Write-Host "Stop all: powershell -ExecutionPolicy Bypass -File .\stop-all.ps1"
