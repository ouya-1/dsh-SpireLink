<#
.SYNOPSIS
    Stop the Slay the Spire instance that was started through ModTheSpire.

.DESCRIPTION
    Finds java.exe / javaw.exe processes whose command line contains
    "ModTheSpire.jar" (the modded game started by run-game.bat / start-game.bat)
    and stops them:

      1. graceful - CloseMainWindow(), i.e. WM_CLOSE to the game window, the same
                    thing as clicking the X, so the current run gets saved
      2. forced   - Stop-Process -Force, only if the process is still alive after
                    -TimeoutSeconds seconds (default 15)

    Invoked through stop-game.bat; see that file for the .bat interface.
#>
[CmdletBinding()]
param(
    [int]$TimeoutSeconds = 15,
    [switch]$DryRun,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

function Write-Diag { param([string]$Message) [Console]::Error.WriteLine($Message) }
function Emit($Object) { $Object | ConvertTo-Json -Compress -Depth 6 }

if ($Help) {
    $text = @'
usage: stop-game.bat [--timeout <seconds>] [--dry-run] [--help]

  Finds java.exe/javaw.exe whose command line contains ModTheSpire.jar and stops it:
  first a graceful window close (WM_CLOSE, same as clicking the X, so the run is
  saved), then Stop-Process -Force only if it is still alive after --timeout seconds.

  --timeout N   graceful grace period in seconds, default 15
  --dry-run     list what would be stopped, stop nothing

  exit 0  = nothing matching left running (stopped N, N may be 0)
  exit 21 = at least one process survived
  exit 22 = could not query the process list (Win32_Process / CIM)

  stdout: one json line, stderr: human readable detail
'@
    Write-Output $text
    exit 0
}

function Test-Alive {
    param([int]$ProcessId)
    $p = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $p) { return $false }
    try { return -not $p.HasExited } catch { return $false }
}

# Win32 needs: the game window of a NON-focused Slay the Spire is paused
# (SlayTheSpire.log shows "PAUSE()"), and a paused game never polls the GLFW
# close flag - WM_CLOSE alone then does nothing. So restore + foreground the
# window first, let the game loop run, and only then post WM_CLOSE.
function Get-Win32 {
    if (-not ("SLWin.Native" -as [type])) {
        Add-Type -Namespace SLWin -Name Native -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("user32.dll")] public static extern bool ShowWindow(System.IntPtr hWnd, int nCmdShow);
[System.Runtime.InteropServices.DllImport("user32.dll")] public static extern bool SetForegroundWindow(System.IntPtr hWnd);
[System.Runtime.InteropServices.DllImport("user32.dll")] public static extern System.IntPtr GetForegroundWindow();
[System.Runtime.InteropServices.DllImport("user32.dll")] public static extern bool IsIconic(System.IntPtr hWnd);
[System.Runtime.InteropServices.DllImport("user32.dll")] public static extern bool PostMessage(System.IntPtr hWnd, uint Msg, System.IntPtr wParam, System.IntPtr lParam);
'@
    }
    return $true
}

function Focus-GameWindow {
    param([System.IntPtr]$WindowHandle)
    if ($WindowHandle -eq [System.IntPtr]::Zero) { return $false }
    try {
        [void](Get-Win32)
        if ([SLWin.Native]::IsIconic($WindowHandle)) { [void][SLWin.Native]::ShowWindow($WindowHandle, 9) }  # SW_RESTORE
        [void][SLWin.Native]::ShowWindow($WindowHandle, 5)                                                    # SW_SHOW
        return [SLWin.Native]::SetForegroundWindow($WindowHandle)
    } catch {
        Write-Diag ("[stop-game] window focus failed: " + $_.Exception.Message)
        return $false
    }
}

function Send-WmClose {
    param([System.IntPtr]$WindowHandle, $Process)
    if ($WindowHandle -ne [System.IntPtr]::Zero) {
        try {
            [void](Get-Win32)
            [void][SLWin.Native]::PostMessage($WindowHandle, 0x0010, [System.IntPtr]::Zero, [System.IntPtr]::Zero)  # WM_CLOSE
            return $true
        } catch { Write-Diag ("[stop-game] PostMessage(WM_CLOSE) failed: " + $_.Exception.Message) }
    }
    if ($Process) { try { return $Process.CloseMainWindow() } catch { return $false } }
    return $false
}

# ---------- find the modded game process ----------
try {
    $filter = "Name='java.exe' OR Name='javaw.exe'"
    $procs = @(Get-CimInstance Win32_Process -Filter $filter |
               Where-Object { $_.CommandLine -and $_.CommandLine -like "*ModTheSpire.jar*" })
} catch {
    Write-Diag ("[stop-game] cannot query Win32_Process: " + $_.Exception.Message)
    Emit @{ stopped = 0; error = "cim_query_failed"; message = $_.Exception.Message }
    exit 22
}

