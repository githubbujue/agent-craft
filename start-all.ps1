# AgentCraft one-click background start: all services run as hidden processes, no popup windows
# Usage: powershell -ExecutionPolicy Bypass -File .\start-all.ps1
# Tail logs: Get-Content .\logs\python.log -Encoding UTF8 -Wait -Tail 30  (java/frontend/redis 同理)
$ErrorActionPreference = "SilentlyContinue"
$root = $PSScriptRoot
# 所有服务日志统一收敛到 <root>\logs\ (历史日志归档在 logs\archive\),
# 避免 java / python / frontend 各写一处, 排查时要分头找
$logs = Join-Path $root "logs"
New-Item -ItemType Directory -Force -Path $logs | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $logs "archive") | Out-Null

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
# 重定向写的是绝对路径: 子进程的工作目录各不相同(java 在根、python 在 python-service、
# frontend 在 frontend), 以前用相对路径 logs\x.log 就变成了三个不同的物理目录。
# chcp 65001: 让子进程控制台代码页为 UTF-8; 日志文件编码由进程自己决定,
# 用 Get-Content 读取时必须加 -Encoding UTF8, 否则 PowerShell 5.1 按 ANSI 解码会显示乱码。
# 服务间内部调用 token（Java -> Python /api/internal/*）：
# 必须与 python-service/.env 里的 INTERNAL_TOKEN 完全一致；不一致或不设置时，
# 摘要不会报错，而是自动降级为本地关键词桩（质量明显下降），所以要改就两处一起改。
$env:INTERNAL_TOKEN = "r2JuwBzjxxyPvbcdSu8iMaQMJ06SJRl2"

$javaLog = Join-Path $logs "java.log"
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", ('chcp 65001 >nul && java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar target\ai-knowledge-system-0.0.1-SNAPSHOT.jar > "' + $javaLog + '" 2>&1') -WorkingDirectory $root -WindowStyle Hidden
Write-Host ("  started (hidden) -> " + $javaLog)

Write-Host "== [4/5] Python AI service (8000) ==" -ForegroundColor Cyan
$pythonLog = Join-Path $logs "python.log"
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", ('chcp 65001 >nul && set PYTHONUTF8=1&& set PYTHONIOENCODING=utf-8&& venv\Scripts\python -m uvicorn main:app --host 127.0.0.1 --port 8000 > "' + $pythonLog + '" 2>&1') -WorkingDirectory (Join-Path $root "python-service") -WindowStyle Hidden
Write-Host ("  started (hidden) -> " + $pythonLog)

Write-Host "== [5/5] React frontend (3000) ==" -ForegroundColor Cyan
$frontendLog = Join-Path $logs "frontend.log"
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", ('chcp 65001 >nul && npm run dev > "' + $frontendLog + '" 2>&1') -WorkingDirectory (Join-Path $root "frontend") -WindowStyle Hidden
Write-Host ("  started (hidden) -> " + $frontendLog)

Write-Host ""
Write-Host "All services started in background. Ready in ~30-40s:" -ForegroundColor Green
Write-Host "  User:    http://localhost:3000/login"
Write-Host "  Admin:   http://localhost:3000/admin/login"
Write-Host ""
Write-Host "Logs: .\logs\  (history: .\logs\archive\)" -ForegroundColor Cyan
Write-Host "Tail a service log in THIS terminal (-Encoding UTF8 必带, 否则中文乱码):"
Write-Host "  Get-Content .\logs\python.log   -Encoding UTF8 -Wait -Tail 30" -ForegroundColor Yellow
Write-Host "  Get-Content .\logs\java.log     -Encoding UTF8 -Wait -Tail 30" -ForegroundColor Yellow
Write-Host "  Get-Content .\logs\frontend.log -Encoding UTF8 -Wait -Tail 30" -ForegroundColor Yellow
Write-Host "  Get-Content .\logs\redis.log    -Encoding UTF8 -Wait -Tail 30" -ForegroundColor Yellow
Write-Host "Stop all: powershell -ExecutionPolicy Bypass -File .\stop-all.ps1"
