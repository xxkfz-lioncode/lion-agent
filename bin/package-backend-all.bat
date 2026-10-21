@echo off
title Lion Agent Build All Backend Modules
echo Building ALL backend modules (lion-agent-app / lion-mcp-server / lion-a2a-server)...
echo.
cd /d "%~dp0.."

rem Pin JDK 21 (pom.xml java.version=21). Change this path to switch JDK.
set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.1"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] java not found: %JAVA_HOME%\bin\java.exe
    echo Please install JDK 21 or fix JAVA_HOME in this script.
    pause
    exit /b 1
)
echo Using JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version

rem Runs against the aggregator root pom, so Maven builds every module in the
rem reactor order: lion-parent -> lion-agent-app -> lion-mcp-server -> lion-a2a-server.
call mvn -B clean package -DskipTests
if errorlevel 1 (
    echo.
    echo [ERROR] Build FAILED. Fix the failing module listed above and retry.
    echo         Tip: build one module only with:
    echo              mvn -B clean package -DskipTests -pl lion-mcp-server -am
    pause
    exit /b 1
)

echo.
echo [INFO] Build SUCCESS. Artifacts:
echo   lion-agent-app\target\lion-agent-app-1.0.0.jar
echo   lion-mcp-server\target\lion-mcp-server.jar
echo   lion-a2a-server\target\lion-a2a-server-1.0.0.jar
echo.
echo Note: lion-agent-app launches lion-mcp-server itself in stdio mode, so that
echo       jar path must match src/main/resources/mcp-servers.json.
echo       Use package-backend.bat when you only need to rebuild the app module.
echo.
pause
