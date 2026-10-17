@echo off
setlocal
set GRADLE_VERSION=8.11.1
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set CACHE_DIR=%GRADLE_USER_HOME%\openlens-distributions\gradle-%GRADLE_VERSION%
set GRADLE_BIN=%CACHE_DIR%\gradle-%GRADLE_VERSION%\bin\gradle.bat
if not exist "%GRADLE_BIN%" (
  if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip='%CACHE_DIR%\gradle.zip'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $zip; $expected='f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'; $actual=(Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant(); if ($actual -ne $expected) { Remove-Item -Force $zip; throw 'Gradle distribution checksum mismatch' }; Expand-Archive -Force $zip '%CACHE_DIR%'; Remove-Item $zip"
  if errorlevel 1 exit /b %errorlevel%
)
call "%GRADLE_BIN%" %*
exit /b %errorlevel%
