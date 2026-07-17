@echo off
rem Double-click entry for start-local.ps1 (args pass through, e.g. start-local.bat -SkipBuild)
rem Prefer pwsh (PowerShell 7); fall back to Windows PowerShell 5.1
cd /d "%~dp0"
where pwsh >nul 2>nul
if %errorlevel%==0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-local.ps1" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-local.ps1" %*
)
pause
