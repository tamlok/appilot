<#
.SYNOPSIS
  AVD 空调自动调节循环 (standalone — 与 appilot 应用无关 / does NOT use the appilot app).

.DESCRIPTION
  每隔 N 分钟循环执行一次完整周期：
    1. 确保模拟器在运行（默认：复用运行中的模拟器，未运行才启动一次——更快）。
       加 -ColdBoot 则每周期先关闭所有 AVD 再冷启动（无头/软件渲染/禁用快照）。
       等待系统完全进入桌面。
    2. 打开桌面快捷方式「温湿度报警器」(Tuya)，截图并用 tesseract OCR 读取当前温度。
    3. 若 24.7 < 温度 <= 25：不做任何操作，完成。
    4. 打开桌面快捷方式「主卧空调」(Haier)；若空调处于关机状态：完成。
    5. 若温度 <= 24.7：设定温度 +1（上限 30）。
    6. 若温度 > 25：设定温度 -1（下限 26）。

  温度与设定温度都是「绘制图形」(非文本节点)，因此用 截图 -> 裁剪 -> 放大 -> tesseract 识别。
  空调开/关 通过电源按钮是否为蓝色判定。

.NOTES
  依赖: Android SDK (emulator + adb)、tesseract (含 eng.traineddata)。
  仅用 adb/emulator/tesseract，不依赖本仓库任何代码。
  坐标基于 1080x2400 分辨率的 Medium_Phone AVD；换设备需重新标定。
#>

[CmdletBinding()]
param(
    [string]$AvdName        = 'Medium_Phone',
    [int]   $IntervalMinutes = 10,
    [string]$Serial          = 'emulator-5554',
    [string]$Sdk             = "$env:LOCALAPPDATA\Android\Sdk",
    [int]   $MaxCycles       = 0,   # 0 = 无限循环
    [string]$WorkDir         = "$env:LOCALAPPDATA\Temp\avd-ac-regulator",
    [switch]$Init,           # 仅准备环境(装 tesseract/eng、修正 AVD GPU 模式、体检)后退出，不跑循环
    [switch]$ColdBoot        # 每周期都关闭所有 AVD 再冷启动；默认关闭=复用运行中的模拟器(更快)
)

$ErrorActionPreference = 'Stop'
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
# 配置 / Calibration (1080x2400)
# ---------------------------------------------------------------------------
$ADB = Join-Path $Sdk 'platform-tools\adb.exe'
$EMU = Join-Path $Sdk 'emulator\emulator.exe'
$TESS = (Get-Command tesseract -ErrorAction SilentlyContinue).Source
if (-not $TESS) { $TESS = Join-Path $env:USERPROFILE 'scoop\shims\tesseract.exe' }

# 桌面快捷方式中心点
$TAP_TEMP_SHORTCUT = @(663, 1221)   # 温湿度报警器
$TAP_AC_SHORTCUT   = @(910, 1221)   # 主卧空调
# 空调设定温度 +/- 按钮
$TAP_AC_PLUS       = @(936, 866)
$TAP_AC_MINUS      = @(144, 866)
# 裁剪区域 [x, y, w, h]
$CROP_TEMP = @(230, 350, 300, 140)  # Tuya 当前温度大数字
$CROP_SET  = @(420, 690, 250, 140)  # Haier 中央设定温度
# 空调电源按钮蓝色采样区 (ON=蓝色)
$AC_POWER_BOX = @(95, 2075, 80, 60) # [x, y, w, h]

# 决策阈值
$DEADBAND_LOW  = 24.7   # (DEADBAND_LOW, DEADBAND_HIGH] => 不动作
$DEADBAND_HIGH = 25.0
$SET_FLOOR     = 26     # 制冷设定下限
$SET_CEIL      = 30     # 硬件上限

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
function Log([string]$msg) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg)
}

function Adb { & $ADB -s $Serial @args }

function Invoke-Tap($pt) { Adb shell input tap $pt[0] $pt[1] | Out-Null }

