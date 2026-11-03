@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem =====================================================================
rem  build.bat - build SpireLink.jar (and optionally install it)
rem
rem  Usage:
rem    scripts\build.bat                 build + install to <game>\mods\SpireLink.jar
rem    scripts\build.bat --no-install    build only
rem    scripts\build.bat --check         check toolchain + deps, build nothing
rem
rem  Game dir lookup: %SPIRELINK_GAME% -> the parent of this repo
rem  Toolchain:       <repo>\tools\jdk8 + <repo>\tools\maven -> %JAVA_HOME% + mvn on PATH
rem
rem  Exit codes: 0 ok / 10 repo root / 11 JDK8 / 12 Maven / 13 pom.xml /
rem              14 project dir / 15 lib jars / 20 maven failed /
rem              21 no jar produced / 22 cannot create mods / 23 copy failed
rem =====================================================================

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
if not exist "%REPO_ROOT%\spirelink\pom.xml" (
  echo [ERROR] Cannot find "%REPO_ROOT%\spirelink\pom.xml"
  echo         Run this script from the repository root.
  exit /b 13
)

rem ---------- arguments ----------
set "NOINSTALL="
set "CHECKONLY="
:argloop
if "%~1"=="" goto args_done
if /i "%~1"=="--no-install" (
  set "NOINSTALL=1"
  shift
  goto argloop
)
if /i "%~1"=="--check" (
  set "CHECKONLY=1"
  shift
  goto argloop
)
if /i "%~1"=="--help" goto usage
if /i "%~1"=="-h" goto usage
echo [ERROR] Unknown argument: %~1
goto usage
:args_done

rem ---------- game dir ----------
if defined SPIRELINK_GAME (
  for %%I in ("%SPIRELINK_GAME%") do set "GAME_DIR=%%~fI"
)
if not defined GAME_DIR (
  for %%I in ("%REPO_ROOT%\..") do set "GAME_DIR=%%~fI"
)

rem ---------- JDK ----------
if exist "%REPO_ROOT%\tools\jdk8\bin\javac.exe" (
  set "JAVA_HOME=%REPO_ROOT%\tools\jdk8"
  set "PATH=%REPO_ROOT%\tools\jdk8\bin;%PATH%"
  echo [toolchain] bundled JDK8: %JAVA_HOME%
) else (
  if defined JAVA_HOME (
    echo [toolchain] JAVA_HOME: %JAVA_HOME%
  ) else (
    echo [ERROR] No JDK 8: "%REPO_ROOT%\tools\jdk8" is missing and JAVA_HOME is not set.
    echo         Install Temurin 8 and set JAVA_HOME, or unpack a JDK 8 into tools\jdk8.
    exit /b 11
  )
)

rem ---------- Maven ----------
set "MVN_CMD="
if exist "%REPO_ROOT%\tools\maven\bin\mvn.cmd" (
  set "MVN_CMD=%REPO_ROOT%\tools\maven\bin\mvn.cmd"
  echo [toolchain] bundled Maven: %MVN_CMD%
) else (
  for /f "delims=" %%P in ('where mvn 2^>nul') do if not defined MVN_CMD set "MVN_CMD=%%P"
  if defined MVN_CMD (
    echo [toolchain] mvn on PATH: !MVN_CMD!
  ) else (
    echo [ERROR] No Maven: "%REPO_ROOT%\tools\maven" is missing and mvn is not on PATH.
    echo         Install Apache Maven 3.9.x, or unpack it into tools\maven.
    exit /b 12
  )
)

rem ---------- lib jars (not shipped; see lib/README.md) ----------
set "MISSING_LIB="
for %%J in (desktop-1.0.jar ModTheSpire.jar BaseMod.jar) do (
  if not exist "%REPO_ROOT%\lib\%%J" (
    echo [ERROR] Missing dependency: "%REPO_ROOT%\lib\%%J"
    set "MISSING_LIB=1"
  )
)
if defined MISSING_LIB (
  echo         Copy the three jars from your own game install and the Steam Workshop.
  echo         See lib/README.md - they are NOT redistributed with this repository.
  exit /b 15
)

echo [info] repo = %REPO_ROOT%
echo [info] game = %GAME_DIR%

if defined CHECKONLY (
  "%JAVA_HOME%\bin\javac.exe" -version
  call "%MVN_CMD%" -v
  echo [check] toolchain and dependencies look fine
  exit /b 0
)

rem ---------- build ----------
pushd "%REPO_ROOT%\spirelink"
if errorlevel 1 (
  echo [ERROR] Cannot enter "%REPO_ROOT%\spirelink"
  exit /b 14
)
if not defined MAVEN_OPTS set "MAVEN_OPTS=-Dfile.encoding=UTF-8 -Xmx1g"
echo [build] mvn -q -DskipTests package
call "%MVN_CMD%" -q -DskipTests package
set "MVN_RC=%ERRORLEVEL%"
if not "%MVN_RC%"=="0" (
  popd
  echo [ERROR] Maven failed with exit code %MVN_RC%
  echo         For the full log: cd /d "%REPO_ROOT%\spirelink" ^&^& "%MVN_CMD%" -DskipTests package
  exit /b 20
)
set "TARGET_JAR=%REPO_ROOT%\spirelink\target\SpireLink.jar"
if not exist "%TARGET_JAR%" (
  popd
  echo [ERROR] Maven succeeded but "%TARGET_JAR%" was not produced.
  exit /b 21
)
popd
echo [OK] built "%TARGET_JAR%"

if defined NOINSTALL (
  echo [skip] --no-install: nothing was copied into the game directory
  exit /b 0
)

if not exist "%GAME_DIR%\SlayTheSpire.exe" (
  echo [WARN] game directory not found: "%GAME_DIR%"
  echo        Built only. Set SPIRELINK_GAME to your game folder to install,
  echo        or keep this repository inside the game folder.
  exit /b 0
)
if not exist "%GAME_DIR%\mods" (
  md "%GAME_DIR%\mods" >nul 2>nul
  if not exist "%GAME_DIR%\mods" (
    echo [ERROR] Cannot create "%GAME_DIR%\mods"
    exit /b 22
  )
)
copy /y "%TARGET_JAR%" "%GAME_DIR%\mods\SpireLink.jar" >nul
if errorlevel 1 (
  echo [ERROR] Copy failed: "%TARGET_JAR%" -^> "%GAME_DIR%\mods\SpireLink.jar"
  exit /b 23
)
echo [OK] installed "%GAME_DIR%\mods\SpireLink.jar"
echo      Enable SpireLink in the ModTheSpire mod list, then run scripts\run-game.bat
endlocal
exit /b 0

:usage
echo.
echo Usage:
echo   scripts\build.bat                 build + install into mods\SpireLink.jar
echo   scripts\build.bat --no-install    build only
echo   scripts\build.bat --check         check toolchain and dependencies
echo.
echo Environment:
echo   SPIRELINK_GAME   game directory (defaults to the parent of this repo)
echo.
exit /b 2
