@echo off
setlocal

rem ============================================================
rem  NEJE Studio build script
rem    build.bat          build debug APK
rem    build.bat test     run unit tests + build debug APK
rem    build.bat clean    clean + build debug APK
rem ============================================================

set "ROOT=%~dp0"
set "PROJECT=%ROOT%NejeStudio"

rem ---- Find JDK 17 (keep existing JAVA_HOME if set)
if not defined JAVA_HOME for /d %%D in ("C:\Program Files\Microsoft\jdk-17*") do set "JAVA_HOME=%%~D"
if not defined JAVA_HOME (
    echo [ERROR] JDK 17 not found. Install: winget install Microsoft.OpenJDK.17
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ---- Check Android SDK
if not exist "%LOCALAPPDATA%\Android\Sdk\platforms" (
    echo [ERROR] Android SDK not found: %LOCALAPPDATA%\Android\Sdk
    exit /b 1
)
if not exist "%PROJECT%\local.properties" (
    > "%PROJECT%\local.properties" echo sdk.dir=%LOCALAPPDATA:\=/%/Android/Sdk
)

set "TASKS=assembleDebug"
if /i "%~1"=="test"  set "TASKS=testDebugUnitTest assembleDebug"
if /i "%~1"=="clean" set "TASKS=clean assembleDebug"

echo [BUILD] JAVA_HOME=%JAVA_HOME%
echo [BUILD] gradlew %TASKS%
pushd "%PROJECT%"
call "%PROJECT%\gradlew.bat" %TASKS% --console=plain
set "RC=%ERRORLEVEL%"
popd

if not "%RC%"=="0" (
    echo.
    echo [FAILED] Build failed. See the error messages above.
    exit /b %RC%
)

rem ---- Copy APK with version name
set "VER="
for /f "tokens=2 delims==" %%V in ('findstr /c:"versionName" "%PROJECT%\app\build.gradle.kts"') do set "VER=%%~V"
set "VER=%VER: =%"
set "VER=%VER:"=%"
if "%VER%"=="" set "VER=dev"

set "OUT=%ROOT%NejeStudio-%VER%-debug.apk"
copy /y "%PROJECT%\app\build\outputs\apk\debug\app-debug.apk" "%OUT%" > nul
echo.
echo [DONE] %OUT%
endlocal
