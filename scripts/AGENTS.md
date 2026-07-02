# AGENTS.md — `avd-ac-regulator.ps1` environment bootstrap

Instructions for an agent to prepare a **fresh Windows PC** so that
[`avd-ac-regulator.ps1`](./avd-ac-regulator.ps1) runs. This script drives an Android
emulator (AVD) to read a temperature off a Tuya app and adjust a Haier AC. It is
**standalone** — it does NOT use the parent `appilot` app, only `adb` / `emulator` /
`tesseract`.

When asked to "prepare / bootstrap the env", the fast path is: **create the AVD first**
(§3 — cannot be done by `-Init`), then run the script's built-in **`-Init`** to auto-install
the tooling and fix the AVD GPU mode, then finish the manual app/shortcut setup it reports.

---

## Usage

```powershell
pwsh -File .\avd-ac-regulator.ps1 -Init          # bootstrap env (no loop), then prints a TODO list
pwsh -File .\avd-ac-regulator.ps1 -MaxCycles 1   # run ONE cycle (verify)
pwsh -File .\avd-ac-regulator.ps1                # run forever, one cycle / 10 min (reuse emulator)
pwsh -File .\avd-ac-regulator.ps1 -ColdBoot      # kill+cold-boot the AVD every cycle (slower, pristine)
```
Key params: `-AvdName` (default `Medium_Phone`), `-IntervalMinutes` (10),
`-SafebandLow` (24.7), `-SafebandHigh` (24.9), `-Serial` (`emulator-5554`),
`-Sdk` (`$env:LOCALAPPDATA\Android\Sdk`), `-MaxCycles` (0 = infinite), `-ColdBoot`
(switch), `-Init` (switch).

**Boot modes:**
- **Default (reuse):** ensures the emulator is running (starts it once if not), then reuses it
  across cycles — much faster (skips the ~70s cold boot each cycle).
- **`-ColdBoot`:** every cycle kills all AVDs + cold-boots a fresh one (`-no-snapshot-*`). Use
  only if you specifically need a pristine boot per cycle.

**Crash resilience:** the emulator is launched headless/software-rendered (stable on VMs), and
if it still exits/hangs mid-cycle, `Ensure-Device` (called before each screenshot and each
shortcut-open) detects the dead process and **auto-restarts it**, then the step retries — so a
mid-cycle crash self-heals instead of losing the cycle. A cycle that still fails is caught by
the main loop and retried next interval.

## `-Init` does the automatable half; YOU do the rest first

`-Init` (see `Invoke-Bootstrap`) will:
- verify `adb` + `emulator` exist (§1),
- install **tesseract** (scoop → winget) and download **`eng.traineddata`** (§2),
- if the AVD exists, force `hw.gpu.mode = swiftshader_indirect` in its `config.ini` (§3),
- if an emulator is online, check the two target apps are installed (§4),
- print a numbered TODO list of anything it could not do.

**`-Init` canNOT create the AVD, install/pair the vendor apps, pin shortcuts, or calibrate.**
So as the agent you must, BEFORE (or after, per the TODO) running `-Init`:
1. **Create the AVD** (§3) — do this first; `-Init`'s GPU fix and boot depend on it.
2. Install + configure the Tuya/Haier apps and pin the two home-screen shortcuts (§4).
3. Re-calibrate coordinates only if the resolution ≠ 1080x2400 (§5).

Then `pwsh -File .\avd-ac-regulator.ps1 -MaxCycles 1` should complete a cycle.

---

## When bootstrapping manually (or to understand what `-Init` automates)

Work through the sections below in order, running the verify command after each. Prefer
PowerShell 7 (`pwsh`).

---

## 0. Environment facts you MUST know first

- The target host is often a **headless / GPU-less VM** (e.g. Hyper-V: `Win32_ComputerSystem.Model = "Virtual Machine"`, only `Microsoft Hyper-V Video` adapter). This drove three non-obvious decisions already baked into the script:
  - The emulator is launched **headless**: `-no-window -gpu swiftshader_indirect -no-audio`.
    - `-no-window` avoids the `UpdateLayeredWindowIndirect failed (A device attached to the system is not functioning)` **GDI crash** that kills the emulator on a VM with no real display session.
    - `-gpu swiftshader_indirect` = software rendering; `-gpu host` is unstable without a physical GPU (Vulkan `VkInstance` create/destroy churn → auto-exit).
  - Screenshots use `adb shell screencap -p /sdcard/x.png` + `adb pull` (**binary-safe**). Do NOT capture `adb exec-out screencap -p` into a PowerShell variable — PowerShell decodes the bytes as text and corrupts the PNG (`ëPNG`).
  - On-screen numbers are **drawn graphics, not text nodes** (`uiautomator dump` shows nothing), so reading is OCR: crop → glyph-segment (drop the `℃`) → upscale → `tesseract` multi-PSM vote.
- **Never run two copies of the script at once.** Each cycle does `adb emu kill` + `Stop-Process qemu` to cold-boot; two copies fight and kill each other's emulator.

---

## 1. Android SDK (adb + emulator)   —   `-Init` verifies (cannot install)

The script resolves the SDK from `-Sdk` (default `$env:LOCALAPPDATA\Android\Sdk`).

**Verify:**
```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
Test-Path "$sdk\platform-tools\adb.exe"   # must be True
Test-Path "$sdk\emulator\emulator.exe"    # must be True
```

**If missing:** install Android Studio (bundles SDK), or the command-line tools, then:
```powershell
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "emulator" "system-images;android-34;google_apis;x86_64"
```
If the SDK lives elsewhere, run the script with `-Sdk <path>` (do not hard-code).

