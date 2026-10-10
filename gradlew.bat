@echo off
setlocal
set "GRADLE_VERSION=8.10.2"
where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
set "CACHE_ROOT=%USERPROFILE%\.gradle\drop-tracker-bootstrap"
set "DIST_DIR=%CACHE_ROOT%\gradle-%GRADLE_VERSION%"
set "ZIP_PATH=%CACHE_ROOT%\gradle-%GRADLE_VERSION%-bin.zip"
if exist "%DIST_DIR%\bin\gradle.bat" goto run_gradle
if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
if not exist "%ZIP_PATH%" (
  echo Gradle not installed; downloading Gradle %GRADLE_VERSION% from services.gradle.org...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP_PATH%'"
  if errorlevel 1 exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $expected='31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26'; $actual=(Get-FileHash -LiteralPath '%ZIP_PATH%' -Algorithm SHA256).Hash.ToLowerInvariant(); if ($actual -ne $expected) { Remove-Item -LiteralPath '%ZIP_PATH%' -Force; throw 'Gradle distribution SHA-256 mismatch; refusing to execute it.' }; Expand-Archive -LiteralPath '%ZIP_PATH%' -DestinationPath '%CACHE_ROOT%' -Force"
if errorlevel 1 exit /b 1
:run_gradle
call "%DIST_DIR%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
