# AgentCraft one-click stop for all services (MySQL Windows service keeps running)
# Usage: powershell -ExecutionPolicy Bypass -File .\stop-all.ps1
$ErrorActionPreference = "SilentlyContinue"

taskkill /IM redis-server.exe /F 2>$null | Out-Null
foreach ($port in @(3000, 8080, 8000)) {
    Get-NetTCPConnection -LocalPort $port -State Listen |
        Select-Object -ExpandProperty OwningProcess -Unique |
        ForEach-Object {
            Write-Host ("  Stopping PID " + $_ + " (port " + $port + ")")
            Stop-Process -Id $_ -Force
        }
}
Write-Host "All AgentCraft services stopped (MySQL system service kept running)" -ForegroundColor Green