---

## 2. tesseract OCR + `eng.traineddata`   —   `-Init` automates this fully

**Verify:**
```powershell
(Get-Command tesseract -ErrorAction SilentlyContinue).Source   # or ~\scoop\shims\tesseract.exe
tesseract --list-langs                                          # the list must contain: eng
```

**If tesseract missing** — install (prefer scoop, else winget):
```powershell
scoop install tesseract
# or:
winget install -e --id tesseract-ocr.tesseract   # (alt id: UB-Mannheim.TesseractOCR)
```

**If `eng` missing** — download it into tesseract's own tessdata dir (parse the dir from
`--list-langs`, which prints `List of available languages in "<dir>"`):
```powershell
$dir = ([regex]::Match((tesseract --list-langs 2>&1) -join "`n", 'in "([^"]+)"')).Groups[1].Value
Invoke-WebRequest "https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata" -OutFile (Join-Path $dir 'eng.traineddata')
tesseract --list-langs   # eng should now appear
```

---

## 3. The AVD (default `Medium_Phone`, **1080x2400**)   —   YOU create it first; `-Init` only fixes its GPU mode

**Verify it exists:**
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds   # must list the target AVD
```

**If missing**, create one (Android Studio Device Manager, or):
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n Medium_Phone -k "system-images;android-34;google_apis;x86_64" -d pixel_5
```

**CRITICAL — force software rendering in the AVD config** (so ANY launch is stable, not just
via the script). Edit `~\.android\avd\<AVD>.avd\config.ini`:
```powershell
$cfg = "$env:USERPROFILE\.android\avd\Medium_Phone.avd\config.ini"
Copy-Item $cfg "$cfg.bak" -Force
(Get-Content $cfg) -replace '^hw\.gpu\.mode\s*=\s*host','hw.gpu.mode = swiftshader_indirect' | Set-Content $cfg -Encoding ASCII
Select-String -Path $cfg -Pattern 'hw.gpu.mode'   # should show swiftshader_indirect
```

**Boot test (headless, cold, no snapshot):**
```powershell
$emu="$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"; $adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
Start-Process $emu -ArgumentList '-avd','Medium_Phone','-no-snapshot-load','-no-snapshot-save','-no-window','-no-boot-anim','-gpu','swiftshader_indirect','-no-audio'
& $adb wait-for-device
1..40 | ForEach-Object { if((& $adb -s emulator-5554 shell getprop sys.boot_completed 2>$null).Trim() -eq '1'){ 'booted'; break }; Start-Sleep 5 }
```

---

## 4. Apps + desktop shortcuts (NOT fully automatable — needs a human)   —   `-Init` only checks presence

The script opens two **home-screen shortcuts** by tapping fixed coordinates. These require
the apps to be installed, configured (device paired / logged in), and pinned to the home
screen. An agent cannot create the vendor accounts or pair devices; flag these as manual.

| Shortcut (label) | App package | Purpose |
|---|---|---|
| `温湿度报警器` | `com.tuya.smartiot` | shows current temperature (drawn) |
| `主卧空调`     | `com.haier.uhome.uplus` | AC control: setpoint + on/off + `+`/`−` |

**Verify apps installed:**
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell pm list packages | Select-String 'com.tuya.smartiot|com.haier.uhome.uplus'
```
If absent: install the APKs (`adb install <apk>`), open each, complete
login/pairing so the temperature device and the AC appear, then **pin both to the home
screen** as the two shortcuts above.

---

## 5. Calibration (only if resolution ≠ 1080x2400)

All coordinates/crop boxes at the top of the script are for a **1080x2400** Medium_Phone.
If `adb shell wm size` differs, re-derive them:

- **Shortcut tap centers** — `adb shell uiautomator dump` the home screen, read the `bounds`
  of the two shortcut `TextView` nodes, use their centers. (Currently: 温湿度报警器 `(663,1221)`, 主卧空调 `(910,1221)`.)
- **`+` / `−` buttons** on the Haier setpoint card. (Currently `+ (936,866)`, `− (144,866)`.)
- **Crop regions** for OCR — the big temperature number card (`CROP_TEMP`) and the centered
  setpoint number (`CROP_SET`); `[x,y,w,h]` around just the digits.
- **AC power-button sample box** (`AC_POWER_BOX`) — a patch inside the bottom-left power
  button; blue fill (`B>150 & B-R>60 & R<120`) ⇒ ON (`已开机`).

Re-derive by screenshotting and inspecting pixel positions; keep the box tight to the digits.

---

## 6. Final verification

```powershell
# tools present
Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
Test-Path "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
(tesseract --list-langs) -match 'eng'
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds

# one real cycle (cold-boots headless, reads temp, may act on AC)
pwsh -File .\avd-ac-regulator.ps1 -MaxCycles 1
```

A healthy run logs: `Step 1: 已进入桌面` → `Step 2: 当前温度 = NN.N℃` → a Step 3/4/5/6
decision, and the emulator stays alive throughout. If `Step 2` times out, the Tuya
mini-app didn't render — give it more time / confirm the device is paired and the shortcut
opens the temperature panel (not a login page).

---

## Decision logic reference (do not change without instruction)

Read temperature `T`; then, with default `-SafebandLow 24.7 -SafebandHigh 24.9`:
1. `24.7 ≤ T ≤ 24.9` → do nothing (safeband).
2. else open AC; if **off** (`已关机`) → do nothing.
3. `T < 24.7` → setpoint **+1** (ceiling 30).
4. `T > 24.9` → setpoint **−1** (floor 26).
