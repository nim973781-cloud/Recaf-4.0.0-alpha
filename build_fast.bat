@echo off
chcp 65001 >nul
setlocal EnableExtensions

set "ROOT=%~dp0"
pushd "%ROOT%" >nul

set "JAVA_CMD="
if defined RECAF_JAVA_HOME if exist "%RECAF_JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%RECAF_JAVA_HOME%\bin\java.exe"
if not defined JAVA_CMD if exist "C:\Java\jdk-25\bin\java.exe" set "JAVA_CMD=C:\Java\jdk-25\bin\java.exe"
if not defined JAVA_CMD if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_CMD for /f "delims=" %%I in ('where java.exe 2^>nul') do (
    set "JAVA_CMD=%%I"
    goto :java_found
)
:java_found

if not defined JAVA_CMD (
    echo [ERROR] No Java runtime found.
    echo Set RECAF_JAVA_HOME or JAVA_HOME, or install JDK 25 under C:\Java\jdk-25.
    popd >nul
    pause
    exit /b 1
)

set "JAVA_HOME=%JAVA_CMD:\bin\java.exe=%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if not defined TARGET_VERSION set "TARGET_VERSION=25"

set "TASKS=%*"
if "%~1"=="" set "TASKS=:recaf-ui:shadowJar -x test"

echo [INFO] Using Java: "%JAVA_CMD%"
echo [INFO] TARGET_VERSION=%TARGET_VERSION%
echo [INFO] Running Gradle tasks: %TASKS%
call "%ROOT%gradlew.bat" %TASKS% --parallel --configuration-cache --build-cache
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Fast build failed with code %EXIT_CODE%.
    popd >nul
    pause
    exit /b %EXIT_CODE%
)

popd >nul
exit /b 0
