@echo off
rem ---------------------------------------------------------------------------
rem  Builds the Depth Cam APK and copies it to out\.
rem
rem  Usage:  build.cmd [debug^|release] [--install] [--clean]
rem
rem    debug     (default) APK signed with the local debug key - installable.
rem    release   Optimised but UNSIGNED - sign it before installing (README).
rem    --install adb install -r the freshly built debug APK.
rem    --clean   Wipe previous build output first.
rem
rem  The toolchain comes from tools\toolchain.cmd when download-tools.cmd has
rem  been run; otherwise JAVA_HOME / ANDROID_SDK_ROOT are used.
rem ---------------------------------------------------------------------------
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"
set "ROOT=%CD%"

set "BUILD_TYPE=debug"
set "INSTALL=no"
set "CLEAN=no"

:args
if "%~1"=="" goto :args_done
if /i "%~1"=="debug"     (set "BUILD_TYPE=debug"   & shift & goto :args)
if /i "%~1"=="release"   (set "BUILD_TYPE=release" & shift & goto :args)
if /i "%~1"=="--install" (set "INSTALL=yes"        & shift & goto :args)
if /i "%~1"=="--clean"   (set "CLEAN=yes"          & shift & goto :args)
if /i "%~1"=="-h"        goto :usage
if /i "%~1"=="--help"    goto :usage
echo Unknown argument: %~1>&2
exit /b 2
:args_done

if /i "%BUILD_TYPE%"=="debug" (
    set "TASK=assembleDebug"
    set "APK=app\build\outputs\apk\debug\app-debug.apk"
    set "OUT=out\depth-cam-debug.apk"
) else (
    set "TASK=assembleRelease"
    set "APK=app\build\outputs\apk\release\app-release-unsigned.apk"
    set "OUT=out\depth-cam-release-unsigned.apk"
)

rem ------------------------------------------------------------- toolchain

rem What download-tools recorded wins, as long as it is still there; otherwise
rem fall back to the environment and then to the usual places.
set "ENV_JAVA_HOME=%JAVA_HOME%"
if exist "%ROOT%\tools\toolchain.cmd" call "%ROOT%\tools\toolchain.cmd"

call :jdk_ok "%JAVA_HOME%" || set "JAVA_HOME=%ENV_JAVA_HOME%"
call :jdk_ok "%JAVA_HOME%"
if errorlevel 1 (
    set "JAVA_HOME="
    call :jdk_ok "%ROOT%\tools\jdk"  && set "JAVA_HOME=%ROOT%\tools\jdk"
)
if not defined JAVA_HOME (
    echo No JDK 17-23 found. Run download-tools.cmd first.>&2
    exit /b 1
)

if not exist "%ROOT%\local.properties" if not defined ANDROID_SDK_ROOT if not defined ANDROID_HOME (
    echo No Android SDK configured. Run download-tools.cmd first.>&2
    exit /b 1
)

rem ----------------------------------------------------------------- build

echo Building %BUILD_TYPE% with %JAVA_HOME%

if /i "%CLEAN%"=="yes" (
    call "%ROOT%\gradlew.bat" -Dorg.gradle.java.home="%JAVA_HOME%" clean || goto :fail
)

call "%ROOT%\gradlew.bat" -Dorg.gradle.java.home="%JAVA_HOME%" %TASK% || goto :fail

if not exist "%ROOT%\%APK%" (
    echo Expected APK not found at %APK%>&2
    goto :fail
)

if not exist "%ROOT%\out" mkdir "%ROOT%\out"
copy /y "%ROOT%\%APK%" "%ROOT%\%OUT%" >nul || goto :fail

for %%f in ("%ROOT%\%OUT%") do set /a SIZE_KB=%%~zf/1024
echo.
echo APK:  %OUT%  (%SIZE_KB% KB)
if /i "%BUILD_TYPE%"=="release" echo Note: release APKs are unsigned - see README.md before installing.

rem --------------------------------------------------------------- install

if /i "%INSTALL%"=="yes" (
    if /i "%BUILD_TYPE%"=="release" (
        echo Refusing to install an unsigned release APK.>&2
        goto :fail
    )
    set "ADB=adb"
    if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
    echo.
    echo Installing...
    "!ADB!" install -r "%ROOT%\%OUT%" || goto :fail
)

endlocal
exit /b 0

rem ===========================================================================

:jdk_ok
rem %1 = candidate JDK directory. Returns 0 when it is a usable Java 17-23.
set "JDK_VER_RAW="
set "JDK_MAJ="
if "%~1"=="" exit /b 1
if not exist "%~1\bin\java.exe" exit /b 1
"%~1\bin\java.exe" -version 2>"%TEMP%\dc-java-version.txt" >nul
for /f "tokens=3" %%v in ('findstr /i "version" "%TEMP%\dc-java-version.txt" 2^>nul') do (
    if not defined JDK_VER_RAW set "JDK_VER_RAW=%%~v"
)
del "%TEMP%\dc-java-version.txt" 2>nul
if not defined JDK_VER_RAW exit /b 1
for /f "tokens=1 delims=." %%a in ("%JDK_VER_RAW%") do set "JDK_MAJ=%%a"
echo %JDK_MAJ%| findstr /r "^[0-9][0-9]*$" >nul || exit /b 1
if %JDK_MAJ% LSS 17 exit /b 1
if %JDK_MAJ% GTR 23 exit /b 1
exit /b 0

:usage
echo Usage:  build.cmd [debug^|release] [--install] [--clean]
endlocal
exit /b 0

:fail
echo.
echo Build failed.>&2
endlocal
exit /b 1
