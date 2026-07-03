# AGENTS.md - Standalone AVD AC Regulator

This repository is currently a standalone Windows/PowerShell automation for
[`scripts/avd-ac-regulator.ps1`](./scripts/avd-ac-regulator.ps1). It drives an Android
emulator with `adb`, reads a Tuya temperature screen with OCR, and adjusts a Haier AC.

Do not assume there is a parent app to build or run. The script depends only on Android SDK
tools, Tesseract OCR, and the configured vendor apps inside the AVD.

## Common Commands

Run from the repository root:

```powershell
pwsh -File .\scripts\avd-ac-regulator.ps1 -Init
pwsh -File .\scripts\avd-ac-regulator.ps1 -MaxCycles 1
pwsh -File .\scripts\avd-ac-regulator.ps1
pwsh -File .\scripts\avd-ac-regulator.ps1 -ColdBoot
pwsh -NoProfile -File .\tests\decision-logic.tests.ps1
```

Important parameters:

- `-AvdName` defaults to `Medium_Phone`.
- `-Serial` defaults to `emulator-5554`.
- `-Sdk` defaults to `$env:LOCALAPPDATA\Android\Sdk`.
- `-IntervalMinutes` defaults to `1` for the unified cycle interval.
- `-UnchangedThresholdCycles` defaults to `4`.
- `-SafebandLow` defaults to `24.8`.
- `-SafebandHigh` defaults to `24.9`.
- `-SetpointFloor` defaults to `25`.
- `-SetpointCeiling` defaults to `28`.
- `-MaxCycles 0` means run forever.
- `-ColdBoot` kills and cold-boots the AVD every cycle; default mode reuses the emulator.
- `-Init` prepares only the automatable runtime pieces and then exits.

## Bootstrap Order

Use this order on a fresh Windows machine:

1. Create the target AVD first. `-Init` cannot create it.
2. Run `pwsh -File .\scripts\avd-ac-regulator.ps1 -Init`.
3. Install, log in to, and pair the Tuya and Haier apps in the AVD.
4. Pin the two required home-screen shortcuts.
5. Recalibrate coordinates only when the device resolution is not one of the script's built-in calibrations.
6. Verify with `pwsh -File .\scripts\avd-ac-regulator.ps1 -MaxCycles 1`.

`-Init` verifies `adb` and `emulator`, installs or locates Tesseract plus `eng.traineddata`,
forces the AVD GPU mode away from host rendering when possible, checks installed packages when
an emulator is online, and prints any remaining manual actions.

## Runtime Facts

- The target host may be a headless or GPU-less VM.
- The emulator is intentionally launched with a visible window and software rendering: `-gpu swiftshader_indirect -no-audio`.
- Screenshots must stay binary-safe. The script uses `adb shell screencap -p /sdcard/x.png` plus `adb pull`.
- Do not replace screenshot capture with `adb exec-out screencap -p` captured into a PowerShell variable; that corrupts PNG bytes.
- On-screen numbers are rendered graphics, not text nodes, so OCR uses crop, glyph segmentation, upscaling, and Tesseract voting.
- Pure decision logic lives in `scripts/avd-ac-regulator.logic.ps1` and is covered by dependency-free tests in `tests/decision-logic.tests.ps1`; emulator, OCR, screenshot, and tap actions stay in `scripts/avd-ac-regulator.ps1`.
- Do not run two copies of the script against the same emulator serial at the same time.

## Required AVD And Apps

Default AVD:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
```

If `Medium_Phone` is missing, create it with Android Studio Device Manager or:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n Medium_Phone -k "system-images;android-34;google_apis;x86_64" -d pixel_5
```

Required app packages and shortcuts:

| Shortcut | Package | Purpose |
|---|---|---|
| `温湿度报警器` | `com.tuya.smartiot` | Current temperature screen |
| `主卧空调` | `com.haier.uhome.uplus` | AC setpoint and power controls |

Verify installed packages:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell pm list packages | Select-String 'com.tuya.smartiot|com.haier.uhome.uplus'
```

## Calibration

The script has built-in calibrations for `1080x2400` and `480x854`; `-Calibration auto` selects
by device resolution. If a new resolution is used, recalibrate these values in the script:

- Home-screen shortcut tap centers.
- Haier `+` and `-` setpoint buttons.
- OCR crop boxes for current temperature and AC setpoint.
- AC power-button sample box used for blue-fill ON detection.

Keep OCR crop boxes tight around digits.

## Decision Logic

For temperature `T` and defaults `-SafebandLow 24.8 -SafebandHigh 24.9`:

- `24.8 <= T <= 24.9`: no normal temperature correction; if the last recorded intervention setpoint is critical, perform critical-zone recovery first.
- Every cycle waits `-IntervalMinutes`, default 1 minute.
- The script keeps one in-memory last setpoint intervention record: `<cur_tmp, set_temp, set_cycle_idx>`.
- `T < 24.8`: out of safeband on the low side. If there is no relevant last low-side record, or `T` is lower than the last recorded temperature, increase setpoint by 1 up to `-SetpointCeiling`.
- `T > 24.9`: out of safeband on the high side. If there is no relevant last high-side record, or `T` is higher than the last recorded temperature, decrease setpoint by 1 down to `-SetpointFloor`.
- If `T` equals the last recorded temperature, only try another one-step intervention when `(current cycle index - recorded cycle index) > -UnchangedThresholdCycles`.
- If temperature is out of safeband but improving toward the safeband, do not open the AC and reset the cycle threshold baseline to the improving reading.
- If temperature is out of safeband and unchanged below threshold, do not open the AC.
- If AC is powered off, do not change the setpoint and do not update the last intervention record.
- Critical zone means any AC setpoint at or outside the automation range boundaries: `<= SetpointFloor` or `>= SetpointCeiling`.
- When the current temperature comes back into the safeband and the last recorded intervention setpoint is critical, open the AC and move the actual setpoint to the nearest non-critical interior setpoint: `SetpointFloor + 1` for low critical values, or `SetpointCeiling - 1` for high critical values.
- Clear the last intervention record only after the AC setpoint is confirmed non-critical; do not clear it when AC is off or setpoint OCR fails.
- There is no critical-zone interval behavior.

Do not change thresholds, setpoint caps, shortcut labels, package names, or emulator launch flags unless explicitly requested.
