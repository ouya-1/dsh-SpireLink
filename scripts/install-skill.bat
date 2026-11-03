@echo off
setlocal EnableExtensions
rem ===========================================================================
rem  SpireLink skill installer
rem
rem  DSH discovers skills as <root>\<name>\SKILL.md (one level deep). Roots, by
rem  priority: <project>\.dsh\skills, <project>\.agents\skills, custom dirs,
rem  <dshHome>\skills, <agentsHome>\skills.
rem
rem  Usage:
rem    scripts\install-skill.bat              install as user-level junction
rem                                            (%USERPROFILE%\.dsh\skills\spirelink)
rem    scripts\install-skill.bat --project    install into <repo>\.dsh\skills\spirelink
rem    scripts\install-skill.bat --copy       copy the directory instead of junction
rem    scripts\install-skill.bat --force      replace an existing directory
rem    scripts\install-skill.bat --dry-run    print what would happen, do nothing
rem
rem  Exit codes: 0 ok / 30 repo root not found / 31 skill source missing
rem              32 target exists (need --force) / 33 cannot create target
rem              34 junction or copy failed / 35 verification failed
rem ===========================================================================

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "SRC=%REPO_ROOT%\skill\spirelink"
set "MODE=link"
set "SCOPE=user"
set "FORCE="
set "DRYRUN="

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--project" set "SCOPE=project"
if /i "%~1"=="--copy"    set "MODE=copy"
if /i "%~1"=="--force"   set "FORCE=1"
if /i "%~1"=="--dry-run" set "DRYRUN=1"
shift
goto parse
:parsed

if not exist "%REPO_ROOT%\skill" (
  echo [ERROR] Cannot resolve repo root from "%~f0"
  exit /b 30
)
if not exist "%SRC%\SKILL.md" (
  echo [ERROR] Skill source not found: "%SRC%\SKILL.md"
  exit /b 31
)

if /i "%SCOPE%"=="project" (
  set "TARGET=%REPO_ROOT%\.dsh\skills\spirelink"
) else (
  set "TARGET=%USERPROFILE%\.dsh\skills\spirelink"
)

echo [info] source = %SRC%
echo [info] target = %TARGET%
echo [info] mode   = %MODE% (%SCOPE% scope)

if defined DRYRUN (
  echo [dry-run] nothing changed
  exit /b 0
)

if exist "%TARGET%" (
  if not defined FORCE (
    echo [ERROR] Target already exists: "%TARGET%"
    echo         Re-run with --force to replace it.
    exit /b 32
  )
  echo [info] removing existing target
  rem A junction is removed with plain rmdir; a real directory needs /s /q.
  dir /AL "%TARGET%" 2>nul | find "<JUNCTION>" >nul
  if errorlevel 1 (
    rmdir /s /q "%TARGET%"
  ) else (
    rmdir "%TARGET%"
  )
)

rem Create parent (...\.dsh\skills) if missing.
for %%I in ("%TARGET%\..") do if not exist "%%~fI" md "%%~fI" || (
  echo [ERROR] Cannot create "%TARGET%\.."
  exit /b 33
)

if /i "%MODE%"=="copy" (
  xcopy /E /I /Y /Q "%SRC%" "%TARGET%" >nul
  if errorlevel 1 (
    echo [ERROR] xcopy failed
    exit /b 34
  )
) else (
  cmd /c mklink /J "%TARGET%" "%SRC%" >nul
  if errorlevel 1 (
    echo [ERROR] mklink /J failed - fall back to --copy
    exit /b 34
  )
)

if not exist "%TARGET%\SKILL.md" (
  echo [ERROR] verification failed: %TARGET%\SKILL.md not readable
  exit /b 35
)
if not exist "%TARGET%\references\recovery.md" (
  echo [ERROR] verification failed: references not reachable
  exit /b 35
)

echo [OK] skill installed: %TARGET%
echo      Start a NEW session (or wait for the skill watcher) and check that
echo      available_skills lists "spirelink"; then call  skill  with name
echo      "spirelink" to load the body.
exit /b 0
