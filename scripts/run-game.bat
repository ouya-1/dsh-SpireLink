@echo off
setlocal EnableExtensions
rem ===========================================================================
rem  Launch Slay the Spire *through ModTheSpire* (modded).
rem
rem  Why this script does NOT just start SlayTheSpire.exe:
rem    <game>\config.json declares
rem        mainClass = com.megacrit.cardcrawl.desktop.DesktopLauncher
rem    i.e. the Steam exe boots the VANILLA launcher. ModTheSpire is never
rem    loaded, no mods run, and SpireLink's MCP server never starts.
rem    MTS has to be the entry point instead:
rem
rem      <game>\jre\bin\java.exe -jar "<...>\ModTheSpire.jar" \
rem          --mods basemod,spirelink --skip-intro
rem
rem  Usage:
rem    scripts\run-game.bat                  launch modded game (detached window)
rem    scripts\run-game.bat --attach         run in this console, blocking: you see
rem                                          MTS / mod stdout live, Ctrl+C kills it
rem    scripts\run-game.bat --min            detached, but start the console MINIMIZED
rem    scripts\run-game.bat --dry-run        print the final command line, do not start
rem    scripts\run-game.bat --strict         fail (exit 14) if mods\SpireLink.jar is
rem                                          missing - use this in scripted smoke tests
rem    scripts\run-game.bat --vanilla        [opt-in] start vanilla SlayTheSpire.exe
rem                                          (no mods) - explicit, never a fallback
rem    scripts\run-game.bat --seed 12345 -v  extra args are passed through to MTS
rem
rem  Environment overrides:
rem    SPIRELINK_MODS   MTS --mods value, default "basemod,spirelink"
rem    SPIRELINK_MTS    explicit path to ModTheSpire.jar (skips probing)
rem
rem  MTS jar probing order:
rem    1. %SPIRELINK_MTS%
rem    2. <steamapps>\workshop\content\646570\1605060445\ModTheSpire.jar  (workshop)
rem    3. <game>\ModTheSpire.jar                                       (manual install)
rem
rem  Exit codes:
rem    0   launched (or dry-run finished)
rem    10  cannot resolve repo layout (script moved out of <repo>\scripts)
rem    11  game directory / SlayTheSpire.exe not found
rem    12  ModTheSpire.jar not found - subscribe on the Workshop, no silent fallback
rem    13  no usable java.exe (neither <game>\jre nor tools\jdk8)
rem    14  mods\SpireLink.jar missing while --strict was given
rem    15  failed to start the process
rem ===========================================================================

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
if defined SPIRELINK_GAME for %%I in ("%SPIRELINK_GAME%") do set "GAME_DIR=%%~fI"
if not defined GAME_DIR for %%I in ("%~dp0..\..") do set "GAME_DIR=%%~fI"
for %%I in ("%GAME_DIR%\..\..") do set "STEAMAPPS=%%~fI"
if not exist "%REPO_ROOT%\scripts" (
  echo [ERROR] Cannot resolve repo root from "%~f0"
  exit /b 10
)

rem ---------- argument parsing (flags are consumed, everything else passes through)
set "DRY_RUN="
set "STRICT="
set "ATTACH="
set "VANILLA="
set "MIN="
set "TEST="
set "PLACE_EXTRA="
set "PASSTHRU="
:argloop
if "%~1"=="" goto argsdone
set "ARG=%~1"
if /i "%ARG%"=="--dry-run" ( set "DRY_RUN=1" & shift & goto argloop )
if /i "%ARG%"=="--strict"  ( set "STRICT=1"  & shift & goto argloop )
if /i "%ARG%"=="--attach"  ( set "ATTACH=1"  & shift & goto argloop )
if /i "%ARG%"=="--vanilla" ( set "VANILLA=1" & shift & goto argloop )
if /i "%ARG%"=="--min"     ( set "MIN=1"     & shift & goto argloop )
rem --test: the agent is testing, so the window goes to a SECONDARY monitor when there is one;
rem the human's remembered spot (window_position.json) is never touched.
if /i "%ARG%"=="--test"    ( set "TEST=1"    & shift & goto argloop )
if /i "%ARG%"=="--help" goto usage
if /i "%ARG%"=="-h" goto usage
set "PASSTHRU=%PASSTHRU% %1"
shift
goto argloop
:argsdone

rem ---------- the game itself
if not exist "%GAME_DIR%\SlayTheSpire.exe" (
  echo [ERROR] Game directory not found: "%GAME_DIR%"
  echo         expected "%GAME_DIR%\SlayTheSpire.exe"
  echo         run-game.bat must stay in ^<repo^>\scripts\ so that ..\.. is the game dir
  exit /b 11
)

if defined VANILLA goto vanilla

rem ---------- 1) locate ModTheSpire.jar
set "MTS_JAR="
if defined SPIRELINK_MTS if exist "%SPIRELINK_MTS%" set "MTS_JAR=%SPIRELINK_MTS%"
if not defined MTS_JAR if exist "%STEAMAPPS%\workshop\content\646570\1605060445\ModTheSpire.jar" set "MTS_JAR=%STEAMAPPS%\workshop\content\646570\1605060445\ModTheSpire.jar"
if not defined MTS_JAR if exist "%GAME_DIR%\ModTheSpire.jar" set "MTS_JAR=%GAME_DIR%\ModTheSpire.jar"
if defined MTS_JAR goto have_mts
echo [ERROR] ModTheSpire.jar not found - cannot launch a modded game.
echo         probed:
echo           "%STEAMAPPS%\workshop\content\646570\1605060445\ModTheSpire.jar"
echo           "%GAME_DIR%\ModTheSpire.jar"
echo         Fix: subscribe to ModTheSpire on the Steam Workshop ^(item 1605060445^):
echo              https://steamcommunity.com/sharedfiles/filedetails/?id=1605060445
echo         Or set SPIRELINK_MTS to an existing ModTheSpire.jar.
echo         Refusing to silently fall back to SlayTheSpire.exe: that boots the
echo         vanilla launcher and loads no mods at all.
exit /b 12
:have_mts

