@echo off
setlocal

set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.1"
set "MAVEN_HOME=D:\apache\maven\apache-maven-3.6.3"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"

cd /d "%~dp0"

echo ============================================================
echo  JAVA_HOME : %JAVA_HOME%
echo  MAVEN_HOME: %MAVEN_HOME%
echo ============================================================

call "%MAVEN_HOME%\bin\mvn.cmd" -B clean package -DskipTests
if errorlevel 1 (
    echo.
    echo [ERROR] Build FAILED!
    pause
    exit /b 1
)

echo.
echo [INFO] Build SUCCESS: %~dp0target\lion-mcp-server.jar
pause
endlocal
