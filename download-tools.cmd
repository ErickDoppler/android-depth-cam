@echo off
rem ---------------------------------------------------------------------------
rem  Downloads everything needed to build android-depth-cam on a Windows machine
rem  with nothing installed:
rem
rem    * a JDK 17 (Eclipse Temurin)          -> tools\jdk
rem    * the Android SDK command line tools  -> tools\cmdline-tools\latest
rem    * Android SDK platform 35, build-tools 35.0.0 and platform-tools
rem    * the Gradle distribution the wrapper asks for
rem
rem  Anything already present is reused: a suitable JAVA_HOME and an existing
rem  Android SDK (ANDROID_SDK_ROOT / ANDROID_HOME / sdk.dir in local.properties
rem  / %LOCALAPPDATA%\Android\Sdk) stay where they are and only the missing SDK
rem  packages are installed. Re-running the script is cheap.
rem
rem  It writes local.properties (sdk.dir) and tools\toolchain.cmd, which
rem  build.cmd reads.
rem
rem  Usage:  download-tools.cmd [--jdk <dir>] [--sdk <dir>]
rem
rem    --jdk <dir>  Use this JDK (17-23) instead of searching or downloading.
rem    --sdk <dir>  Use this Android SDK; missing packages are installed in it.
rem
rem  JAVA_HOME and ANDROID_SDK_ROOT / ANDROID_HOME are picked up automatically,
rem  so the flags are only needed for tools in unusual places.
rem ---------------------------------------------------------------------------
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"
set "ROOT=%CD%"
set "TOOLS=%ROOT%\tools"

set "JDK_OVERRIDE="
set "SDK_OVERRIDE="
:args
if "%~1"=="" goto :args_done
if /i "%~1"=="--jdk" (set "JDK_OVERRIDE=%~2" & shift & shift & goto :args)
if /i "%~1"=="--sdk" (set "SDK_OVERRIDE=%~2" & shift & shift & goto :args)
if /i "%~1"=="-h"     goto :usage
if /i "%~1"=="--help" goto :usage
echo Unknown argument: %~1>&2
exit /b 2
:args_done

rem Must match app\build.gradle.kts (compileSdk) and the gradle wrapper.
set "SDK_PLATFORM=35"
set "BUILD_TOOLS=35.0.0"
rem JDK 17 is the version AGP 8.7 targets; Gradle 8.11 accepts 17 through 23.
set "JDK_MAJOR=17"
rem Android SDK command line tools 16.0 (rev 13114758).
set "CMDLINE_TOOLS_BUILD=13114758"

if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" (set "ARCH=aarch64") else (set "ARCH=x64")

if not exist "%TOOLS%" mkdir "%TOOLS%"

rem ------------------------------------------------------------------- JDK

echo [1/3] JDK %JDK_MAJOR%

set "JDK="
if defined JDK_OVERRIDE (
    call :jdk_ok "%JDK_OVERRIDE%" || (
        echo --jdk %JDK_OVERRIDE% is not a JDK 17-23.>&2
        goto :fail
    )
    set "JDK=%JDK_OVERRIDE%"
) else (
    call :jdk_ok "%TOOLS%\jdk"    && set "JDK=%TOOLS%\jdk"
    if not defined JDK call :jdk_ok "%JAVA_HOME%" && set "JDK=%JAVA_HOME%"
)

if defined JDK (
    call :jdk_ok "%JDK%"
    echo   using %JDK% ^(Java !JDK_MAJ!^)
) else (
    echo   no JDK 17-23 found, downloading Temurin %JDK_MAJOR%
    call :install_jdk || goto :fail
    set "JDK=%TOOLS%\jdk"
    echo   installed !JDK!
)
set "JAVA_HOME=%JDK%"

rem ----------------------------------------------------------- Android SDK

echo [2/3] Android SDK

