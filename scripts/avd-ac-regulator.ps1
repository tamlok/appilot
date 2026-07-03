<#
.SYNOPSIS
  AVD AC auto-regulation loop (standalone; does not use the appilot app).

.DESCRIPTION
  Runs full cycles with a unified interval, defaulting to 1 minute, and an
  increasing cycle index.
    1. Ensure the emulator is running. The default mode reuses an existing emulator
       and starts one only when needed.
       With -ColdBoot, every cycle kills all AVDs and cold-boots with
       software rendering and snapshots disabled.
    2. Open the Tuya temperature/humidity shortcut, capture a screenshot, and read
       current temperature with tesseract OCR.
     3. If safeband low <= temperature <= safeband high: recover any recorded
        critical setpoint first, otherwise take no action.
     4. For out-of-safeband readings, adjust only when worsening or when unchanged
        for more than the configured threshold cycles.
     5. Open the Haier bedroom AC shortcut. If AC is powered off: take no action.
     6. If temperature is below the safeband: increase setpoint by 1, capped at the ceiling.
     7. If temperature is above the safeband: decrease setpoint by 1, floored at the floor.

  The temperature and setpoint are rendered graphics, not text nodes, so OCR uses:
  screenshot -> crop -> upscale -> tesseract. AC power state is inferred from the
  blue fill ratio in the power button area.

.NOTES
  Dependencies: Android SDK (emulator + adb), tesseract with eng.traineddata.
  Uses only adb/emulator/tesseract and no parent appilot app code.
  Includes 1080x2400 and 480x854 calibrations; defaults to auto-selecting by the
  running device resolution.
#>

[CmdletBinding()]
param(
    [string]$AvdName        = 'Medium_Phone',
    [int]   $IntervalMinutes = 1,
    [double]$SafebandLow    = 24.8,
    [double]$SafebandHigh   = 24.9,
    [int]   $SetpointFloor   = 25,
    [int]   $SetpointCeiling = 28,
    [int]   $UnchangedThresholdCycles = 4,
    [string]$Serial          = 'emulator-5554',
    [string]$Sdk             = "$env:LOCALAPPDATA\Android\Sdk",
    [int]   $MaxCycles       = 0,   # 0 = infinite loop
    [string]$WorkDir         = "$env:LOCALAPPDATA\Temp\avd-ac-regulator",
    [string]$Calibration     = 'auto',
    [switch]$Help,           # Print supported options and exit.
    [switch]$Init,           # Prepare environment only, then exit without running cycles.
    [switch]$ColdBoot        # Kill all AVDs and cold-boot every cycle. Default reuses the emulator.
)

function Write-Usage {
    @"
AVD AC regulator

Usage:
  pwsh -File .\scripts\avd-ac-regulator.ps1 [options]

Options:
  -Help                                 Print this help text and exit. Default: false
  -Init                                 Prepare runtime dependencies and exit. Default: false
  -ColdBoot                             Kill all AVDs and cold-boot every cycle. Default: false
  -AvdName <string>                     AVD name. Default: Medium_Phone
  -Serial <string>                      adb serial. Default: emulator-5554
  -Sdk <string>                         Android SDK path. Default: `$env:LOCALAPPDATA\Android\Sdk
  -WorkDir <string>                     Temporary working directory. Default: `$env:LOCALAPPDATA\Temp\avd-ac-regulator
  -Calibration <auto|1080x2400|480x854> Coordinate calibration profile. Default: auto
  -MaxCycles <int>                      Number of cycles to run; 0 means forever. Default: 0
  -IntervalMinutes <int>                Unified interval between cycles. Default: 1
  -UnchangedThresholdCycles <int>       Same-temperature retry threshold in cycles. Default: 4
  -SafebandLow <double>                 Lower no-action temperature bound. Default: 24.8
  -SafebandHigh <double>                Upper no-action temperature bound. Default: 24.9
  -SetpointFloor <int>                  Lowest AC setpoint used by automation. Default: 25
  -SetpointCeiling <int>                Highest AC setpoint used by automation. Default: 28
Examples:
  pwsh -File .\scripts\avd-ac-regulator.ps1 -Help
  pwsh -File .\scripts\avd-ac-regulator.ps1 -Init
  pwsh -File .\scripts\avd-ac-regulator.ps1 -MaxCycles 1
  pwsh -File .\scripts\avd-ac-regulator.ps1 -SafebandLow 24.5 -SafebandHigh 24.9
"@
}

if ($Help) { Write-Usage; return }

$ErrorActionPreference = 'Stop'
$APP_SETPOINT_MIN = 16
$APP_SETPOINT_MAX = 30
if ([double]::IsNaN($SafebandLow) -or [double]::IsNaN($SafebandHigh) -or
    [double]::IsInfinity($SafebandLow) -or [double]::IsInfinity($SafebandHigh)) {
    throw 'Safeband bounds must be finite numbers.'
}
if (@('auto', '1080x2400', '480x854') -notcontains $Calibration) {
    throw "-Calibration must be one of: auto, 1080x2400, 480x854."
}
if ($SetpointFloor -lt $APP_SETPOINT_MIN -or $SetpointCeiling -gt $APP_SETPOINT_MAX) {
    throw "Setpoint range must stay within the supported app range [$APP_SETPOINT_MIN, $APP_SETPOINT_MAX]."
}
if ($SafebandLow -gt $SafebandHigh) {
    throw '-SafebandLow must be less than or equal to -SafebandHigh.'
}
if ($SetpointFloor -gt $SetpointCeiling) {
    throw '-SetpointFloor must be less than or equal to -SetpointCeiling.'
}
if (($SetpointCeiling - $SetpointFloor) -lt 2) {
    throw '-SetpointCeiling must be at least 2 greater than -SetpointFloor for critical-zone recovery.'
}
if ($IntervalMinutes -lt 1) {
    throw '-IntervalMinutes must be at least 1.'
}
if ($UnchangedThresholdCycles -lt 0) {
    throw '-UnchangedThresholdCycles must be at least 0.'
}

