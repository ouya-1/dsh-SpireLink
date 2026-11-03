<#
.SYNOPSIS
    Keep the Slay the Spire window where it belongs, without stealing focus.

.DESCRIPTION
    A human drags the window once; that rectangle is remembered in scripts\window_position.json and
    every later launch restores exactly that spot - and keeps restoring it for a while, because the
    game re-centres its own window a few seconds after start-up.

      * remembered rect (默认)  -> applied as-is, re-applied whenever the window drifts
      * no memory, 2+ monitors -> the secondary screen, centred
      * no memory, 1 monitor   -> the primary working area, centred (never stranded off-screen)
      * remembered rect on a monitor that is now switched off -> fallback above

    Nothing here uses the mouse or the keyboard; a window that was minimised stays minimised.

.PARAMETER Save
    Remember the window's CURRENT rectangle (drag it first, then run this).

.PARAMETER Show
    Show the list of monitors and the remembered rectangle, change nothing.

.PARAMETER ForcePrimary
    Diagnostic switch: behave as if only the primary monitor existed.

.PARAMETER Secondary
    Put the window on a secondary monitor, centred, at the remembered SIZE - and do not touch
    window_position.json. This is the "the agent is testing" mode: the human keeps the primary
    screen, the saved spot stays whatever the human dragged the window to.

.PARAMETER Seconds
    How long to keep watching for drift after applying the position. Default: 90.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\place_window.ps1 -Save
#>
param(
    [string]$ProcessName = "java",
    [string]$TitleLike = "Slay",
    [int]$WaitSeconds = 180,
    [int]$Seconds = 90,
    [switch]$Save,
    [switch]$Show,
    [switch]$Secondary,
    [switch]$ForcePrimary
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class WinPlace {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern int GetWindowTextLength(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder s, int max);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    // Without this a 150%-scaled desktop reports 857x507 for a 1280x720 window.
    [DllImport("shcore.dll")] public static extern int SetProcessDpiAwareness(int value);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }

    public static string TitleOf(IntPtr h) {
        StringBuilder sb = new StringBuilder(512);
        GetWindowText(h, sb, 512);
        return sb.ToString();
    }

    public static IntPtr FindByPids(uint[] pids, string titleLike) {
        IntPtr exact = IntPtr.Zero;
        IntPtr any = IntPtr.Zero;
        EnumWindows(delegate(IntPtr h, IntPtr l) {
            uint pid;
            GetWindowThreadProcessId(h, out pid);
            bool mine = false;
            foreach (uint want in pids) { if (pid == want) { mine = true; break; } }
            if (!mine || GetWindowTextLength(h) == 0) { return true; }
            if (any == IntPtr.Zero) { any = h; }
            if (TitleOf(h).IndexOf(titleLike, StringComparison.OrdinalIgnoreCase) >= 0) { exact = h; return false; }
            return true;
        }, IntPtr.Zero);
        return exact != IntPtr.Zero ? exact : any;
    }
}
"@

try { [void][WinPlace]::SetProcessDpiAwareness(2) } catch { }

$SWP = 0x0001 -bor 0x0004 -bor 0x0010
$SW_MINIMIZE = 6
$SW_SHOWNOACTIVATE = 4
$repo = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$store = Join-Path $repo "scripts\window_position.json"

function Get-GameWindow {
    param([string]$Name, [int]$TimeoutSeconds, [string]$Title)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $procs = @(Get-Process -Name $Name -ErrorAction SilentlyContinue)
        if ($procs.Count -gt 0) {
            foreach ($p in $procs) {
                if ($p.MainWindowHandle -ne [IntPtr]::Zero) { return $p.MainWindowHandle }
            }
            $pids = @($procs | ForEach-Object { [uint32]$_.Id })
            $hwnd = [WinPlace]::FindByPids($pids, $Title)
            if ($hwnd -ne [IntPtr]::Zero) { return $hwnd }
        }
        Start-Sleep -Milliseconds 700
    }
    return [IntPtr]::Zero
}

function Get-Rect {
    param([IntPtr]$Handle)
    $r = New-Object WinPlace+RECT
    [WinPlace]::GetWindowRect($Handle, [ref]$r) | Out-Null
    return @{ X = $r.Left; Y = $r.Top; W = ($r.Right - $r.Left); H = ($r.Bottom - $r.Top) }
}

$screens = @([System.Windows.Forms.Screen]::AllScreens)
$primary = $screens | Where-Object { $_.Primary } | Select-Object -First 1
$others = @($screens | Where-Object { -not $_.Primary })
if ($ForcePrimary) { $others = @() }

function Show-Monitors {
    ($screens | ForEach-Object {
        $tag = ""
        if ($_.Primary) { $tag = " (primary)" }
        "{0} {1}x{2} at {3},{4}{5}" -f $_.DeviceName, $_.Bounds.Width, $_.Bounds.Height, $_.Bounds.X, $_.Bounds.Y, $tag
    }) -join "; "
}

