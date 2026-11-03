@echo off
setlocal EnableExtensions
rem ===========================================================================
rem  stop-game.bat - stop the modded Slay the Spire started by run-game.bat /
rem  start-game.bat (java.exe whose command line contains ModTheSpire.jar).
rem
rem  Graceful first: WM_CLOSE to the game window (= clicking the X, the current
rem  run is saved) and wait --timeout seconds; only then Stop-Process -Force.
rem  The heavy lifting is in stop-game.ps1 (PowerShell 5.1, built into Windows).
rem
rem  Contract:
rem    stdout = exactly ONE json line, stderr = human readable detail
rem    stopped    {"stopped":1,"failed":0,"processes":[{"pid":66252,"stopped":true,"method":"wm_close"}]}
rem    nothing    {"stopped":0,"already_stopped":true}
rem    dry run    {"stopped":0,"dry_run":true,"would_stop":[{"pid":66252,"name":"java.exe","cmd":"..."}]}
rem
rem  Usage:
rem    scripts\stop-game.bat                  graceful stop, 15 s grace
rem    scripts\stop-game.bat --timeout 30     longer grace period
rem    scripts\stop-game.bat --dry-run        list the processes, stop nothing
rem    scripts\stop-game.bat --help
rem
rem  Exit codes:
rem    0   nothing matching is running any more (stopped N, N may be 0)
rem    10  bad/missing option value
rem    11  unknown argument
rem    21  at least one process survived
rem    22  could not query the process list, or PowerShell/stop-game.ps1 missing
rem ===========================================================================

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
if not exist "%REPO_ROOT%\scripts" (
  echo [ERROR] Cannot resolve repo root from "%~f0" 1>&2
  exit /b 10
)

set "PWSH=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "PS1=%~dp0stop-game.ps1"
if not exist "%PWSH%" (
  echo [ERROR] Windows PowerShell not found: "%PWSH%" 1>&2
  exit /b 22
)
if not exist "%PS1%" (
  echo [ERROR] stop-game.ps1 not found next to this script: "%PS1%" 1>&2
  exit /b 22
)

rem ---------- translate batch-style flags into PowerShell parameters ----------
set "PSARGS="
:argloop
if "%~1"=="" goto runps
set "ARG=%~1"
if /i "%ARG%"=="--timeout" goto opt_timeout
if /i "%ARG%"=="--dry-run" ( set "PSARGS=%PSARGS% -DryRun" & shift & goto argloop )
if /i "%ARG%"=="--help"    ( set "PSARGS=%PSARGS% -Help"   & shift & goto argloop )
if /i "%ARG%"=="-h"        ( set "PSARGS=%PSARGS% -Help"   & shift & goto argloop )
echo [ERROR] unknown argument: %ARG% (see --help) 1>&2
exit /b 11
:opt_timeout
shift
if "%~1"=="" ( echo [ERROR] --timeout needs a value in seconds 1>&2 & exit /b 10 )
set "PSARGS=%PSARGS% -TimeoutSeconds %~1"
shift
goto argloop
:runps

"%PWSH%" -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %PSARGS%
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