set "SDK_ROOT="
if defined SDK_OVERRIDE (
    if not exist "%SDK_OVERRIDE%" mkdir "%SDK_OVERRIDE%"
    set "SDK_ROOT=%SDK_OVERRIDE%"
)
call :try_sdk "%TOOLS%\android-sdk"
call :try_sdk "%ANDROID_SDK_ROOT%"
call :try_sdk "%ANDROID_HOME%"
if exist local.properties (
    for /f "usebackq tokens=1,* delims==" %%a in ("local.properties") do (
        if /i "%%a"=="sdk.dir" (
            set "LP=%%b"
            set "LP=!LP:\\=\!"
            set "LP=!LP:\:=:!"
            call :try_sdk "!LP!"
        )
    )
)
call :try_sdk "%LOCALAPPDATA%\Android\Sdk"

if defined SDK_ROOT (
    echo   using %SDK_ROOT%
) else (
    set "SDK_ROOT=%TOOLS%\android-sdk"
    echo   no SDK found, creating !SDK_ROOT!
    mkdir "!SDK_ROOT!" 2>nul
)

set "MISSING="
if not exist "%SDK_ROOT%\platforms\android-%SDK_PLATFORM%\android.jar" set "MISSING=!MISSING! platform"
if not exist "%SDK_ROOT%\build-tools\%BUILD_TOOLS%" set "MISSING=!MISSING! build-tools"
if not exist "%SDK_ROOT%\platform-tools\adb.exe" set "MISSING=!MISSING! platform-tools"

if defined MISSING (
    echo   missing:!MISSING!
    rem The command line tools live in this project, never inside a shared SDK:
    rem sdkmanager installs into whatever --sdk_root points at.
    set "CMDLINE=%TOOLS%\cmdline-tools\latest"
    if not exist "!CMDLINE!\bin" call :install_cmdline_tools || goto :fail
    echo   accepting SDK licences
    (for /l %%i in (1,1,50) do @echo y) | "!CMDLINE!\bin\sdkmanager.bat" --sdk_root="%SDK_ROOT%" --licenses >nul
    echo   installing packages
    call "!CMDLINE!\bin\sdkmanager.bat" --sdk_root="%SDK_ROOT%" "platform-tools" "platforms;android-%SDK_PLATFORM%" "build-tools;%BUILD_TOOLS%" || goto :fail
    if not exist "%SDK_ROOT%\platforms\android-%SDK_PLATFORM%\android.jar" (
        echo SDK platform %SDK_PLATFORM% still missing after install.>&2
        goto :fail
    )
) else (
    echo   platform %SDK_PLATFORM%, build-tools %BUILD_TOOLS%, platform-tools present
)

rem ------------------------------------------ project + toolchain records

rem Forward slashes need no escaping in a Java properties file.
set "SDK_PROP=%SDK_ROOT:\=/%"
> local.properties echo sdk.dir=%SDK_PROP%

> "%TOOLS%\toolchain.cmd" echo @rem Written by download-tools.cmd - read by build.cmd. Safe to delete.
>>"%TOOLS%\toolchain.cmd" echo set "JAVA_HOME=%JDK%"
>>"%TOOLS%\toolchain.cmd" echo set "ANDROID_SDK_ROOT=%SDK_ROOT%"

set "JDK_SLASH=%JDK:\=/%"
> "%TOOLS%\toolchain.env" echo # Written by download-tools.cmd - read by build.sh. Safe to delete.
>>"%TOOLS%\toolchain.env" echo JAVA_HOME='%JDK_SLASH%'
>>"%TOOLS%\toolchain.env" echo ANDROID_SDK_ROOT='%SDK_PROP%'

rem ---------------------------------------------------------------- Gradle

rem The wrapper downloads the Gradle distribution named in
rem gradle\wrapper\gradle-wrapper.properties; doing it here keeps the first
rem build from stalling on a ~130 MB download.
echo [3/3] Gradle distribution
call "%ROOT%\gradlew.bat" -Dorg.gradle.java.home="%JDK%" --version >nul || goto :fail
echo   ready

echo.
echo Toolchain ready:
echo   JDK          %JDK%
echo   Android SDK  %SDK_ROOT%
echo.
echo Next:  build.cmd
endlocal
exit /b 0

rem ===========================================================================

