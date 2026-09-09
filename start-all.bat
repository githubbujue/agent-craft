@echo off
chcp 65001 >nul
title AgentCraft One-Click Start
cd /d "%~dp0"

echo ================================================
echo   AgentCraft 一键启动 (MySQL - Redis - Java - Python - Frontend)
echo ================================================
echo.

echo [0/5] 清理旧实例 (仅杀掉占用本项目端口的进程)...
call :killport 3000
call :killport 8080
call :killport 8000
call :killport 6379
taskkill /IM redis-server.exe /F >nul 2>nul
timeout /t 2 /nobreak >nul

echo [1/5] MySQL: 检查服务...
sc query MySQL84 | find "RUNNING" >nul
if %errorlevel%==0 (
    echo       MySQL 已在运行
) else (
    echo       正在启动 MySQL84 服务...
    net start MySQL84 2>nul || echo       [提示] 启动失败, 请右键本脚本"以管理员身份运行"
)

echo [2/5] Redis: 启动 (端口 6379)...
start "Redis-6379" cmd /k "C:\Users\Administrator\AppData\Local\Microsoft\WinGet\Packages\taizod1024.redis-windows-fork_Microsoft.Winget.Source_8wekyb3d8bbwe\Redis-8.10.1-Windows-x64-msys2\redis-server.exe --port 6379"

echo [3/5] Java 后端: 启动 (端口 8080)...
if exist "target\ai-knowledge-system-0.0.1-SNAPSHOT.jar" (
    start "Java-8080" cmd /k "chcp 65001 >nul && java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar target\ai-knowledge-system-0.0.1-SNAPSHOT.jar"
) else (
    echo       未找到 jar, 改用 mvn spring-boot:run
    start "Java-8080" cmd /k "chcp 65001 >nul && mvn spring-boot:run"
)

echo [4/5] Python AI 服务: 启动 (端口 8000, 无 --reload)...
start "Python-8000" cmd /k "chcp 65001 >nul && set PYTHONIOENCODING=utf-8 && cd /d %~dp0python-service && venv\Scripts\python -m uvicorn main:app --host 127.0.0.1 --port 8000"

echo [5/5] 前端: 启动 (端口 3000)...
start "Frontend-3000" cmd /k "cd /d %~dp0frontend && npm run dev"

echo.
echo ================================================
echo   全部启动命令已发出! 每个服务在独立窗口运行。
echo   请等待约 30-40 秒后访问:
echo     用户端:  http://localhost:3000/login
echo     管理端:  http://localhost:3000/admin/login
echo     AI服务:  http://localhost:8000/health
echo   关闭某个服务 = 直接关闭对应窗口
echo ================================================
echo.
pause
exit /b

:killport
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%1 " ^| findstr "LISTENING"') do taskkill /PID %%a /F >nul 2>nul
exit /b
