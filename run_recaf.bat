@echo off
chcp 65001 >nul
setlocal EnableExtensions

set "RECAF_JAR=%~dp0recaf-ui\build\libs\recaf-ui-4.0.0-SNAPSHOT-all.jar"
if not exist "%RECAF_JAR%" (
    echo [ERROR] Missing jar: "%RECAF_JAR%"
    echo Build first: .\gradlew.bat :recaf-ui:shadowJar
    pause
    exit /b 1
)

set "JAVA_CMD="
if defined RECAF_JAVA_HOME if exist "%RECAF_JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%RECAF_JAVA_HOME%\bin\java.exe"
if not defined JAVA_CMD call :find_java_in_common_dirs
if not defined JAVA_CMD if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_CMD call :find_java_from_path

if not defined JAVA_CMD (
    echo [ERROR] No Java runtime found.
    echo Set RECAF_JAVA_HOME or JAVA_HOME to a valid JDK/JRE path.
    pause
    exit /b 1
)

set "JVM_COMMON=--enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -Duser.language=zh -Duser.country=CN"
set "JVM_FAST="
if /I not "%RECAF_FAST_START%"=="0" if /I not "%RECAF_FAST_START%"=="false" if /I not "%RECAF_FAST_START%"=="off" (
    set "JVM_FAST=-XX:TieredStopAtLevel=1"
)

set "JAVAW_CMD=%JAVA_CMD:\bin\java.exe=\bin\javaw.exe%"
if not exist "%JAVAW_CMD%" (
    echo [WARN] javaw.exe not found, fallback to java.exe and keep console visible.
    echo [INFO] Using Java: "%JAVA_CMD%"
    if defined JVM_FAST echo [INFO] Fast start enabled: %JVM_FAST%
    "%JAVA_CMD%" %JVM_COMMON% %JVM_FAST% -jar "%RECAF_JAR%"
    set "EXIT_CODE=%ERRORLEVEL%"
    if not "%EXIT_CODE%"=="0" (
        echo.
        echo [ERROR] Recaf exited with code %EXIT_CODE%.
        echo If you saw "UnsupportedClassVersionError ... class file version 69.0",
        echo you need a newer Java runtime ^(typically JDK 25 for this jar^).
        pause
    )
    exit /b %EXIT_CODE%
)

echo [INFO] Launching Recaf in background with: "%JAVAW_CMD%"
if defined JVM_FAST echo [INFO] Fast start enabled: %JVM_FAST%
start "" /D "%~dp0" "%JAVAW_CMD%" %JVM_COMMON% %JVM_FAST% -jar "%RECAF_JAR%"
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Failed to launch Recaf, start returned code %EXIT_CODE%.
    pause
    exit /b %EXIT_CODE%
)

exit /b 0

:find_java_in_common_dirs
for %%V in (25 24 23 22) do (
    for /d %%P in ("C:\Java\jdk-%%V*") do (
        if exist "%%~fP\bin\java.exe" (
            set "JAVA_CMD=%%~fP\bin\java.exe"
            goto :eof
        )
    )
    for /d %%P in ("%ProgramFiles%\Eclipse Adoptium\jdk-%%V*") do (
        if exist "%%~fP\bin\java.exe" (
            set "JAVA_CMD=%%~fP\bin\java.exe"
            goto :eof
        )
    )
    for /d %%P in ("%ProgramFiles%\Java\jdk-%%V*") do (
        if exist "%%~fP\bin\java.exe" (
            set "JAVA_CMD=%%~fP\bin\java.exe"
            goto :eof
        )
    )
)
goto :eof

:find_java_from_path
for /f "delims=" %%I in ('where java.exe 2^>nul') do (
    set "JAVA_CMD=%%I"
    goto :eof
)
goto :eof
