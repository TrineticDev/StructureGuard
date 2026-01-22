@echo off
echo Building StructureGuard Plugin...
echo.

"%TEMP%\gradle-8.8\bin\gradle.bat" clean shadowJar --no-daemon

if %ERRORLEVEL% EQU 0 (
    echo.
    echo Build successful!
    echo JAR location: build\libs\StructureGuard.jar
) else (
    echo.
    echo Build failed!
)

pause
