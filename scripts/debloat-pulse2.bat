@echo off
REM ===========================================================================
REM  Strip the AI+ Pulse 2 4G back down after a factory reset.
REM
REM    debloat-pulse2.bat [device-serial]
REM
REM  Removes the app for user 0 only; the system image is untouched, so another
REM  factory reset brings everything back and any single app can be restored
REM  with:  adb shell cmd package install-existing <package>
REM
REM  ORDER AFTER A FACTORY RESET
REM    1. Walk through setup but do NOT sign in to a Google account. Setting a
REM       device owner is refused while any account exists.
REM    2. adb install -r -t testdpc-9.1.16-parental-controls.apk
REM    3. adb shell dpm set-device-owner com.afwsamples.testdpc/.DeviceAdminReceiver
REM    4. adb shell appops set com.afwsamples.testdpc android:get_usage_stats allow
REM    5. Run this script.
REM    6. Only now sign in to Google, if you want the Play Store. Accounts may
REM       be added freely once the device owner is already set.
REM ===========================================================================

setlocal enabledelayedexpansion

if "%~1"=="" (set "ADB=adb") else (set "ADB=adb -s %~1")

%ADB% get-state >nul 2>&1
if errorlevel 1 (
  echo No device reachable over adb. Plug the phone in and unlock it.
  exit /b 1
)

REM This script uninstalls things. Refuse to run against anything but the phone
REM it was written for: pointing it at another handset would quietly strip apps
REM off that one instead. Pass DRYRUN as the second argument to list without
REM removing, which is the safe way to try it anywhere.
set "DRYRUN="
if /i "%~2"=="DRYRUN" set "DRYRUN=1"

for /f "delims=" %%M in ('%ADB% shell getprop ro.product.model 2^>nul') do set "MODEL=%%M"
set "MODEL=%MODEL: =%"
if /i not "%MODEL%"=="AI+Pulse24G" (
  if not defined DRYRUN (
    echo Refusing to run: this device reports "%MODEL%", not the AI+ Pulse 2 4G.
    echo Re-run with DRYRUN as the second argument to see what it would do:
    echo   %~nx0 %~1 DRYRUN
    exit /b 1
  )
  echo NOTE: device is "%MODEL%", not the AI+ Pulse 2 4G. Dry run only.
)
if defined DRYRUN echo *** DRY RUN - nothing will be uninstalled ***

REM Snapshot the package list once. Asking the phone per package turns 53 checks
REM into 53 round trips, which takes minutes for no reason.
set "PKGLIST=%TEMP%\pulse2_packages.txt"
%ADB% shell pm list packages > "%PKGLIST%" 2>nul

set "REMOVED=0"
set "ABSENT=0"
set "FAILED=0"

echo.
echo === OEM bloat: game space, clean assistant and friends ===
call :kill ai.nxtquantum.os.cleanassistant
call :kill ai.nxtquantum.os.gamezone
call :kill ai.nxtquantum.os.mobilebutler
call :kill ai.nxtquantum.os.phoneclone
call :kill com.app.nxtquantum
call :kill com.spro.globalsearch
call :kill com.spro.sprolivewallpaper
call :kill com.spro.weatherclock
call :kill com.sprd.callrecorder

echo.
echo === Preloaded third-party junk, shipped outside /system ===
call :kill com.pikcn.tools.net
call :kill com.xinctk.catclear
call :kill com.xinctk.grabcat
call :kill com.xinctk.linklink
call :kill com.xinctk.movecar

echo.
echo === Google apps a child does not need ===
REM YouTube is here because you asked for it; drop this line if you would
REM rather keep it and just cap it with screen time instead.
call :kill com.google.android.youtube
call :kill com.google.android.gm
call :kill com.google.android.keep
call :kill com.google.android.videos
call :kill com.google.android.play.games
call :kill com.google.android.apps.bard
call :kill com.google.android.apps.tachyon
call :kill com.google.android.apps.youtube.music
call :kill com.google.android.googlequicksearchbox

echo.
echo === Apps you had already disabled by hand ===
call :kill com.android.virtualization.terminal
call :kill com.google.ambient.streaming
call :kill com.google.android.apps.docs
call :kill com.google.android.apps.nbu.files
call :kill com.google.android.apps.restore
call :kill com.google.android.apps.subscriptions.red
call :kill com.google.android.as
call :kill com.google.android.as.oss
call :kill com.google.android.calculator
call :kill com.google.android.calendar
call :kill com.google.android.deskclock
call :kill com.google.android.marvin.talkback
call :kill com.google.android.projection.gearhead
call :kill com.sprd.powersavemodelauncher

echo.
echo === Icons with no purpose here ===
call :kill com.android.fmradio
call :kill com.android.soundrecorder
call :kill com.android.gallery3d

echo.
echo === Chinese payment and biometric frameworks ===
call :kill com.tencent.soter.soterserver
call :kill org.ifaa.aidl.manager

echo.
echo === Privileged ODM extras that can reach the network ===
call :kill com.exampl.notificationanim
call :kill com.pri.smartfloatball
call :kill com.hm.app.setupwizardext
call :kill ai.nxtquantum.os.privacydashboard
call :kill ai.nxtquantum.os.mpfusys
call :kill com.sprd.linkturbo

echo.
echo === Factory and engineering tools ===
call :kill ai.nxtquantum.os.dramtest
call :kill com.sprd.agingtest
call :kill com.sprd.validationtools
call :kill com.sprd.engineermode
call :kill com.sprd.logmanager

echo.
echo ---------------------------------------------------------------
echo  removed: !REMOVED!   already absent: !ABSENT!   failed: !FAILED!
echo ---------------------------------------------------------------
echo.
echo Deliberately left alone, because removing them breaks the phone:
echo   com.spreadtrum.ims                 VoLTE calling
echo   com.spreadtrum.sgps                GPS
echo   com.spreadtrum.proxy.nfwlocation   network location
echo   com.unisoc.*                       chipset and display overlays
echo   com.sprd.providers.photos          media provider
echo   com.sprd.cameracalibration, com.sprd.camta   camera
echo   com.sprd.omacp                     carrier auto-configuration
echo.
echo Kept on purpose: Play Store, Phone, Messages, Contacts, Camera, Photos,
echo Chrome, Settings, Find My Device, Personal Safety, Maps, the keyboard.
echo.
echo These two were disabled rather than removed, since disabling is safer for
echo system components. Re-disable them if you want that state back:
echo   %ADB% shell pm disable-user --user 0 com.android.nfc
echo   %ADB% shell pm disable-user --user 0 com.google.android.gms.supervision
echo.
echo Restore any single app with:
echo   %ADB% shell cmd package install-existing ^<package^>
del "%PKGLIST%" >nul 2>&1
exit /b 0

:kill
findstr /r /c:"^package:%~1$" "%PKGLIST%" >nul 2>&1
if errorlevel 1 (
  echo   absent    %~1
  set /a ABSENT+=1
  exit /b 0
)
if defined DRYRUN (
  echo   would remove  %~1
  set /a REMOVED+=1
  exit /b 0
)
for /f "delims=" %%R in ('%ADB% shell pm uninstall --user 0 %~1 2^>^&1') do set "OUT=%%R"
echo !OUT! | findstr /c:"Success" >nul
if errorlevel 1 (
  echo   FAILED    %~1  ^(!OUT!^)
  set /a FAILED+=1
) else (
  echo   removed   %~1
  set /a REMOVED+=1
)
exit /b 0
