@echo off
setlocal EnableExtensions
rem ===========================================================================
rem  start-game.bat - bring the game up THROUGH ModTheSpire and wait until the
rem  SpireLink MCP server answers, so an agent can go from "game not running"
rem  to "MCP callable" with one command and no human in the loop.
rem
rem  Contract:
rem    stdout = exactly ONE json line (machine readable)
rem    stderr = human diagnostics (probe results, chosen paths, launch log)
rem
rem    already up     {"started":false,"already_running":true,"url":"..."}
rem    became ready   {"started":true,"ready":true,"waited_ms":23145,"url":"..."}
rem    timeout        {"started":true,"ready":false,"waited_ms":120001,"url":"...","hint":...}
rem    launch failure {"started":false,"ready":false,"error":"launch_failed","launch_exit":14,...}
rem    dry run        {"started":false,"dry_run":true,"already_running":false,...}
rem
rem  Idempotent: if /health already returns status ok, NOTHING is started (a
rem  second instance would collide on port 8731 and the mod refuses to serve).
rem
rem  Env override:
rem    SPIRELINK_HEALTH_URL   default http://127.0.0.1:8731/health
rem
rem  Usage:
rem    scripts\start-game.bat                  start if needed, wait up to 120 s
rem    scripts\start-game.bat --timeout 180    custom readiness timeout (seconds)
rem    scripts\start-game.bat --dry-run        probe only, print the launch command
rem    scripts\start-game.bat --json           accepted (json is the default)
rem    scripts\start-game.bat --help
rem
rem  Exit codes:
rem    0   MCP ready (either it was already running, or it just became ready)
rem    10  bad/missing option value, or repo layout not resolvable
rem    11  unknown argument
rem    12  curl.exe not found (used for the /health probe)
rem    20  game started, but /health was not ready before the timeout
rem    22  launch failed (json carries launch_exit = run-game.bat exit code)
rem ===========================================================================

rem Work even when the caller inherited a stripped PATH (e.g. a MSYS shell).
set "PATH=%SystemRoot%\System32;%SystemRoot%;%PATH%"

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
if defined SPIRELINK_GAME for %%I in ("%SPIRELINK_GAME%") do set "GAME_DIR=%%~fI"
if not defined GAME_DIR for %%I in ("%~dp0..\..") do set "GAME_DIR=%%~fI"
if not exist "%REPO_ROOT%\scripts" (
  1>&2 echo [ERROR] Cannot resolve repo root from "%~f0"
  exit /b 10
)

set "HEALTH_URL=http://127.0.0.1:8731/health"
if defined SPIRELINK_HEALTH_URL set "HEALTH_URL=%SPIRELINK_HEALTH_URL%"
set "TIMEOUT_S=120"
set "DRY_RUN="
set "RUN_GAME=%~dp0run-game.bat"
set "MIN_PS1=%~dp0minimize-game.ps1"
set "NO_MINIMIZE="
set "MIN_RESULT="
set "PWSH=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "CURL=%SystemRoot%\System32\curl.exe"

rem ---------- arguments ----------
:argloop
if "%~1"=="" goto argsdone
set "ARG=%~1"
if /i "%ARG%"=="--timeout" goto opt_timeout
if /i "%ARG%"=="--dry-run" ( set "DRY_RUN=1" & shift & goto argloop )
if /i "%ARG%"=="--json"    ( shift & goto argloop )
if /i "%ARG%"=="--no-minimize" ( set "NO_MINIMIZE=1" & shift & goto argloop )
if /i "%ARG%"=="--help" goto usage
if /i "%ARG%"=="-h" goto usage
1>&2 echo [ERROR] unknown argument: %ARG% ^(see --help^)
exit /b 11
:opt_timeout
shift
if "%~1"=="" ( 1>&2 echo [ERROR] --timeout needs a value in seconds & exit /b 10 )
set "TIMEOUT_S=%~1"
shift
goto argloop
:argsdone

if not exist "%CURL%" (
  1>&2 echo [ERROR] curl.exe not found: "%CURL%"
  1>&2 echo         Windows 10 1803+ ships it; it is needed for the /health probe.
  exit /b 12
)
if not exist "%RUN_GAME%" (
  1>&2 echo [ERROR] run-game.bat not found next to this script: "%RUN_GAME%"
  exit /b 10
)
1>&2 echo [start-game] repo   = %REPO_ROOT%
1>&2 echo [start-game] game   = %GAME_DIR%
1>&2 echo [start-game] health = %HEALTH_URL%

rem ---------- 1) is it already serving? ----------
call :probe
if "%HEALTH_OK%"=="1" goto already_running
1>&2 echo [start-game] probe: not ready (curl exit %PROBE_RC%)

rem ---------- 2) dry run ----------
if defined DRY_RUN (
  1>&2 echo [start-game] --dry-run: nothing will be started; the launch command would be:
  call "%RUN_GAME%" --dry-run --min --strict 1>&2
  echo {"started":false,"dry_run":true,"already_running":false,"url":"%HEALTH_URL%"}
  exit /b 0
)

rem ---------- 3) launch the modded game (minimized console window) ----------
set "LAUNCH_LOG=%TEMP%\spirelink_startgame_%RANDOM%%RANDOM%.log"
call "%RUN_GAME%" --min --strict > "%LAUNCH_LOG%" 2>&1
set "LAUNCH_RC=%ERRORLEVEL%"
type "%LAUNCH_LOG%" 1>&2
if not "%LAUNCH_RC%"=="0" (
  del "%LAUNCH_LOG%" >nul 2>nul
  echo {"started":false,"ready":false,"error":"launch_failed","launch_exit":%LAUNCH_RC%,"url":"%HEALTH_URL%"}
  exit /b 22
)