function On-SomeScreen {
    param([int]$Cx, [int]$Cy)
    foreach ($s in $screens) {
        $b = $s.Bounds
        if ($Cx -ge $b.X -and $Cx -lt ($b.X + $b.Width) -and $Cy -ge $b.Y -and $Cy -lt ($b.Y + $b.Height)) { return $s }
    }
    return $null
}

# ---------- -Save: remember whatever the human dragged to
if ($Save) {
    $h = Get-GameWindow -Name $ProcessName -TimeoutSeconds 10 -Title $TitleLike
    if ($h -eq [IntPtr]::Zero) { Write-Output "[place] no game window to remember"; exit 1 }
    $r = Get-Rect -Handle $h
    $mon = On-SomeScreen -Cx ([int]($r.X + $r.W / 2)) -Cy ([int]($r.Y + $r.H / 2))
    $monName = ""
    if ($null -ne $mon) { $monName = $mon.DeviceName }
    $data = [ordered]@{ x = $r.X; y = $r.Y; w = $r.W; h = $r.H; monitor = $monName; savedAt = (Get-Date).ToString("s") }
    $data | ConvertTo-Json | Set-Content -Path $store -Encoding UTF8
    Write-Output ("[place] remembered: {0},{1} {2}x{3} on {4} -> {5}" -f $r.X, $r.Y, $r.W, $r.H, $monName, $store)
    exit 0
}

if ($Show) {
    Write-Output ("[place] monitors: " + (Show-Monitors))
    if (Test-Path $store) { Write-Output ("[place] remembered: " + (Get-Content -Raw $store).Trim()) }
    else { Write-Output "[place] remembered: (none)" }
    $h = Get-GameWindow -Name $ProcessName -TimeoutSeconds 3 -Title $TitleLike
    if ($h -eq [IntPtr]::Zero) { Write-Output "[place] now: (no window)" }
    else {
        $r = Get-Rect -Handle $h
        $mon = On-SomeScreen -Cx ([int]($r.X + $r.W / 2)) -Cy ([int]($r.Y + $r.H / 2))
        $mn = "(off-screen)"
        if ($null -ne $mon) { $mn = $mon.DeviceName }
        Write-Output ("[place] now: {0},{1} {2}x{3} on {4}" -f $r.X, $r.Y, $r.W, $r.H, $mn)
    }
    exit 0
}

# ---------- -Secondary: agent test mode, secondary monitor, keep the saved spot untouched
if ($Secondary) {
    $hwndS = Get-GameWindow -Name $ProcessName -TimeoutSeconds $WaitSeconds -Title $TitleLike
    if ($hwndS -eq [IntPtr]::Zero) { Write-Output "[place] no window for -Secondary"; exit 0 }
    $pickS = $others | Sort-Object { $_.Bounds.X } | Select-Object -First 1
    if ($null -eq $pickS) {
        Write-Output "[place] -Secondary asked for but there is no secondary monitor; leaving the window alone"
        exit 0
    }
    $bS = $pickS.Bounds
    Write-Output ("[place] -Secondary: {0} {1}x{2} at {3},{4}; waiting for the game to settle" -f $pickS.DeviceName, $bS.Width, $bS.Height, $bS.X, $bS.Y)
    # The game creates a tiny window first and grows it a moment later; centring too early just
    # leaves it half off-screen, so wait until it looks like a real window.
    $settle = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $settle) {
        $h2 = Get-GameWindow -Name $ProcessName -TimeoutSeconds 2 -Title $TitleLike
        if ($h2 -ne [IntPtr]::Zero) { $hwndS = $h2 }
        $rS = Get-Rect -Handle $hwndS
        if ($rS.W -ge 1200) { break }
        Start-Sleep -Milliseconds 600
    }
    # Then keep it centred for a while: the game re-centres itself on the primary screen a few
    # seconds after start-up, and a single nudge is not enough.
    $deadlineS = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadlineS) {
        $h = Get-GameWindow -Name $ProcessName -TimeoutSeconds 3 -Title $TitleLike
        if ($h -eq [IntPtr]::Zero) { Write-Output "[place] -Secondary: window gone"; break }
        $rS = Get-Rect -Handle $h
        $xS = [int]($bS.X + ($bS.Width - $rS.W) / 2)
        $yS = [int]($bS.Y + ($bS.Height - $rS.H) / 2)
        if ([Math]::Abs($rS.X - $xS) -gt 2 -or [Math]::Abs($rS.Y - $yS) -gt 2) {
            if ([WinPlace]::IsIconic($h)) { [WinPlace]::ShowWindow($h, $SW_SHOWNOACTIVATE) | Out-Null }
            [WinPlace]::SetWindowPos($h, [IntPtr](-1), $xS, $yS, $rS.W, $rS.H, $SWP) | Out-Null
            Write-Output ("[place] -Secondary move: {0},{1} {2}x{3} -> {4},{5}" -f $rS.X, $rS.Y, $rS.W, $rS.H, $xS, $yS)
        }
        Start-Sleep -Milliseconds 1500
    }
    $rS = Get-Rect -Handle $hwndS
    Write-Output ("[place] -Secondary done: {0},{1} {2}x{3} (saved spot untouched)" -f $rS.X, $rS.Y, $rS.W, $rS.H)
    exit 0
}

