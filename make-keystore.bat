@echo off
setlocal EnableDelayedExpansion
rem Aman Kids TV - create your private signing key (run ONCE, then keep a backup!)
cd /d "%~dp0"

if exist "keystore\aman-release.jks" (
  echo [!] keystore\aman-release.jks already exists - nothing to do.
  echo     Keep a backup of this file and local.properties. Do NOT create a new one.
  pause
  exit /b 0
)

findstr /b /c:"release.storeFile" local.properties >nul 2>&1
if not errorlevel 1 (
  echo [!] local.properties already has release.* lines but the keystore file is missing.
  echo     Restore keystore\aman-release.jks from your backup instead of creating a new key.
  pause
  exit /b 1
)

set "KEYTOOL="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\keytool.exe" set "KEYTOOL=%JAVA_HOME%\bin\keytool.exe"
if not defined KEYTOOL if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\keytool.exe" set "KEYTOOL=%ProgramFiles%\Android\Android Studio\jbr\bin\keytool.exe"
if not defined KEYTOOL if exist "%ProgramFiles%\Android\Android Studio\jre\bin\keytool.exe" set "KEYTOOL=%ProgramFiles%\Android\Android Studio\jre\bin\keytool.exe"
if not defined KEYTOOL if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\keytool.exe" set "KEYTOOL=%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\keytool.exe"
if not defined KEYTOOL for %%k in (keytool.exe) do if not "%%~$PATH:k"=="" set "KEYTOOL=%%~$PATH:k"
if not defined KEYTOOL (
  echo [x] keytool not found. Install Android Studio, or set JAVA_HOME, then run again.
  pause
  exit /b 1
)

rem random 32-char password
for /f "usebackq delims=" %%p in (`powershell -NoProfile -Command "-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 32 | ForEach-Object {[char]$_})"`) do set "PASS=%%p"
if not defined PASS (
  echo [x] could not generate a password.
  pause
  exit /b 1
)

if not exist keystore mkdir keystore
"%KEYTOOL%" -genkeypair -v -keystore "keystore\aman-release.jks" -alias aman -keyalg RSA -keysize 4096 -validity 10000 -storepass "!PASS!" -keypass "!PASS!" -dname "CN=Aman Kids TV, O=Aman Kids TV, C=SA"
if errorlevel 1 (
  echo [x] keytool failed.
  pause
  exit /b 1
)

>>local.properties echo.
>>local.properties echo # private signing key - keep a backup of keystore\aman-release.jks and these lines
>>local.properties echo release.storeFile=keystore/aman-release.jks
>>local.properties echo release.storePassword=!PASS!
>>local.properties echo release.keyAlias=aman
>>local.properties echo release.keyPassword=!PASS!

echo.
echo [OK] Created keystore\aman-release.jks and added its settings to local.properties.
echo      BACK UP both files somewhere safe (USB / private cloud). If you lose them,
echo      future updates will need uninstalling the app first.
echo      Now build: gradlew assembleRelease
pause