Write-Diag ("[stop-game] matching processes: " + $procs.Count)

if ($DryRun) {
    $would = @($procs | ForEach-Object { @{ pid = $_.ProcessId; name = $_.Name; cmd = $_.CommandLine } })
    foreach ($w in $would) { Write-Diag ("[stop-game] would stop pid " + $w.pid + ": " + $w.cmd) }
    Emit @{ stopped = 0; dry_run = $true; would_stop = $would }
    exit 0
}

if ($procs.Count -eq 0) {
    Write-Diag "[stop-game] nothing to stop"
    Emit @{ stopped = 0; already_stopped = $true }
    exit 0
}

# ---------- stop them ----------
$results = New-Object System.Collections.ArrayList
foreach ($t in $procs) {
    $procId = [int]$t.ProcessId
    if (-not (Test-Alive $procId)) {
        [void]$results.Add(@{ pid = $procId; stopped = $true; method = "already_exited" })
        continue
    }
    $proc = $null
    try { $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue } catch { $proc = $null }
    $hwnd = [System.IntPtr]::Zero
    if ($proc) { try { $hwnd = $proc.MainWindowHandle } catch { $hwnd = [System.IntPtr]::Zero } }

    # A non-focused Slay the Spire pauses itself (SlayTheSpire.log shows "PAUSE()")
    # and a paused game never polls the GLFW close flag - WM_CLOSE then does
    # nothing for as long as you wait. Restore + foreground the window first so
    # the game loop runs again and can actually process the close.
    $foreground = [System.IntPtr]::Zero
    try { [void](Get-Win32); $foreground = [SLWin.Native]::GetForegroundWindow() } catch { $foreground = [System.IntPtr]::Zero }
    if (($hwnd -ne [System.IntPtr]::Zero) -and ($foreground -ne $hwnd)) {
        $iconic = $false
        try { $iconic = [SLWin.Native]::IsIconic($hwnd) } catch { $iconic = $false }
        if ($iconic) { Write-Diag ("[stop-game] pid " + $procId + ": window is minimized, restoring it") }
        else { Write-Diag ("[stop-game] pid " + $procId + ": window is not focused (the game is paused), bringing it back so it can process the close") }
        [void](Focus-GameWindow -WindowHandle $hwnd)
        Start-Sleep -Milliseconds 500
    }

    Write-Diag ("[stop-game] pid " + $procId + ": sending WM_CLOSE (window handle " + $hwnd + ") - same as clicking the X")
    $asked = Send-WmClose -WindowHandle $hwnd -Process $proc
    if (-not $asked) { Write-Diag ("[stop-game] pid " + $procId + ": no window to close, will have to force") }
    $exited = $false
    if ($proc) { try { $exited = $proc.WaitForExit($TimeoutSeconds * 1000) } catch { $exited = $false } }
    if (-not $exited) {
        Write-Diag ("[stop-game] pid " + $procId + ": still alive after " + $TimeoutSeconds + "s, retrying WM_CLOSE once")
        [void](Send-WmClose -WindowHandle $hwnd -Process $proc)
        if ($proc) { try { $exited = $proc.WaitForExit(5000) } catch { $exited = $false } }
    }
    $method = "wm_close"
    if (-not $exited) {
        Write-Diag ("[stop-game] pid " + $procId + ": still alive, Stop-Process -Force (the run may not be saved)")
        $method = "force"
        try { Stop-Process -Id $procId -Force -ErrorAction Stop } catch {
            Write-Diag ("[stop-game] pid " + $procId + ": Stop-Process failed: " + $_.Exception.Message)
        }
        $deadline = (Get-Date).AddSeconds(10)
        while ((Test-Alive $procId) -and ((Get-Date) -lt $deadline)) { Start-Sleep -Milliseconds 200 }
    }
    $alive = Test-Alive $procId
    if ($alive) { Write-Diag ("[stop-game] pid " + $procId + ": STILL RUNNING") }
    else { Write-Diag ("[stop-game] pid " + $procId + ": stopped via " + $method) }
    [void]$results.Add(@{ pid = $procId; stopped = (-not $alive); method = $method })
}

$stoppedCount = @($results | Where-Object { $_.stopped }).Count
$failedCount = @($results | Where-Object { -not $_.stopped }).Count
Write-Diag ("[stop-game] stopped " + $stoppedCount + ", failed " + $failedCount)

Emit @{ stopped = $stoppedCount; failed = $failedCount; processes = @($results) }
if ($failedCount -gt 0) { exit 21 }
exit 0
