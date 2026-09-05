@echo off
title Lion Agent Backend Build
echo Packaging backend...
echo.
cd /d "%~dp0.."
call mvn clean package -DskipTests
echo.
echo Backend build completed. Output: target/lion-agent-1.0.0.jar
echo.
pause