. (Join-Path $PSScriptRoot 'avd-ac-regulator.logic.ps1')

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class NativeWin {
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr hAfter, int X, int Y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
}
"@

# ---------------------------------------------------------------------------
# Configuration / Calibration
# ---------------------------------------------------------------------------
$ADB = Join-Path $Sdk 'platform-tools\adb.exe'
$EMU = Join-Path $Sdk 'emulator\emulator.exe'
$TESS = (Get-Command tesseract -ErrorAction SilentlyContinue).Source
if (-not $TESS) { $TESS = Join-Path $env:USERPROFILE 'scoop\shims\tesseract.exe' }

# Defaults are the legacy 1080x2400 coordinates. Runtime calibration switches them.
# Home-screen shortcut centers.
$TAP_TEMP_SHORTCUT = @(663, 1221)   # Tuya temperature/humidity shortcut
$TAP_AC_SHORTCUT   = @(910, 1221)   # Haier bedroom AC shortcut
# AC setpoint +/- buttons.
$TAP_AC_PLUS       = @(936, 866)
$TAP_AC_MINUS      = @(144, 866)
# Crop boxes [x, y, w, h].
$CROP_TEMP = @(230, 350, 300, 140)  # Tuya current-temperature digits
$CROP_SET  = @(420, 690, 250, 140)  # Haier centered setpoint digits
# AC power-button blue sample box. ON means blue.
$AC_POWER_BOX = @(95, 2075, 80, 60) # [x, y, w, h]
$ACTIVE_CALIBRATION = $null

# Shortcut labels are generated from code points to keep this source ASCII-only.
$TEMP_SHORTCUT_LABEL = (-join ([char[]]@(0x6E29, 0x6E7F, 0x5EA6, 0x62A5, 0x8B66, 0x5668)))
$AC_SHORTCUT_LABEL   = (-join ([char[]]@(0x4E3B, 0x5367, 0x7A7A, 0x8C03)))

# Decision thresholds.
$SAFEBAND_LOW  = $SafebandLow   # [SAFEBAND_LOW, SAFEBAND_HIGH] => no action
$SAFEBAND_HIGH = $SafebandHigh
$SET_FLOOR     = $SetpointFloor
$SET_CEIL      = $SetpointCeiling
$LAST_SET_ACTION = $null

# ---------------------------------------------------------------------------
# Utilities
# ---------------------------------------------------------------------------
function Log([string]$msg) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg)
}

function Write-StartupInfo {
    Log '=== AVD AC regulator startup ==='
    Log ("AVD={0}; Serial={1}; Calibration={2}; Init={3}; ColdBoot={4}; MaxCycles={5}" -f $AvdName, $Serial, $Calibration, $Init.IsPresent, $ColdBoot.IsPresent, $MaxCycles)
    Log ("Interval: {0} min unified; unchanged threshold: {1} cycles" -f $IntervalMinutes, $UnchangedThresholdCycles)
    Log ("Safeband: [{0}, {1}] C" -f $SAFEBAND_LOW, $SAFEBAND_HIGH)
    Log ("Setpoint range: [{0}, {1}] C" -f $SET_FLOOR, $SET_CEIL)
    Log ("Paths: SDK={0}; WorkDir={1}" -f $Sdk, $WorkDir)
    Log ("Tools: adb={0}; emulator={1}; tesseract={2}" -f $ADB, $EMU, $TESS)
}

function Adb { & $ADB -s $Serial @args }

function Set-LastSetAction([double]$Temperature, [int]$Setpoint, [int]$CycleIndex) {
    $script:LAST_SET_ACTION = New-SetActionRecord -Temperature $Temperature -Setpoint $Setpoint -CycleIndex $CycleIndex
    Log ("  Recorded setpoint intervention: temp={0} C; setpoint={1} C; cycle={2}" -f $Temperature, $Setpoint, $CycleIndex)
}

function Clear-LastSetAction([string]$Reason) {
    $script:LAST_SET_ACTION = $null
    Log ("  Cleared setpoint intervention record: {0}" -f $Reason)
}

function Update-LastSetActionObservation([double]$Temperature, [int]$CycleIndex) {
    if ($null -eq $script:LAST_SET_ACTION) { return }
    $script:LAST_SET_ACTION.Temperature = $Temperature
    $script:LAST_SET_ACTION.CycleIndex = $CycleIndex
    Log ("  Temperature is moving toward safeband; reset retry threshold baseline: temp={0} C; setpoint={1} C; cycle={2}" -f $script:LAST_SET_ACTION.Temperature, $script:LAST_SET_ACTION.Setpoint, $script:LAST_SET_ACTION.CycleIndex)
}

function Invoke-Tap($pt) {
    $x = [int]$pt[0]
    $y = [int]$pt[1]
    Log "  Tap ($x,$y)"
    Adb shell input tap $x $y | Out-Null
}

function Set-EmulatorTopmost {
    # A minimized emulator window can pause or reduce rendering, causing blank or stale screenshots.
    # Keep the window restored, topmost, and foregrounded so rendering, screenshots, and taps work.
    $p = Get-Process -Name 'qemu*' -ErrorAction SilentlyContinue |
         Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
    if ($p) {
        $HWND_TOPMOST = [IntPtr]::new(-1); $SWP = (0x1 -bor 0x2)  # NOSIZE|NOMOVE
        [NativeWin]::ShowWindow($p.MainWindowHandle, 9)   | Out-Null   # SW_RESTORE
        [NativeWin]::SetWindowPos($p.MainWindowHandle, $HWND_TOPMOST, 0, 0, 0, 0, $SWP) | Out-Null
        [NativeWin]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
    }
}