# ---------- decide where the window goes
$saved = $null
if (Test-Path $store) {
    try { $saved = Get-Content -Raw $store | ConvertFrom-Json } catch { $saved = $null }
}

$hwnd = Get-GameWindow -Name $ProcessName -TimeoutSeconds $WaitSeconds -Title $TitleLike
if ($hwnd -eq [IntPtr]::Zero) {
    Write-Output ("[place] no window for '" + $ProcessName + "' within " + $WaitSeconds + "s")
    exit 0
}

Write-Output ("[place] monitors: " + (Show-Monitors))

$target = $null
$why = ""
if ($null -ne $saved) {
    $mon = On-SomeScreen -Cx ([int]($saved.x + $saved.w / 2)) -Cy ([int]($saved.y + $saved.h / 2))
    if ($null -ne $mon -and -not ($ForcePrimary -and $mon.Primary)) {
        $target = @{ X = [int]$saved.x; Y = [int]$saved.y; W = [int]$saved.w; H = [int]$saved.h }
        $why = "remembered spot on " + $mon.DeviceName
    } else {
        $why = "remembered spot is on a screen that is not available now"
    }
}
if ($null -eq $target) {
    $pick = $null
    if ($others.Count -gt 0) { $pick = $others | Sort-Object { $_.Bounds.X } | Select-Object -First 1 }
    else { $pick = $primary }
    $b = $pick.Bounds
    $cur = Get-Rect -Handle $hwnd
    $w = $cur.W; $h = $cur.H
    if ($w -le 0 -or $w -gt $b.Width) { $w = [int]($b.Width * 0.9) }
    if ($h -le 0 -or $h -gt $b.Height) { $h = [int]($b.Height * 0.9) }
    $target = @{ X = [int]($b.X + ($b.Width - $w) / 2); Y = [int]($b.Y + ($b.Height - $h) / 2); W = $w; H = $h }
    if ($why -ne "") { $why = $why + " -> " + $pick.DeviceName + " centred" }
    else { $why = "no remembered spot -> " + $pick.DeviceName + " centred" }
}

$before = Get-Rect -Handle $hwnd
Write-Output ("[place] window before: {0},{1} {2}x{3}" -f $before.X, $before.Y, $before.W, $before.H)
Write-Output ("[place] target: {0},{1} {2}x{3}  ({4})" -f $target.X, $target.Y, $target.W, $target.H, $why)

# ---------- apply, then keep the game from re-centring itself
if ($null -eq $saved) {
    # No remembered spot yet: place once and get out of the way. Re-applying here would fight a
    # human who is dragging the window to the spot they want us to remember.
    $Seconds = 0
}
$deadline = (Get-Date).AddSeconds($Seconds)
$applied = 0
while ($true) {
    $h = Get-GameWindow -Name $ProcessName -TimeoutSeconds 3 -Title $TitleLike
    if ($h -eq [IntPtr]::Zero) { Write-Output "[place] window gone, stop"; break }
    $cur = Get-Rect -Handle $h
    $dx = [Math]::Abs($cur.X - $target.X)
    $dy = [Math]::Abs($cur.Y - $target.Y)
    if ($dx -gt 2 -or $dy -gt 2) {
        $wasMin = [WinPlace]::IsIconic($h)
        if ($wasMin) { [WinPlace]::ShowWindow($h, $SW_SHOWNOACTIVATE) | Out-Null }
        [WinPlace]::SetWindowPos($h, [IntPtr](-1), $target.X, $target.Y, $target.W, $target.H, $SWP) | Out-Null
        $applied = $applied + 1
        $now = Get-Rect -Handle $h
        Write-Output ("[place] moved #{0}: {1},{2} -> {3},{4}" -f $applied, $cur.X, $cur.Y, $now.X, $now.Y)
        if ($wasMin) { [WinPlace]::ShowWindow($h, $SW_MINIMIZE) | Out-Null }
    }
    if ((Get-Date) -ge $deadline -and $applied -gt 0) { break }
    if ($applied -eq 0 -and (Get-Date) -ge $deadline) { break }
    Start-Sleep -Milliseconds 1500
}
Write-Output ("[place] done, applied {0} time(s)" -f $applied)
exit 0
