# Takes the README's screenshots: runs the game in the bench's shots mode (Shots.java,
# ./gradlew runClientGameTest -Pshots), captures the console window while the fight goes on, and copies the
# pictures to docs/images. A game window and the console window open while it runs; do not close them.
#
#   pwsh -NoProfile -File tools/shots.ps1 [-Fight <n>]
#
# -Fight picks which of the fight pictures (build/shots/fight-<n>.png) goes to docs/images/fight.png.
param([ValidateRange(1, 6)][int]$Fight = 1)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
Set-Location $root
$shots = Join-Path $root 'build/shots'
$images = Join-Path $root 'docs/images'
$log = Join-Path $root 'build/shots.log'

Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class ShotsWin {
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc proc, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int max);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdc, uint flags);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }

    public static IntPtr Find(string title) {
        IntPtr found = IntPtr.Zero;
        EnumWindows((h, l) => {
            if (!IsWindowVisible(h)) return true;
            var sb = new StringBuilder(512);
            GetWindowText(h, sb, sb.Capacity);
            if (sb.ToString().Contains(title)) { found = h; return false; }
            return true;
        }, IntPtr.Zero);
        return found;
    }
}
'@

# PrintWindow with PW_RENDERFULLCONTENT (2): the window's own pixels, even behind the game window.
function Save-Window([IntPtr]$hwnd, [string]$file) {
    [ShotsWin]::SetProcessDPIAware() | Out-Null
    $r = New-Object ShotsWin+RECT
    [ShotsWin]::GetWindowRect($hwnd, [ref]$r) | Out-Null
    $bmp = New-Object System.Drawing.Bitmap ($r.Right - $r.Left), ($r.Bottom - $r.Top)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $hdc = $g.GetHdc()
    $ok = [ShotsWin]::PrintWindow($hwnd, $hdc, 2)
    $g.ReleaseHdc($hdc)
    $g.Dispose()
    if (-not $ok) { $bmp.Dispose(); throw 'PrintWindow refused the console window' }
    $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

if (Test-Path $shots) { Remove-Item -LiteralPath $shots -Recurse -Force }
New-Item -ItemType Directory -Force $shots | Out-Null
$gradle = Start-Process -FilePath (Join-Path $root 'gradlew.bat') -ArgumentList 'runClientGameTest', '-Pshots' `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" -NoNewWindow -PassThru

$ready = Join-Path $shots 'console.ready'
$deadline = (Get-Date).AddMinutes(10)
while (-not (Test-Path $ready) -and -not $gradle.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
if (Test-Path $ready) {
    $hwnd = [ShotsWin]::Find('Xploits console')
    if ($hwnd -eq [IntPtr]::Zero) {
        Write-Warning 'the console window was not found; no console picture'
    } else {
        Save-Window $hwnd (Join-Path $shots 'console.png')
        Write-Host 'console captured'
    }
    New-Item -ItemType File -Force (Join-Path $shots 'console.done') | Out-Null
}
$gradle.WaitForExit()
if ($gradle.ExitCode -ne 0) { throw "the shots run failed (exit $($gradle.ExitCode)); see $log" }

# The game pictures as JPEG (a quarter of the PNG's size: the world's textures are noise to PNG); the console,
# flat colours and text, stays PNG.
function Save-Jpeg([string]$from, [string]$to) {
    $img = [System.Drawing.Image]::FromFile($from)
    try {
        $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
        $params = New-Object System.Drawing.Imaging.EncoderParameters 1
        $params.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), 90L
        $img.Save($to, $codec, $params)
    } finally { $img.Dispose() }
}

New-Item -ItemType Directory -Force $images | Out-Null
$copies = [ordered]@{ 'clickgui.png' = 'clickgui.jpg'; 'crystal-aura-pp.png' = 'crystal-aura-pp.jpg'; 'restock.png' = 'restock.jpg'; "fight-$Fight.png" = 'fight.jpg'; 'console.png' = 'console.png' }
foreach ($from in $copies.Keys) {
    $source = Join-Path $shots $from
    $target = Join-Path $images $copies[$from]
    if (-not (Test-Path $source)) { Write-Warning "$from was not taken"; continue }
    if ($target.EndsWith('.jpg')) { Save-Jpeg $source $target } else { Copy-Item -LiteralPath $source -Destination $target -Force }
    Write-Host "docs/images/$($copies[$from])"
}
