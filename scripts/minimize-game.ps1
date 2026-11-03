# Minimize the Slay the Spire game window without touching z-order or focus.
#
# A launched game pops up wherever Windows remembers (on a multi-monitor setup that
# is often the bottom-left corner of the secondary screen, sometimes right over the
# user's work). start-game.bat calls this as soon as /health turns ready, and this
# script keeps retrying for a while because the game window is created *after* the
# mod's HTTP server is already answering /health.
#
# exit 0 = minimized (or already minimized), 3 = window never showed up, 4 = Add-Type failed

param(
    [string]$Title = "Modded Slay the Spire",
    [int]$TimeoutSeconds = 30
)

$sig = @'
[DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
public static extern IntPtr FindWindow(string lpClassName, string lpWindowName);

[DllImport("user32.dll")]
public static extern bool IsIconic(IntPtr hWnd);

[DllImport("user32.dll")]
public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
'@

try {
    $w = Add-Type -MemberDefinition $sig -Name "SpireLinkWindow" -Namespace "SpireLink" -PassThru -ErrorAction Stop
} catch {
    Write-Output "add-type-failed"
    exit 4
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ($true) {
    # [NullString]::Value keeps the class name a real NULL; a plain $null is marshalled
    # as an empty string and FindWindow then matches nothing.
    $hwnd = $w::FindWindow([NullString]::Value, $Title)
    if ($hwnd -ne [IntPtr]::Zero) {
        if ($w::IsIconic($hwnd)) {
            Write-Output "already-minimized"
            exit 0
        }
        $w::ShowWindow($hwnd, 6) | Out-Null   # SW_MINIMIZE: no activation, no foreground steal
        Start-Sleep -Milliseconds 200
        if ($w::IsIconic($hwnd)) {
            Write-Output "minimized"
            exit 0
        }
        Write-Output "minimize-failed"
        exit 3
    }
    if ((Get-Date) -gt $deadline) {
        Write-Output "no-window"
        exit 3
    }
    Start-Sleep -Milliseconds 500
}
