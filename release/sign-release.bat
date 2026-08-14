@echo off
REM Signs the release APK with your own keystore.
REM
REM   sign-release.bat <path-to-keystore.jks> <key-alias>
REM
REM apksigner needs a JDK but nothing on this machine sets JAVA_HOME, so it is
REM pointed at the one bundled with Android Studio for the life of this script
REM only. Nothing is written to your environment permanently.
REM
REM The keystore password is never passed on the command line; apksigner asks
REM for it, which keeps it out of your shell history.

setlocal

if "%~2"=="" (
  echo Usage: sign-release.bat ^<path-to-keystore.jks^> ^<key-alias^>
  echo   e.g. sign-release.bat D:\keys\PlayStoreSigningKey.jks upload
  exit /b 1
)

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "BUILD_TOOLS=%LOCALAPPDATA%\Android\Sdk\build-tools\35.0.0"
set "HERE=%~dp0"
set "UNSIGNED=%HERE%testdpc-9.0.14-parental-controls-unsigned.apk"
set "SIGNED=%HERE%testdpc-9.0.14-parental-controls.apk"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Could not find a JDK at "%JAVA_HOME%".
  echo Set JAVA_HOME to your own JDK and run this again.
  exit /b 1
)
if not exist "%BUILD_TOOLS%\apksigner.bat" (
  echo Could not find apksigner at "%BUILD_TOOLS%".
  exit /b 1
)
if not exist "%UNSIGNED%" (
  echo Could not find the unsigned APK at "%UNSIGNED%".
  echo Build it first:  bazel build testdpc -c opt
  exit /b 1
)
if not exist "%~1" (
  echo Could not find the keystore at "%~1".
  exit /b 1
)

echo Using JDK       : %JAVA_HOME%
echo Signing         : %UNSIGNED%
echo Output          : %SIGNED%
echo.

REM The APK is already zipaligned, which apksigner requires to happen first.
call "%BUILD_TOOLS%\apksigner.bat" sign --ks "%~1" --ks-key-alias "%~2" --out "%SIGNED%" "%UNSIGNED%"
if errorlevel 1 (
  echo.
  echo Signing failed.
  exit /b 1
)

echo.
echo Verifying...
call "%BUILD_TOOLS%\apksigner.bat" verify --print-certs "%SIGNED%"
if errorlevel 1 (
  echo.
  echo Verification failed.
  exit /b 1
)

echo.
echo Done. Upload this file to the GitHub release:
echo   %SIGNED%
endlocal