function Set-EmulatorTopmost {
    # 模拟器窗口最小化时会暂停/降低渲染 -> 截图得到空白/陈旧帧。
    # 保持窗口 还原 + 置顶 + 前台，确保渲染持续、截图有效、点击可达。
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

# 确保模拟器在线且启动完成；容忍瞬时断连，若进程已退出则自动重启（自愈）。返回 $true/$false。
function Ensure-Device {
    for ($round = 0; $round -lt 3; $round++) {
        if (Test-DeviceBooted) { return $true }
        # 进程还在但离线：等待瞬时断连恢复
        if (Get-Process -Name 'qemu*' -ErrorAction SilentlyContinue) {
            for ($i = 0; $i -lt 6; $i++) {
                & $ADB -s $Serial wait-for-device 2>$null
                Start-Sleep -Seconds 5
                if (Test-DeviceBooted) { return $true }
            }
        }
        # 进程已死或长期无响应 -> 重启（自愈）
        Log '  [warn] 模拟器已退出/无响应，正在重启 ...'
        Get-Process -Name 'qemu*' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
        try { Start-EmulatorAndWait } catch { Log "  重启失败: $($_.Exception.Message)" }
    }
    return (Test-DeviceBooted)
}

function Get-Screenshot([string]$path) {
    # screencap 写到设备再 adb pull（二进制安全）。校验 PNG 有效，失败重试。
    Set-EmulatorTopmost
    for ($try = 1; $try -le 4; $try++) {
        try {
            & $ADB -s $Serial shell screencap -p /sdcard/_shot.png 2>$null | Out-Null
            & $ADB -s $Serial pull /sdcard/_shot.png $path 2>$null | Out-Null
            if (Test-Path $path) {
                $fi = Get-Item $path
                if ($fi.Length -gt 10000) {
                    $sig = [System.IO.File]::ReadAllBytes($path)[0..3] -join ','
                    if ($sig -eq '137,80,78,71') { return $true }   # PNG magic
                }
            }
        } catch { }
        Log "  截图失败(第 $try 次)，检查设备并重试 ..."
        Ensure-Device | Out-Null
        Start-Sleep -Seconds 2
    }
    return $false
}

function Get-Focus {
    (Adb shell dumpsys window 2>$null | Select-String 'mCurrentFocus' | Select-Object -First 1)
}

# 从截图指定区域读取数字（温度/设定温度）。数字为绘制图形：
#   二值化 -> 定位大数字行带(排除灰色小标签) -> 按空列切分字形 ->
#   遇到「小而靠上」的字形(℃ 的度数环)即停止(丢弃 ℃/单位) -> 放大 -> 多 PSM 投票。
function Read-Number([string]$src, [int[]]$box) {
    $bmp = [System.Drawing.Bitmap]::FromFile($src)
    try {
        $x0 = $box[0]; $y0 = $box[1]; $w = $box[2]; $h = $box[3]
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

        # 按空列切分字形（任意非暗列即分隔）
        $blobs = @(); $in = $false; $bs = 0; $be = 0
        for ($cx = 0; $cx -lt $w; $cx++) {
            if ($col[$cx] -ge 2) { if (-not $in) { $in = $true; $bs = $cx }; $be = $cx }
            else { if ($in) { $blobs += , @($bs, $be); $in = $false } }
        }
        if ($in) { $blobs += , @($bs, $be) }
        if ($blobs.Count -eq 0) { return $null }

        # 从左到右保留数字字形；遇到「小而靠上」的度数环(℃)即停止
        $keepStart = $blobs[0][0]; $keepEnd = -1
        foreach ($bl in $blobs) {
            $mx = -1
            for ($cx = $bl[0]; $cx -le $bl[1]; $cx++) { for ($cy = $rowTop; $cy -le $rowBot; $cy++) { if ($dark[$cx, $cy] -and $cy -gt $mx) { $mx = $cy } } }
            if ($mx -lt ($rowTop + 0.6 * $bandH)) { break }   # 度数环 -> ℃ 起点，停止
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

function Test-AcOn([string]$shot) {
    # 采样电源按钮区域蓝色像素占比；蓝色 => 已开机
    $bmp = [System.Drawing.Bitmap]::FromFile($shot)
    try {
        $blue = 0; $total = 0
        for ($x = $AC_POWER_BOX[0]; $x -lt $AC_POWER_BOX[0] + $AC_POWER_BOX[2]; $x += 4) {
            for ($y = $AC_POWER_BOX[1]; $y -lt $AC_POWER_BOX[1] + $AC_POWER_BOX[3]; $y += 4) {
                $c = $bmp.GetPixel($x, $y); $total++
                if ($c.B -gt 150 -and ($c.B - $c.R) -gt 60 -and $c.R -lt 120) { $blue++ }
            }
        }
        return (($total -gt 0) -and (($blue / $total) -gt 0.15))
    } finally { $bmp.Dispose() }
}

# ---------------------------------------------------------------------------
# 步骤 1：关闭所有 AVD + 冷启动
# ---------------------------------------------------------------------------
function Start-EmulatorAndWait {
    Log "启动 $AvdName (headless, no-snapshot) ..."
    $log = Join-Path $WorkDir 'emu.log'
    # 本机是 Hyper-V 虚拟机、无物理 GPU、无真实显示会话，因此：
    #   -no-window            : 无头模式。GUI 窗口渲染(UpdateLayeredWindowIndirect)在无显示设备的
    #                           VM 上会失败并使模拟器崩溃退出；无头模式彻底规避。截图/点击不受影响。
    #   -gpu swiftshader_indirect : 软件渲染（无物理 GPU，host 模式下 Vulkan 不稳定）。
    #   -no-audio             : 虚拟机无音频设备。
    #   如果你在有真实显示器/GPU 的物理机上运行且想看到窗口，可删掉 -no-window。
    Start-Process -FilePath $EMU `
        -ArgumentList @('-avd', $AvdName, '-no-snapshot-load', '-no-snapshot-save',
                        '-no-window', '-no-boot-anim', '-gpu', 'swiftshader_indirect', '-no-audio') `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden | Out-Null

    & $ADB -s $Serial wait-for-device
    $booted = ''
    for ($i = 0; $i -lt 60; $i++) {
        $booted = (Adb shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($booted -eq '1') { break }
        Start-Sleep -Seconds 5
    }
    if ($booted -ne '1') { throw '启动超时：boot_completed != 1' }
    Start-Sleep -Seconds 5
    Set-EmulatorTopmost
    Adb shell wm dismiss-keyguard 2>$null | Out-Null
    Adb shell input keyevent KEYCODE_HOME | Out-Null
    Start-Sleep -Seconds 3
    Set-EmulatorTopmost
    Log 'Step 1: 已进入桌面.'
}

# 默认模式：每周期关闭所有 AVD 再冷启动（符合原始需求）
function Invoke-ColdBoot {
    Log 'Step 1: 关闭所有 AVD ...'
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

# -NoColdBoot 模式：模拟器已在线且启动完成则复用（更快）；否则启动一次；崩溃则自动重启。
function Ensure-EmulatorRunning {
    if (Test-DeviceBooted) { Set-EmulatorTopmost; Log 'Step 1: 复用运行中的模拟器（跳过冷启动）.'; return }
    Log 'Step 1: 模拟器未运行，启动一次 ...'
    if (-not (Ensure-Device)) { throw '无法启动模拟器' }
}

# ---------------------------------------------------------------------------
# 步骤 2：打开温湿度报警器并读取温度
# ---------------------------------------------------------------------------
function Wait-LauncherReady {
    for ($i = 0; $i -lt 15; $i++) {
        if ((Get-Focus) -match 'launcher') { Start-Sleep -Seconds 1; return }
        Start-Sleep -Seconds 2
    }
}

# 打开桌面快捷方式并确认目标 app 进入前台；失败自动重开。返回 $true/$false。
function Open-Shortcut([int[]]$tap, [string]$pkgPattern, [string]$label) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        if (-not (Ensure-Device)) { Log "  设备不可用，重试 ..."; continue }   # 中途崩溃则先重启
        Adb shell input keyevent KEYCODE_HOME | Out-Null
        Wait-LauncherReady
        Set-EmulatorTopmost
        Start-Sleep -Seconds 2
        Invoke-Tap $tap
        for ($i = 0; $i -lt 10; $i++) {
            Start-Sleep -Seconds 3
            if ((Get-Focus) -match $pkgPattern) { return $true }
        }
        Log "  未能打开「$label」(第 $attempt/3 次)，重试 ..."
    }
    return $false
}

# ---------------------------------------------------------------------------
# 步骤 2：打开温湿度报警器并读取温度
# ---------------------------------------------------------------------------
function Read-Temperature {
    $shot = Join-Path $WorkDir 'temp.png'
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Log "Step 2: 打开「温湿度报警器」(第 $attempt/3 次) ..."
        if (-not (Open-Shortcut $TAP_TEMP_SHORTCUT 'com\.tuya' '温湿度报警器')) { continue }
        # mini-app 内容(温度大卡片)异步渲染，耐心轮询直到读到合理温度
        for ($i = 0; $i -lt 20; $i++) {          # 约 100s
            Start-Sleep -Seconds 5
            if (-not (Get-Screenshot $shot)) { continue }
            $t = Read-Number $shot $CROP_TEMP
            if ($null -ne $t -and $t -ge 5 -and $t -le 45) {
                Log ("Step 2: 当前温度 = {0}℃" -f $t)
                return $t
            }
        }
        Log '  温度卡片未渲染，重开 mini-app ...'
    }
    throw 'Step 2: 读取温度失败（多次重试后仍未渲染）'
}

# ---------------------------------------------------------------------------
# 步骤 4：打开主卧空调
# ---------------------------------------------------------------------------
function Open-Ac {
    Log 'Step 4: 打开「主卧空调」...'
    if (-not (Open-Shortcut $TAP_AC_SHORTCUT 'com\.haier' '主卧空调')) {
        throw 'Step 4: 无法打开主卧空调'
    }
    $shot = Join-Path $WorkDir 'ac.png'
    # 等待控制面板渲染（设定温度可读即视为就绪）
    for ($i = 0; $i -lt 12; $i++) {
        Start-Sleep -Seconds 4
        if (-not (Get-Screenshot $shot)) { continue }
        if ($null -ne (Read-Number $shot $CROP_SET)) { return $shot }
    }
    if (-not (Get-Screenshot $shot)) { throw 'Step 4: 空调界面截图失败' }
    return $shot
}

# ---------------------------------------------------------------------------
# 一个完整周期 (步骤 2-6)
# ---------------------------------------------------------------------------
function Invoke-Cycle {
    $t = Read-Temperature

    # 步骤 3：死区
    if ($t -gt $DEADBAND_LOW -and $t -le $DEADBAND_HIGH) {
        Log ("Step 3: {0}℃ 处于死区 ({1}, {2}] -> 不动作，完成." -f $t, $DEADBAND_LOW, $DEADBAND_HIGH)
        return
    }

    # 步骤 4：打开空调，检测开关机
    $shot = Open-Ac
    if (-not (Test-AcOn $shot)) {
        Log 'Step 4: 空调处于关机状态 -> 不动作，完成.'
        return
    }
    $sp = Read-Number $shot $CROP_SET
    Log ("Step 4: 空调已开机，当前设定温度 = {0}℃" -f $sp)

    if ($t -le $DEADBAND_LOW) {
        # 步骤 5：温度 <= 24.7 -> 设定 +1
        if ($null -ne $sp -and $sp -ge $SET_CEIL) {
            Log ("Step 5: 已达上限 {0}℃ -> 不动作." -f $SET_CEIL)
        } else {
            Invoke-Tap $TAP_AC_PLUS
            Start-Sleep -Seconds 2
            Get-Screenshot $shot | Out-Null
            $new = Read-Number $shot $CROP_SET
            Log ("Step 5: 温度<=24.7 -> 设定 +1 ({0} -> {1})℃" -f $sp, $new)
        }
    }
    elseif ($t -gt $DEADBAND_HIGH) {
        # 步骤 6：温度 > 25 -> 设定 -1，下限 26
        if ($null -ne $sp -and $sp -gt $SET_FLOOR) {
            Invoke-Tap $TAP_AC_MINUS
            Start-Sleep -Seconds 2
            Get-Screenshot $shot | Out-Null
            $new = Read-Number $shot $CROP_SET
            Log ("Step 6: 温度>25 -> 设定 -1 ({0} -> {1})℃" -f $sp, $new)
        } else {
            Log ("Step 6: 已达下限 {0}℃ -> 不动作." -f $SET_FLOOR)
        }
    }
}

# ---------------------------------------------------------------------------
# -Init：准备运行环境（能自动化的部分）
#   自动: 体检 SDK / 安装 tesseract+eng / 修正 AVD GPU 模式 / 检查目标应用
#   需人工(见 scripts/AGENTS.md): 创建 AVD、安装并配对 Tuya/Haier 应用、放置桌面快捷方式、必要时标定坐标
# ---------------------------------------------------------------------------
function Test-Cmd([string]$n) { [bool](Get-Command $n -ErrorAction SilentlyContinue) }

function Invoke-Bootstrap {
    Log '=== -Init: 准备运行环境 ==='
    $todo = @()

    Log '[1/4] Android SDK (adb + emulator) ...'
    if (Test-Path $ADB) { Log "  OK  adb: $ADB" } else { Log "  MISS adb: $ADB"; $todo += '安装 Android SDK Platform-Tools，或用 -Sdk 指定 SDK 路径' }
    if (Test-Path $EMU) { Log "  OK  emulator: $EMU" } else { Log "  MISS emulator: $EMU"; $todo += '安装 Android SDK Emulator' }

    Log '[2/4] tesseract OCR + eng 语言数据 ...'
    if (-not (Test-Path $TESS) -and -not (Test-Cmd tesseract)) {
        Log '  未检测到 tesseract，尝试自动安装 ...'
        if (Test-Cmd scoop) { scoop install tesseract }
        elseif (Test-Cmd winget) { winget install -e --id tesseract-ocr.tesseract --accept-source-agreements --accept-package-agreements }
        else { $todo += '手动安装 tesseract（无 scoop/winget）: https://github.com/UB-Mannheim/tesseract/wiki' }
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
                Log "  下载 eng.traineddata -> $dir ..."
                try {
                    Invoke-WebRequest 'https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata' -OutFile (Join-Path $dir 'eng.traineddata') -UseBasicParsing -TimeoutSec 90
                    Log '  OK  eng.traineddata 已安装'
                } catch { $todo += "下载 eng.traineddata 失败，请手动放入 $dir : $($_.Exception.Message)" }
            } else { $todo += '无法定位 tessdata 目录以安装 eng.traineddata' }
        }
    } else { Log '  MISS tesseract 仍不可用'; $todo += 'tesseract 安装失败，请手动安装' }

    Log "[3/4] AVD '$AvdName' + GPU 模式（VM/无 GPU 必须软件渲染）..."
    if (Test-Path $EMU) {
        $avds = & $EMU -list-avds 2>$null
        if ($avds -contains $AvdName) {
            Log "  OK  AVD 存在: $AvdName"
            $cfg = Join-Path $env:USERPROFILE ".android\avd\$AvdName.avd\config.ini"
            if (Test-Path $cfg) {
                $c = Get-Content $cfg
                if ($c -match '(?m)^hw\.gpu\.mode\s*=\s*host\s*$') {
                    Copy-Item $cfg "$cfg.bak" -Force
                    ($c -replace '(?m)^hw\.gpu\.mode\s*=\s*host\s*$', 'hw.gpu.mode = swiftshader_indirect') | Set-Content $cfg -Encoding ASCII
                    Log '  OK  hw.gpu.mode: host -> swiftshader_indirect (备份 config.ini.bak)'
                } else { Log '  OK  GPU 模式已非 host' }
            }
        } else {
            Log "  MISS 未找到 AVD '$AvdName'"
            $todo += "创建 AVD（见 AGENTS.md §3）: avdmanager create avd -n $AvdName -k `"system-images;android-34;google_apis;x86_64`" -d pixel_5"
        }
    }

    Log '[4/4] 目标应用（需模拟器在线才能检查）...'
    if ((& $ADB devices 2>$null) -match "$Serial\s+device") {
        $pkgs = & $ADB -s $Serial shell pm list packages 2>$null
        foreach ($p in @('com.tuya.smartiot', 'com.haier.uhome.uplus')) {
            if ($pkgs -match [regex]::Escape($p)) { Log "  OK  $p" }
            else { Log "  MISS $p"; $todo += "在 AVD 中安装并配置 $p，并把它固定到桌面（见 AGENTS.md §4）" }
        }
    } else { Log '  跳过（模拟器未运行）。启动后确认 温湿度报警器/主卧空调 已安装且有桌面快捷方式。' }

    Log ''
    if ($todo.Count -eq 0) {
        Log '=== -Init 完成：核心工具就绪。可运行: pwsh -File avd-ac-regulator.ps1 -MaxCycles 1 ==='
    } else {
        Log '=== -Init 还需手动处理以下项（详见 scripts/AGENTS.md）: ==='
        $i = 1; foreach ($it in $todo) { Log ("  {0}. {1}" -f $i, $it); $i++ }
    }
}

# ---------------------------------------------------------------------------
# 主循环
# ---------------------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
if ($Init) { Invoke-Bootstrap; return }
if (-not (Test-Path $ADB))  { throw "找不到 adb: $ADB（先运行: pwsh -File avd-ac-regulator.ps1 -Init）" }
if (-not (Test-Path $EMU))  { throw "找不到 emulator: $EMU（先运行: -Init）" }
if (-not (Test-Path $TESS)) { throw "找不到 tesseract: $TESS（先运行: -Init 自动安装）" }

$cycle = 0
Log ("模式: {0}" -f $(if ($ColdBoot) { '每周期冷启动 (-ColdBoot)' } else { '复用运行中的模拟器 (默认，不冷启动)' }))
while ($true) {
    $cycle++
    Log "========== Cycle #$cycle =========="
    try {
        if ($ColdBoot) { Invoke-ColdBoot } else { Ensure-EmulatorRunning }
        Invoke-Cycle
        Log "Cycle #$cycle 完成."
    } catch {
        Log "Cycle #$cycle 出错: $($_.Exception.Message)"
    }
    if ($MaxCycles -gt 0 -and $cycle -ge $MaxCycles) { Log "已达 MaxCycles=$MaxCycles，退出."; break }
    Log "等待 $IntervalMinutes 分钟进入下一周期 ..."
    Start-Sleep -Seconds ($IntervalMinutes * 60)
}
