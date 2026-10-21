@echo off
title Lion Agent Frontend Build
echo Packaging frontend...
echo.
cd /d "%~dp0..\lion-frontend"
call npm run build
echo.
echo Frontend build completed. Output: lion-frontend/dist
echo.
pause