function Test-DeviceOnline {
    [bool]((& $ADB devices 2>$null) -match "$Serial\s+device")
}

function Test-DeviceBooted {
    (Test-DeviceOnline) -and ((& $ADB -s $Serial shell getprop sys.boot_completed 2>$null | Out-String).Trim() -eq '1')
}

function Resolve-AvdName {
    if (-not (Test-Path $EMU)) { return }
    $avds = @(& $EMU -list-avds 2>$null | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($avds -contains $AvdName) { return }
    if ($AvdName -eq 'Medium_Phone' -and $avds.Count -eq 1) {
        $script:AvdName = $avds[0]
        Log "Default AVD Medium_Phone not found; using the only available AVD: $AvdName"
    }
}

function Get-DeviceSize {
    if (-not (Test-DeviceOnline)) { return $null }
    $sizeLine = (Adb shell wm size 2>$null | Out-String).Trim()
    $m = [regex]::Match($sizeLine, '(\d+)x(\d+)')
    if (-not $m.Success) { return $null }
    return @([int]$m.Groups[1].Value, [int]$m.Groups[2].Value)
}

function Use-Calibration([string]$profile) {
    switch ($profile) {
        '480x854' {
            $script:TAP_TEMP_SHORTCUT = @(147, 392)
            $script:TAP_AC_SHORTCUT   = @(240, 392)
            $script:TAP_AC_PLUS       = @(416, 392)
            $script:TAP_AC_MINUS      = @(64, 392)
            $script:CROP_TEMP         = @(100, 176, 140, 60)
            $script:CROP_SET          = @(214, 315, 58, 55)
            $script:AC_POWER_BOX      = @(42, 724, 36, 36)
            $script:ACTIVE_CALIBRATION = '480x854'
            $global:TAP_TEMP_SHORTCUT = @(147, 392)
            $global:TAP_AC_SHORTCUT   = @(240, 392)
            $global:TAP_AC_PLUS       = @(416, 392)
            $global:TAP_AC_MINUS      = @(64, 392)
            $global:CROP_TEMP         = @(100, 176, 140, 60)
            $global:CROP_SET          = @(214, 315, 58, 55)
            $global:AC_POWER_BOX      = @(42, 724, 36, 36)
            $global:ACTIVE_CALIBRATION = '480x854'
        }
        default {
            $script:TAP_TEMP_SHORTCUT = @(663, 1221)
            $script:TAP_AC_SHORTCUT   = @(910, 1221)
            $script:TAP_AC_PLUS       = @(936, 866)
            $script:TAP_AC_MINUS      = @(144, 866)
            $script:CROP_TEMP         = @(230, 350, 300, 140)
            $script:CROP_SET          = @(420, 690, 250, 140)
            $script:AC_POWER_BOX      = @(95, 2075, 80, 60)
            $script:ACTIVE_CALIBRATION = '1080x2400'
            $global:TAP_TEMP_SHORTCUT = @(663, 1221)
            $global:TAP_AC_SHORTCUT   = @(910, 1221)
            $global:TAP_AC_PLUS       = @(936, 866)
            $global:TAP_AC_MINUS      = @(144, 866)
            $global:CROP_TEMP         = @(230, 350, 300, 140)
            $global:CROP_SET          = @(420, 690, 250, 140)
            $global:AC_POWER_BOX      = @(95, 2075, 80, 60)
            $global:ACTIVE_CALIBRATION = '1080x2400'
        }
    }
    Log "Calibration: $($script:ACTIVE_CALIBRATION)"
}

function Select-Calibration {
    if ($Calibration -ne 'auto') { Use-Calibration $Calibration; return }
    $size = Get-DeviceSize
    if ($null -ne $size) {
        $profile = ('{0}x{1}' -f $size[0], $size[1])
        if ($profile -eq '480x854') { Use-Calibration '480x854'; return }
        if ($profile -eq '1080x2400') { Use-Calibration '1080x2400'; return }
        Log "  [warn] Unknown screen size $profile; using default 1080x2400 coordinates."
    }
    Use-Calibration '1080x2400'
}

# Ensure the emulator is online and fully booted. Tolerates transient disconnects and restarts dead emulators.
function Ensure-Device {
    for ($round = 0; $round -lt 3; $round++) {
        if (Test-DeviceBooted) { return $true }
        # Process is alive but offline: wait for transient disconnect recovery.
        if (Get-Process -Name 'qemu*' -ErrorAction SilentlyContinue) {
            for ($i = 0; $i -lt 6; $i++) {
                & $ADB -s $Serial wait-for-device 2>$null
                Start-Sleep -Seconds 5
                if (Test-DeviceBooted) { return $true }
            }
        }
        # Process is dead or long-unresponsive: restart for self-healing.
        Log '  [warn] Emulator exited or is unresponsive; restarting ...'
        Get-Process -Name 'qemu*' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
        try { Start-EmulatorAndWait } catch { Log "  Restart failed: $($_.Exception.Message)" }
    }
    return (Test-DeviceBooted)
}

function Get-Screenshot([string]$path) {
    # Write screencap on device, then adb pull it for binary safety. Validate PNG before returning.
    Set-EmulatorTopmost
    for ($try = 1; $try -le 3; $try++) {
        try {
            & $ADB -s $Serial shell screencap -p /sdcard/_shot.png 2>$null | Out-Null
            $oldEap = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try { & $ADB -s $Serial pull /sdcard/_shot.png $path 2>$null | Out-Null }
            finally { $ErrorActionPreference = $oldEap }
            if (Test-Path $path) {
                $fi = Get-Item $path
                if ($fi.Length -gt 10000) {
                    $sig = [System.IO.File]::ReadAllBytes($path)[0..3] -join ','
                    if ($sig -eq '137,80,78,71') { return $true }   # PNG magic
                }
            }
        } catch {
            Log "  Screenshot exception: $($_.Exception.Message)"
        }
        Log "  Screenshot failed (attempt $try); checking device and retrying ..."
        Ensure-Device | Out-Null
        Start-Sleep -Seconds 2
    }
    return $false
}

function Get-Focus {
    $dump = (Adb shell dumpsys window 2>$null | Out-String)
    $m = [regex]::Match($dump, 'mCurrentFocus=Window\{.*?\}', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if ($m.Success) { return $m.Value }
    return ($dump | Select-String 'mCurrentFocus' | Select-Object -First 1)
}

function Get-LauncherShortcutTap([string]$label, [int[]]$fallback) {
    $xmlPath = Join-Path $WorkDir 'launcher.xml'
    try {
        Adb shell uiautomator dump /sdcard/_launcher.xml 2>$null | Out-Null
        $oldEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & $ADB -s $Serial pull /sdcard/_launcher.xml $xmlPath 2>$null | Out-Null }
        finally { $ErrorActionPreference = $oldEap }
        if (-not (Test-Path $xmlPath)) { return $fallback }

        [xml]$xml = [System.IO.File]::ReadAllText($xmlPath, [System.Text.Encoding]::UTF8)
        foreach ($node in $xml.SelectNodes('//*')) {
            $text = $node.GetAttribute('text')
            $desc = $node.GetAttribute('content-desc')
            if ($text -ne $label -and $desc -ne $label) { continue }

            $bounds = $node.GetAttribute('bounds')
            $m = [regex]::Match($bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
            if ($m.Success) {
                $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
                $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
                Log "  Launcher shortcut '$label' -> ($x,$y)"
                return @($x, $y)
            }
        }
    } catch {
        Log "  [warn] Could not locate '$label' from launcher dump: $($_.Exception.Message)"
    }
    return $fallback
}

# Read a number from a screenshot crop. Digits are rendered graphics:
#   binarize -> find the large-digit band -> split glyphs by blank columns ->
#   stop at small upper glyphs such as the degree ring -> upscale -> OCR with multiple PSMs.
function Read-Number([string]$src, [int[]]$box) {
    $bmp = [System.Drawing.Bitmap]::FromFile($src)
    try {
        if ($box.Count -lt 4) { return $null }
        $x0 = [int]$box[0]; $y0 = [int]$box[1]; $w = [int]$box[2]; $h = [int]$box[3]
        if ($x0 -lt 0 -or $y0 -lt 0 -or $w -le 0 -or $h -le 0) { return $null }
        if ($x0 -ge $bmp.Width -or $y0 -ge $bmp.Height) { return $null }
        $w = [Math]::Min($w, $bmp.Width - $x0)
        $h = [Math]::Min($h, $bmp.Height - $y0)
        if ($w -le 0 -or $h -le 0) { return $null }
        $dark = New-Object 'bool[,]' $w, $h
        $col  = New-Object 'int[]' $w
        $rowHas = New-Object 'int[]' $h
        for ($cx = 0; $cx -lt $w; $cx++) {
            for ($cy = 0; $cy -lt $h; $cy++) {
                $c = $bmp.GetPixel($x0 + $cx, $y0 + $cy)
                if (($c.R + $c.G + $c.B) -lt 320) { $dark[$cx, $cy] = $true; $col[$cx]++; $rowHas[$cy]++ }
            }
        }
        $rowTop = -1; $rowBot = -1
        for ($cy = 0; $cy -lt $h; $cy++) { if ($rowHas[$cy] -gt 3) { if ($rowTop -lt 0) { $rowTop = $cy }; $rowBot = $cy } }
        if ($rowTop -lt 0) { return $null }
        $bandH = $rowBot - $rowTop

        # Split glyphs by blank columns.
        $blobs = @(); $in = $false; $bs = 0; $be = 0
        for ($cx = 0; $cx -lt $w; $cx++) {
            if ($col[$cx] -ge 2) { if (-not $in) { $in = $true; $bs = $cx }; $be = $cx }
            else { if ($in) { $blobs += , @($bs, $be); $in = $false } }
        }
        if ($in) { $blobs += , @($bs, $be) }
        if ($blobs.Count -eq 0) { return $null }

        # Keep digit glyphs left-to-right; stop at the small upper degree ring.
        $keepStart = $blobs[0][0]; $keepEnd = -1
        foreach ($bl in $blobs) {
            $mx = -1
            for ($cx = $bl[0]; $cx -le $bl[1]; $cx++) { for ($cy = $rowTop; $cy -le $rowBot; $cy++) { if ($dark[$cx, $cy] -and $cy -gt $mx) { $mx = $cy } } }
            if ($mx -lt ($rowTop + 0.6 * $bandH)) { break }   # degree ring or unit start
            $keepEnd = $bl[1]
        }
        if ($keepEnd -lt 0) { return $null }

        $pad = 8
        $sx = [Math]::Max(0, $keepStart - $pad); $sy = [Math]::Max(0, $rowTop - $pad)
        $sw = [Math]::Min($w - $sx, ($keepEnd - $keepStart) + 2 * $pad)
        $sh = [Math]::Min($h - $sy, $bandH + 2 * $pad)
        $rect = New-Object System.Drawing.Rectangle(($x0 + $sx), ($y0 + $sy), $sw, $sh)
        $crop = $bmp.Clone($rect, $bmp.PixelFormat)
        $scale = 5; $nw = [int]($sw * $scale); $nh = [int]($sh * $scale)
        $big = New-Object System.Drawing.Bitmap($nw, $nh)
        $g = [System.Drawing.Graphics]::FromImage($big)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.DrawImage($crop, 0, 0, $nw, $nh)
        $dst = Join-Path $WorkDir 'num.png'
        $big.Save($dst, [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose(); $big.Dispose(); $crop.Dispose()

        $votes = @{}
        foreach ($psm in @(7, 8, 13)) {
            $t = ((& $TESS $dst stdout --psm $psm -c tessedit_char_whitelist=0123456789. 2>$null) -join '').Trim()
            $m = [regex]::Match($t, '\d+(\.\d+)?')
            if ($m.Success) { $k = $m.Value; if ($votes[$k]) { $votes[$k]++ } else { $votes[$k] = 1 } }
        }
        if ($votes.Count -eq 0) { return $null }
        return [double](($votes.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 1).Key)
    } finally { $bmp.Dispose() }
}

function Read-Setpoint([string]$shot) {
    $value = Read-Number -src $shot -box $script:CROP_SET
    if ($null -eq $value) { return $null }
    if ($value -ge $APP_SETPOINT_MIN -and $value -le $APP_SETPOINT_MAX) { return [int][Math]::Round($value) }
    Log "  [warn] Ignoring invalid setpoint OCR: $value"
    return $null
}

function Test-AcOn([string]$shot) {
    # Sample blue-pixel ratio in the power button area. Blue means AC is on.
    $bmp = [System.Drawing.Bitmap]::FromFile($shot)
    try {
        if ($script:AC_POWER_BOX.Count -lt 4) { return $false }
        $x0 = [int]$script:AC_POWER_BOX[0]; $y0 = [int]$script:AC_POWER_BOX[1]
        $x1 = [Math]::Min($bmp.Width,  $x0 + [int]$script:AC_POWER_BOX[2])
        $y1 = [Math]::Min($bmp.Height, $y0 + [int]$script:AC_POWER_BOX[3])
        if ($x0 -lt 0 -or $y0 -lt 0 -or $x0 -ge $x1 -or $y0 -ge $y1) { return $false }
        $blue = 0; $total = 0
        for ($x = $x0; $x -lt $x1; $x += 4) {
            for ($y = $y0; $y -lt $y1; $y += 4) {
                $c = $bmp.GetPixel($x, $y); $total++
                if ($c.B -gt 150 -and ($c.B - $c.R) -gt 60 -and $c.R -lt 120) { $blue++ }
            }
        }
        return (($total -gt 0) -and (($blue / $total) -gt 0.15))
    } finally { $bmp.Dispose() }
}

# ---------------------------------------------------------------------------
# Step 1: kill all AVDs and cold boot.
# ---------------------------------------------------------------------------
function Start-EmulatorAndWait {
    Log "Starting $AvdName (visible window, no-snapshot) ..."
    $log = Join-Path $WorkDir 'emu.log'
    # This host may be a Hyper-V VM without physical GPU or real display session:
    #   -gpu swiftshader_indirect: software rendering; host GPU mode is unstable without a physical GPU.
    #   -no-audio: avoids missing-audio-device issues in VMs.
    Start-Process -FilePath $EMU `
        -ArgumentList @('-avd', $AvdName, '-no-snapshot-load', '-no-snapshot-save',
                        '-no-boot-anim', '-gpu', 'swiftshader_indirect', '-no-audio') `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" | Out-Null

    & $ADB -s $Serial wait-for-device
    $booted = ''
    for ($i = 0; $i -lt 60; $i++) {
        $booted = (Adb shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($booted -eq '1') { break }
        Start-Sleep -Seconds 5
    }
    if ($booted -ne '1') { throw 'Boot timeout: boot_completed != 1' }
    Start-Sleep -Seconds 5
    Set-EmulatorTopmost
    Adb shell wm dismiss-keyguard 2>$null | Out-Null
    Adb shell input keyevent KEYCODE_HOME | Out-Null
    Start-Sleep -Seconds 3
    Set-EmulatorTopmost
    Log 'Step 1: launcher/home screen is ready.'
}

# Cold-boot mode: kill all AVDs before starting a fresh emulator.
function Invoke-ColdBoot {
    Log 'Step 1: killing all AVDs ...'
    $devices = & $ADB devices | Select-String '^emulator-\d+' | ForEach-Object { ($_ -split '\s+')[0] }
    foreach ($d in $devices) { & $ADB -s $d emu kill 2>$null | Out-Null }
    for ($i = 0; $i -lt 20; $i++) {
        if (-not (& $ADB devices | Select-String '^emulator-\d+\s+device')) { break }
        Start-Sleep -Seconds 2
    }
    Get-Process -Name 'qemu*' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    Start-EmulatorAndWait
}

# Default mode: reuse a booted emulator when possible; otherwise start one. Crashes self-heal.
function Ensure-EmulatorRunning {
    if (Test-DeviceBooted) { Set-EmulatorTopmost; Log 'Step 1: reusing running emulator; skipping cold boot.'; return }
    Log 'Step 1: emulator is not running; starting one ...'
    if (-not (Ensure-Device)) { throw 'Could not start emulator' }
}

# ---------------------------------------------------------------------------
# Step 2: open Tuya temperature device and read temperature.
# ---------------------------------------------------------------------------
function Wait-LauncherReady {
    for ($i = 0; $i -lt 15; $i++) {
        if ((Get-Focus) -match 'launcher') { Start-Sleep -Seconds 1; return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

# Open a home-screen shortcut and confirm that the target app reaches the foreground.
# One call performs one attempt; outer steps control total retry count.
function Open-Shortcut([int[]]$tap, [string]$pkgPattern, [string]$label, [int]$attempt, [int]$maxAttempts) {
    if (-not (Ensure-Device)) { Log "  Device unavailable; retrying ..."; return $false }   # restart first if emulator crashed mid-step
    Adb shell input keyevent KEYCODE_HOME | Out-Null
    if (-not (Wait-LauncherReady)) { Log '  [warn] Launcher did not become foreground in time; tapping anyway.' }
    Set-EmulatorTopmost
    Start-Sleep -Seconds 2
    $actualTap = Get-LauncherShortcutTap $label $tap
    Invoke-Tap $actualTap
    for ($i = 0; $i -lt 12; $i++) {
        Start-Sleep -Seconds 2
        $focus = Get-Focus
        if ($focus -match $pkgPattern) { return $true }
    }
    Log "  Current foreground: $(Get-Focus)"
    Log "  Failed to open '$label' (attempt $attempt/$maxAttempts)"
    return $false
}

# ---------------------------------------------------------------------------
# Step 2: open Tuya temperature device and read temperature.
# ---------------------------------------------------------------------------
function Read-Temperature {
    $shot = Join-Path $WorkDir 'temp.png'
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Log "Step 2: opening Tuya temperature shortcut (attempt $attempt/3) ..."
        if (-not (Open-Shortcut $script:TAP_TEMP_SHORTCUT 'com\.tuya' $script:TEMP_SHORTCUT_LABEL $attempt 3)) { continue }
        # Mini-app content renders asynchronously; poll until the temperature card is readable.
        for ($i = 0; $i -lt 20; $i++) {          # about 100 seconds
            Start-Sleep -Seconds 5
            if (-not (Get-Screenshot $shot)) { continue }
            $t = Read-Number -src $shot -box $script:CROP_TEMP
            if ($null -ne $t -and $t -ge 5 -and $t -le 45) {
                Log ("Step 2: current temperature = {0} C" -f $t)
                return $t
            }
            if (($i + 1) % 4 -eq 0) { Log '  Waiting for temperature card to render ...' }
        }
        Log '  Temperature card did not render; reopening mini-app ...'
    }
    throw 'Step 2: failed to read temperature after retries.'
}

# ---------------------------------------------------------------------------
# Step 4: open Haier bedroom AC.
# ---------------------------------------------------------------------------
function Open-Ac {
    Log 'Step 4: opening Haier bedroom AC ...'
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        if (Open-Shortcut $script:TAP_AC_SHORTCUT 'com\.haier' $script:AC_SHORTCUT_LABEL $attempt 3) { break }
        if ($attempt -eq 3) { throw 'Step 4: could not open Haier bedroom AC' }
    }
    $shot = Join-Path $WorkDir 'ac.png'
    # Wait for the control panel; readable setpoint means the screen is ready.
    for ($i = 0; $i -lt 12; $i++) {
        Start-Sleep -Seconds 4
        if (-not (Get-Screenshot $shot)) { continue }
        if ($null -ne (Read-Setpoint $shot)) { return $shot }
    }
    if (-not (Get-Screenshot $shot)) { throw 'Step 4: failed to capture AC screen' }
    return $shot
}

function Move-SetpointToTarget([int]$CurrentSetpoint, [int]$TargetSetpoint, [string]$Shot) {
    if ($CurrentSetpoint -eq $TargetSetpoint) { return $CurrentSetpoint }

    $tapPoint = $(if ($TargetSetpoint -gt $CurrentSetpoint) { $script:TAP_AC_PLUS } else { $script:TAP_AC_MINUS })
    $steps = [Math]::Abs($TargetSetpoint - $CurrentSetpoint)
    for ($i = 0; $i -lt $steps; $i++) {
        Invoke-Tap $tapPoint
        Start-Sleep -Seconds 2
    }

    Get-Screenshot $Shot | Out-Null
    return (Read-Setpoint $Shot)
}

function Invoke-CriticalZoneRecovery([double]$Temperature) {
    $recordedSetpoint = [int]$script:LAST_SET_ACTION.Setpoint

    Log ("Step 3: {0} C is in safeband [{1}, {2}], but last setpoint {3} C is critical -> checking AC for recovery." -f $Temperature, $SAFEBAND_LOW, $SAFEBAND_HIGH, $recordedSetpoint)
    $shot = Open-Ac
    if (-not (Test-AcOn $shot)) {
        Log 'Step 4: AC is powered off -> critical-zone recovery skipped.'
        return
    }

    $sp = Read-Setpoint $shot
    if ($null -eq $sp) {
        Log 'Step 4: could not read AC setpoint -> critical-zone recovery skipped.'
        return
    }

    if (-not (Test-CriticalSetpoint -Setpoint ([int]$sp) -SetpointFloor $SET_FLOOR -SetpointCeiling $SET_CEIL)) {
        Log ("Step 4: current setpoint {0} C is already non-critical -> no recovery tap needed." -f $sp)
        Clear-LastSetAction 'AC setpoint is already non-critical while temperature is in safeband.'
        return
    }

    $target = Get-NearestNonCriticalSetpoint -Setpoint ([int]$sp) -SetpointFloor $SET_FLOOR -SetpointCeiling $SET_CEIL
    $new = Move-SetpointToTarget ([int]$sp) $target $shot
    Log ("Step 4: safeband critical-zone recovery ({0} -> {1}) C; confirmed={2}" -f $sp, $target, $new)
    if ($null -ne $new -and [int]$new -eq $target) {
        Clear-LastSetAction ("Recovered critical setpoint to {0} C." -f $target)
    } else {
        Log ("  [warn] Critical-zone recovery target {0} C was not confirmed; preserving intervention record." -f $target)
    }
}

# ---------------------------------------------------------------------------
# One full cycle (steps 2-6).
# ---------------------------------------------------------------------------
function Invoke-Cycle([int]$CycleIndex) {
    $t = Read-Temperature
    $decision = Get-CycleDecision -Temperature $t -LastSetAction $script:LAST_SET_ACTION -CycleIndex $CycleIndex -SafebandLow $SAFEBAND_LOW -SafebandHigh $SAFEBAND_HIGH -SetpointFloor $SET_FLOOR -SetpointCeiling $SET_CEIL -UnchangedThresholdCycles $UnchangedThresholdCycles

    if ($decision.Action -eq 'NoAction' -and $decision.TemperatureSide -eq 'safe') {
        Log ("Step 3: {0} C is in safeband [{1}, {2}] -> no action." -f $t, $SAFEBAND_LOW, $SAFEBAND_HIGH)
        return
    }

    if ($decision.Action -eq 'RecoverCritical') {
        Invoke-CriticalZoneRecovery $t
        return
    }

    if ($decision.Action -eq 'UpdateObservation') {
        Update-LastSetActionObservation $t $CycleIndex
        return
    }
    if (-not $decision.ShouldOpenAc) {
        if ($null -ne $script:LAST_SET_ACTION) {
            Log ("Step 3: {0} C is out of safeband, but not worsening and same-temperature threshold is not due. Last intervention: temp={1} C; setpoint={2} C; cycle={3}." -f $t, $script:LAST_SET_ACTION.Temperature, $script:LAST_SET_ACTION.Setpoint, $script:LAST_SET_ACTION.CycleIndex)
        } else {
            Log ("Step 3: {0} C is out of safeband, but no setpoint intervention is due." -f $t)
        }
        return
    }

    # Step 4: open AC and detect power state.
    $shot = Open-Ac
    if (-not (Test-AcOn $shot)) {
        Log 'Step 4: AC is powered off -> no setpoint intervention occurred.'
        return
    }
    $sp = Read-Setpoint $shot
    if ($null -eq $sp) { throw 'Step 4: could not read AC setpoint' }
    Log ("Step 4: AC is on; current setpoint = {0} C" -f $sp)

    if ($t -lt $SAFEBAND_LOW) {
        # Step 5: temperature below safeband -> setpoint +1.
        if ($null -ne $sp -and $sp -ge $SET_CEIL) {
            Log ("Step 5: already at ceiling {0} C -> recording intervention gate." -f $SET_CEIL)
            Set-LastSetAction $t ([int]$sp) $CycleIndex
        } else {
            $target = [int][Math]::Min($sp + 1, $SET_CEIL)
            Invoke-Tap $script:TAP_AC_PLUS
            Start-Sleep -Seconds 2
            Get-Screenshot $shot | Out-Null
            $new = Read-Setpoint $shot
            Log ("Step 5: temperature < {0} -> setpoint +1 ({1} -> {2}) C" -f $SAFEBAND_LOW, $sp, $new)
            if ($null -eq $new) {
                Log ("  [warn] Could not confirm adjusted setpoint; using intended target {0} C for intervention record." -f $target)
                $new = $target
            }
            Set-LastSetAction $t ([int]$new) $CycleIndex
        }
    }
    elseif ($t -gt $SAFEBAND_HIGH) {
        # Step 6: temperature above safeband -> setpoint -1.
        if ($null -ne $sp -and $sp -gt $SET_FLOOR) {
            $target = [int][Math]::Max($sp - 1, $SET_FLOOR)
            Invoke-Tap $script:TAP_AC_MINUS
            Start-Sleep -Seconds 2
            Get-Screenshot $shot | Out-Null
            $new = Read-Setpoint $shot
            Log ("Step 6: temperature > {0} -> setpoint -1 ({1} -> {2}) C" -f $SAFEBAND_HIGH, $sp, $new)
            if ($null -eq $new) {
                Log ("  [warn] Could not confirm adjusted setpoint; using intended target {0} C for intervention record." -f $target)
                $new = $target
            }
            Set-LastSetAction $t ([int]$new) $CycleIndex
        } else {
            Log ("Step 6: already at floor {0} C -> recording intervention gate." -f $SET_FLOOR)
            Set-LastSetAction $t ([int]$sp) $CycleIndex
        }
    }
}

# ---------------------------------------------------------------------------
# -Init: prepare the automatable part of the runtime environment.
#   Automated: SDK checks, tesseract+eng install, AVD GPU mode fix, target app checks.
#   Manual (see repo-root AGENTS.md): create AVD, install/pair Tuya/Haier apps, place shortcuts, calibrate if needed.
# ---------------------------------------------------------------------------
function Test-Cmd([string]$n) { [bool](Get-Command $n -ErrorAction SilentlyContinue) }

function Invoke-Bootstrap {
    Resolve-AvdName
    Log '=== -Init: preparing runtime environment ==='
    $todo = @()

    Log '[1/4] Android SDK (adb + emulator) ...'
    if (Test-Path $ADB) { Log "  OK  adb: $ADB" } else { Log "  MISS adb: $ADB"; $todo += 'Install Android SDK Platform-Tools, or pass the SDK path with -Sdk.' }
    if (Test-Path $EMU) { Log "  OK  emulator: $EMU" } else { Log "  MISS emulator: $EMU"; $todo += 'Install Android SDK Emulator.' }

    Log '[2/4] tesseract OCR + eng language data ...'
    if (-not (Test-Path $TESS) -and -not (Test-Cmd tesseract)) {
        Log '  tesseract not detected; attempting automatic install ...'
        if (Test-Cmd scoop) { scoop install tesseract }
        elseif (Test-Cmd winget) { winget install -e --id tesseract-ocr.tesseract --accept-source-agreements --accept-package-agreements }
        else { $todo += 'Install tesseract manually; scoop/winget was not found: https://github.com/UB-Mannheim/tesseract/wiki' }
        $script:TESS = (Get-Command tesseract -ErrorAction SilentlyContinue).Source
        if (-not $script:TESS) { $script:TESS = Join-Path $env:USERPROFILE 'scoop\shims\tesseract.exe' }
    }
    if (Test-Path $TESS) {
        Log "  OK  tesseract: $TESS"
        $langs = (& $TESS --list-langs 2>&1) -join "`n"
        if ($langs -match '(?m)^eng\s*$') { Log '  OK  eng.traineddata' }
        else {
            $m = [regex]::Match($langs, 'in "([^"]+)"')
            if ($m.Success) {
                $dir = $m.Groups[1].Value.TrimEnd('/', '\')
                Log "  Downloading eng.traineddata -> $dir ..."
                try {
                    Invoke-WebRequest 'https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata' -OutFile (Join-Path $dir 'eng.traineddata') -UseBasicParsing -TimeoutSec 90
                    Log '  OK  eng.traineddata installed'
                } catch { $todo += "Failed to download eng.traineddata; place it in $dir manually: $($_.Exception.Message)" }
            } else { $todo += 'Could not locate tessdata directory for eng.traineddata.' }
        }
    } else { Log '  MISS tesseract is still unavailable'; $todo += 'tesseract install failed; install it manually.' }

    Log "[3/4] AVD '$AvdName' + GPU mode (software rendering required for VM/no-GPU hosts) ..."
    if (Test-Path $EMU) {
        $avds = & $EMU -list-avds 2>$null
        if ($avds -contains $AvdName) {
            Log "  OK  AVD exists: $AvdName"
            $cfg = Join-Path $env:USERPROFILE ".android\avd\$AvdName.avd\config.ini"
            if (Test-Path $cfg) {
                $c = Get-Content $cfg
                if ($c -match '(?m)^hw\.gpu\.mode\s*=\s*(host|auto)\s*$') {
                    Copy-Item $cfg "$cfg.bak" -Force
                    ($c -replace '(?m)^hw\.gpu\.mode\s*=\s*(host|auto)\s*$', 'hw.gpu.mode = swiftshader_indirect') | Set-Content $cfg -Encoding ASCII
                    Log '  OK  hw.gpu.mode: host/auto -> swiftshader_indirect (backed up config.ini.bak)'
                } else { Log '  OK  GPU mode is already not host/auto' }
            }
        } else {
            Log "  MISS AVD '$AvdName' not found"
            $todo += "Create the AVD (see AGENTS.md section 3): avdmanager create avd -n $AvdName -k `"system-images;android-34;google_apis;x86_64`" -d pixel_5"
        }
    }

    Log '[4/4] target apps (requires online emulator) ...'
    if ((& $ADB devices 2>$null) -match "$Serial\s+device") {
        $pkgs = & $ADB -s $Serial shell pm list packages 2>$null
        foreach ($p in @('com.tuya.smartiot', 'com.haier.uhome.uplus')) {
            if ($pkgs -match [regex]::Escape($p)) { Log "  OK  $p" }
            else { Log "  MISS $p"; $todo += "Install and configure $p in the AVD, then pin its shortcut on the home screen (see AGENTS.md section 4)." }
        }
    } else { Log '  Skipped because emulator is offline. After boot, confirm both target apps are installed and have home-screen shortcuts.' }

    Log ''
    if ($todo.Count -eq 0) {
        Log '=== -Init complete: core tools are ready. You can run: pwsh -File .\scripts\avd-ac-regulator.ps1 -MaxCycles 1 ==='
    } else {
        Log '=== -Init still needs these manual actions (see repo-root AGENTS.md): ==='
        $i = 1; foreach ($it in $todo) { Log ("  {0}. {1}" -f $i, $it); $i++ }
    }
}

# ---------------------------------------------------------------------------
# Main loop
# ---------------------------------------------------------------------------
Write-StartupInfo
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
Resolve-AvdName
if ($Init) { Invoke-Bootstrap; return }
if (-not (Test-Path $ADB))  { throw "adb not found: $ADB. Run first: pwsh -File .\scripts\avd-ac-regulator.ps1 -Init" }
if (-not (Test-Path $EMU))  { throw "emulator not found: $EMU. Run first: -Init" }
if (-not (Test-Path $TESS)) { throw "tesseract not found: $TESS. Run -Init first to install it automatically." }

$cycle = 0
Log ("Mode: {0}" -f $(if ($ColdBoot) { 'cold boot every cycle (-ColdBoot)' } else { 'reuse running emulator (default, no cold boot)' }))
while ($true) {
    $cycle++
    Log "========== Cycle #$cycle =========="
    try {
        if ($ColdBoot) { Invoke-ColdBoot } else { Ensure-EmulatorRunning }
        Select-Calibration
        Invoke-Cycle $cycle
        Log "Cycle #$cycle completed."
    } catch {
        Log "Cycle #$cycle failed: $($_.Exception.Message)"
    }
    if ($MaxCycles -gt 0 -and $cycle -ge $MaxCycles) { Log "Reached MaxCycles=$MaxCycles; exiting."; break }
    Log "Waiting $IntervalMinutes minutes before next cycle ..."
    Start-Sleep -Seconds ($IntervalMinutes * 60)
}