rem ---------- 2) locate java.exe (game jre first, it is what the game ships with)
set "JAVA_EXE="
if exist "%GAME_DIR%\jre\bin\java.exe" set "JAVA_EXE=%GAME_DIR%\jre\bin\java.exe"
if not defined JAVA_EXE if exist "%REPO_ROOT%\tools\jdk8\bin\java.exe" set "JAVA_EXE=%REPO_ROOT%\tools\jdk8\bin\java.exe"
if defined JAVA_EXE goto have_java
echo [ERROR] No usable java.exe found.
echo         probed "%GAME_DIR%\jre\bin\java.exe" and "%REPO_ROOT%\tools\jdk8\bin\java.exe"
exit /b 13
:have_java

rem ---------- 3) mods argument + SpireLink.jar presence
set "MODS_ARG=%SPIRELINK_MODS%"
if not defined MODS_ARG set "MODS_ARG=basemod,spirelink"
if not exist "%GAME_DIR%\mods" md "%GAME_DIR%\mods" >nul 2>nul
if exist "%GAME_DIR%\mods\SpireLink.jar" goto mods_ok
if defined STRICT (
  echo [ERROR] --strict: "%GAME_DIR%\mods\SpireLink.jar" is missing.
  echo         Run scripts\build.bat first.
  exit /b 14
)
echo [WARN] "%GAME_DIR%\mods\SpireLink.jar" not found - SpireLink will NOT load.
echo        Run scripts\build.bat first, or pass --strict to make this fatal.
:mods_ok

rem ---------- 4) build the command line
set "CMD_LINE="%JAVA_EXE%" -jar "%MTS_JAR%" --mods %MODS_ARG% --skip-intro%PASSTHRU%"

echo [run] repo = %REPO_ROOT%
echo [run] game = %GAME_DIR%
echo [run] mts  = %MTS_JAR%
echo [run] java = %JAVA_EXE%
echo [run] mods = %MODS_ARG%

if not defined DRY_RUN goto launch
echo [dry-run] cd /d "%GAME_DIR%"
echo [dry-run] %CMD_LINE%
exit /b 0

:launch
pushd "%GAME_DIR%"
if errorlevel 1 (
  echo [ERROR] Cannot enter "%GAME_DIR%"
  exit /b 10
)
if defined ATTACH goto attach
if defined MIN ( start "" /MIN %CMD_LINE% ) else ( start "" %CMD_LINE% )
if errorlevel 1 (
  popd
  echo [ERROR] Failed to start the game process.
  echo         Re-run with --attach to see the error in this console.
  exit /b 15
)
popd
rem Where does the window belong? On the secondary screen when it is on, otherwise it is pulled
rem back onto the primary one - a detached background job so startup is not delayed.
if defined TEST set "PLACE_EXTRA=-Secondary"
if exist "%REPO_ROOT%\scripts\place_window.ps1" (
  start "" /b "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%REPO_ROOT%\scripts\place_window.ps1" -WaitSeconds 180 %PLACE_EXTRA% >>"%REPO_ROOT%\tools\tmp\place_window.log" 2>&1
)
echo [OK] Launched ModTheSpire (detached window, logs go there).
echo      Window title should read "Modded Slay the Spire".
echo      Enable/disable mods in the MTS mod list; SpireLink writes its log under
echo      "%GAME_DIR%\mods\spirelink-data\spirelink.log"
endlocal
exit /b 0

:attach
echo [run] attached: the console stays busy until the game exits (Ctrl+C to kill)
%CMD_LINE%
set "GAME_RC=%ERRORLEVEL%"
popd
echo [run] game exited with code %GAME_RC%
endlocal
exit /b 0

:vanilla
rem Opt-in vanilla launch: no ModTheSpire, no mods. This is *not* a fallback.
echo [run] vanilla mode (--vanilla): starting SlayTheSpire.exe, NO mods will load
if not defined DRY_RUN goto vanilla_launch
echo [dry-run] cd /d "%GAME_DIR%"
echo [dry-run] start "" "%GAME_DIR%\SlayTheSpire.exe"%PASSTHRU%
exit /b 0
:vanilla_launch
pushd "%GAME_DIR%"
start "" "%GAME_DIR%\SlayTheSpire.exe" %PASSTHRU%
if errorlevel 1 (
  popd
  echo [ERROR] Failed to start "%GAME_DIR%\SlayTheSpire.exe"
  exit /b 15
)
popd
echo [OK] Launched vanilla SlayTheSpire.exe (no mods).
exit /b 0

:usage
echo usage: run-game.bat [--dry-run] [--attach] [--strict] [--vanilla] [--help] [MTS args...]
echo.
echo   default         launch the game through ModTheSpire in a detached console
echo                   window, with --mods ^<SPIRELINK_MODS^> (default basemod,spirelink)
echo   --dry-run       print the final command line and exit, do not start anything
echo   --attach        run in the current console and wait for the game to exit
echo   --strict        exit 14 if mods\SpireLink.jar is missing
echo   --min           detached launch with the console window minimized
echo   --test          agent testing: window goes to a secondary monitor when there is one,
echo                   and the human's remembered spot is left untouched
echo   --vanilla       [opt-in] start vanilla SlayTheSpire.exe, no mods
echo   extra args      appended as-is to the MTS command line (e.g. --seed 12345)
exit /b 0
