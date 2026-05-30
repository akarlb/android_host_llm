@echo off
rem Lightweight text-only Gradle launcher.
rem The normal Gradle wrapper requires gradle-wrapper.jar, but this repository's
rem PR pipeline rejects binary files. This script delegates to an installed
rem Gradle executable while preserving the documented gradlew.bat entry point.

setlocal
set GRADLE_BIN=%GRADLE_CMD%
if "%GRADLE_BIN%"=="" set GRADLE_BIN=gradle

where %GRADLE_BIN% >nul 2>nul
if errorlevel 1 (
  echo ERROR: Gradle is required but was not found on PATH. 1>&2
  echo Install Gradle 8.9+ and JDK 17, then rerun: 1>&2
  echo   gradlew.bat clean assembleDebug 1>&2
  echo Or set GRADLE_CMD to an explicit Gradle executable path. 1>&2
  exit /b 127
)

%GRADLE_BIN% %*
exit /b %ERRORLEVEL%