rem ---------- 4) poll /health every ~1 s until ready or timeout ----------
call :now
set "ELAPSED_S=0"
:poll
call :probe
if "%HEALTH_OK%"=="1" goto ready
if %ELAPSED_S% GEQ %TIMEOUT_S% goto timeout
ping -n 2 127.0.0.1 >nul 2>&1
set /a ELAPSED_S+=1
goto poll

:ready
call :waited
del "%LAUNCH_LOG%" >nul 2>nul
1>&2 echo [start-game] MCP is ready after ~%ELAPSED_S%s
call :minimize
echo {"started":true,"ready":true,"waited_ms":%WAITED_MS%,"url":"%HEALTH_URL%"}
exit /b 0

rem ---------- keep the game window off the user's desktop ----------
:minimize
if defined NO_MINIMIZE ( 1>&2 echo [start-game] --no-minimize given, window left as is & goto :eof )
if not exist "%PWSH%" goto :eof
if not exist "%MIN_PS1%" goto :eof
set "MINOUT=%TEMP%spirelink_min_%RANDOM%%RANDOM%.txt"
"%PWSH%" -NoProfile -ExecutionPolicy Bypass -File "%MIN_PS1%" -TimeoutSeconds 30 > "%MINOUT%" 2>nul
if exist "%MINOUT%" for /f "usebackq delims=" %%M in ("%MINOUT%") do set "MIN_RESULT=%%M"
del "%MINOUT%" >nul 2>nul
if not defined MIN_RESULT set "MIN_RESULT=unknown"
1>&2 echo [start-game] window: %MIN_RESULT%
goto :eof

:timeout
call :waited
del "%LAUNCH_LOG%" >nul 2>nul
1>&2 echo [start-game] TIMEOUT: /health still not ready after %TIMEOUT_S%s
1>&2 echo [start-game] check: "%GAME_DIR%\mods\spirelink-data\spirelink-debug.log"
echo {"started":true,"ready":false,"waited_ms":%WAITED_MS%,"url":"%HEALTH_URL%","hint":"check mods/spirelink-data/spirelink-debug.log"}
exit /b 20

:already_running
if defined DRY_RUN (
  1>&2 echo [start-game] --dry-run: /health already answers status ok, nothing to start
  echo {"started":false,"dry_run":true,"already_running":true,"url":"%HEALTH_URL%"}
  exit /b 0
)
1>&2 echo [start-game] already running, not starting a second instance
echo {"started":false,"already_running":true,"url":"%HEALTH_URL%"}
exit /b 0

rem ======================= helpers =======================
:probe
rem HEALTH_OK=1 when GET /health answers 2xx AND the body carries status+ok
set "HEALTH_OK=0"
set "PROBE_RC=0"
set "BODY=%TEMP%\spirelink_health_%RANDOM%%RANDOM%.json"
"%CURL%" -s -f -m 2 -o "%BODY%" "%HEALTH_URL%" >nul 2>nul
set "PROBE_RC=%ERRORLEVEL%"
if not "%PROBE_RC%"=="0" goto probe_cleanup
if not exist "%BODY%" goto probe_cleanup
findstr /i /c:"status" /c:"ok" "%BODY%" >nul 2>nul
if errorlevel 1 goto probe_cleanup
set "HEALTH_OK=1"
:probe_cleanup
del "%BODY%" >nul 2>nul
goto :eof

rem NOTE: never put a powershell expression containing () inside a FOR /F
rem       IN(...) clause - cmd parses those parentheses as the IN block and
rem       dies with "filename/directory/volume label syntax is incorrect".
rem       Writing to a temp file and reading it back is safe.
:now
set "T0="
if not exist "%PWSH%" goto :eof
set "MSFILE=%TEMP%\spirelink_ms_%RANDOM%%RANDOM%.txt"
"%PWSH%" -NoProfile -Command "[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()" > "%MSFILE%" 2>nul
if exist "%MSFILE%" for /f "usebackq delims=" %%T in ("%MSFILE%") do set "T0=%%T"
del "%MSFILE%" >nul 2>nul
goto :eof

:waited
rem WAITED_MS = now - T0 in ms; falls back to ELAPSED_S*1000 without powershell
set "WAITED_MS="
if not defined T0 goto waited_fallback
if not exist "%PWSH%" goto waited_fallback
set "MSFILE=%TEMP%\spirelink_ms_%RANDOM%%RANDOM%.txt"
"%PWSH%" -NoProfile -Command "[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - %T0%" > "%MSFILE%" 2>nul
if exist "%MSFILE%" for /f "usebackq delims=" %%T in ("%MSFILE%") do set "WAITED_MS=%%T"
del "%MSFILE%" >nul 2>nul
if not defined WAITED_MS goto waited_fallback
goto :eof
:waited_fallback
set /a WAITED_MS=ELAPSED_S*1000
goto :eof

:usage
echo usage: start-game.bat [--timeout ^<seconds^>] [--dry-run] [--json] [--no-minimize] [--help]
echo.
echo   --timeout N   readiness timeout in seconds, default 120
echo   --dry-run     probe /health and print the launch command, start nothing
echo   --json        accepted for compatibility; stdout is always one json line
echo   --no-minimize keep the game window on screen ^(default: minimize it once ready^)
echo.
echo   exit 0 = ready / already running, 20 = timeout, 22 = launch failed
echo   stdout = one json line, stderr = diagnostics
exit /b 0