:jdk_ok
rem %1 = candidate JDK directory. Returns 0 and sets JDK_MAJ when it is a
rem usable Java 17-23 runtime.
set "JDK_MAJ="
set "JDK_VER_RAW="
if "%~1"=="" exit /b 1
if not exist "%~1\bin\java.exe" exit /b 1
"%~1\bin\java.exe" -version 2>"%TEMP%\dc-java-version.txt" >nul
for /f "tokens=3" %%v in ('findstr /i "version" "%TEMP%\dc-java-version.txt" 2^>nul') do (
    if not defined JDK_VER_RAW set "JDK_VER_RAW=%%~v"
)
del "%TEMP%\dc-java-version.txt" 2>nul
if not defined JDK_VER_RAW exit /b 1
for /f "tokens=1 delims=." %%a in ("%JDK_VER_RAW%") do set "JDK_MAJ=%%a"
set "JDK_VER_RAW="
echo %JDK_MAJ%| findstr /r "^[0-9][0-9]*$" >nul || exit /b 1
if %JDK_MAJ% LSS 17 exit /b 1
if %JDK_MAJ% GTR 23 exit /b 1
exit /b 0

:try_sdk
rem %1 = candidate SDK directory; keeps the first one that exists.
if defined SDK_ROOT exit /b 0
if "%~1"=="" exit /b 0
if not exist "%~1" exit /b 0
set "SDK_ROOT=%~f1"
exit /b 0

:install_jdk
set "JDK_URL=https://api.adoptium.net/v3/binary/latest/%JDK_MAJOR%/ga/windows/%ARCH%/jdk/hotspot/normal/eclipse"
call :fetch "%JDK_URL%" "%TOOLS%\jdk.zip" || exit /b 1
if exist "%TOOLS%\.jdk-unpack" rmdir /s /q "%TOOLS%\.jdk-unpack"
if exist "%TOOLS%\jdk" rmdir /s /q "%TOOLS%\jdk"
call :expand "%TOOLS%\jdk.zip" "%TOOLS%\.jdk-unpack" || exit /b 1
del "%TOOLS%\jdk.zip" 2>nul
rem The archive holds a single jdk-<version> directory.
for /d %%d in ("%TOOLS%\.jdk-unpack\*") do move "%%~fd" "%TOOLS%\jdk" >nul
rmdir /s /q "%TOOLS%\.jdk-unpack" 2>nul
call :jdk_ok "%TOOLS%\jdk" || (echo Downloaded JDK does not run.>&2 & exit /b 1)
exit /b 0

:install_cmdline_tools
set "CT_URL=https://dl.google.com/android/repository/commandlinetools-win-%CMDLINE_TOOLS_BUILD%_latest.zip"
call :fetch "%CT_URL%" "%TOOLS%\cmdline-tools.zip" || exit /b 1
if exist "%TOOLS%\.ct-unpack" rmdir /s /q "%TOOLS%\.ct-unpack"
call :expand "%TOOLS%\cmdline-tools.zip" "%TOOLS%\.ct-unpack" || exit /b 1
del "%TOOLS%\cmdline-tools.zip" 2>nul
if not exist "%TOOLS%\cmdline-tools" mkdir "%TOOLS%\cmdline-tools"
if exist "%TOOLS%\cmdline-tools\latest" rmdir /s /q "%TOOLS%\cmdline-tools\latest"
move "%TOOLS%\.ct-unpack\cmdline-tools" "%TOOLS%\cmdline-tools\latest" >nul || exit /b 1
rmdir /s /q "%TOOLS%\.ct-unpack" 2>nul
exit /b 0

:fetch
echo   fetching %~nx2
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; try { Invoke-WebRequest -Uri '%~1' -OutFile '%~2' -UseBasicParsing } catch { Write-Error $_; exit 1 }" || exit /b 1
exit /b 0

:expand
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; try { Expand-Archive -LiteralPath '%~1' -DestinationPath '%~2' -Force } catch { Write-Error $_; exit 1 }" || exit /b 1
exit /b 0

:usage
echo Usage:  download-tools.cmd [--jdk ^<dir^>] [--sdk ^<dir^>]
echo.
echo   --jdk ^<dir^>  Use this JDK ^(17-23^) instead of searching or downloading.
echo   --sdk ^<dir^>  Use this Android SDK; missing packages are installed in it.
endlocal
exit /b 0

:fail
echo.
echo Tool download failed.>&2
endlocal
exit /b 1
