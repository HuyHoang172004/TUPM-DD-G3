@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch-lab.ps1" %*
if errorlevel 1 (
    echo.
    echo Lab could not start. Read the error above and .lab\logs, then run this file again.
    pause
    exit /b 1
)
