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
```

Important parameters:

- `-AvdName` defaults to `Medium_Phone`.
- `-Serial` defaults to `emulator-5554`.
- `-Sdk` defaults to `$env:LOCALAPPDATA\Android\Sdk`.
- `-IntervalMinutes` defaults to `10` for fallback/low-temperature checks.
- `-NormalIntervalMinutes` defaults to `4` for safeband checks.
- `-HighIntervalMinutes` defaults to `4` for high-temperature checks.
- `-SafebandLow` defaults to `24.7`.
- `-SafebandHigh` defaults to `24.9`.
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
- The emulator is intentionally launched headless with software rendering: `-no-window -gpu swiftshader_indirect -no-audio`.
- Screenshots must stay binary-safe. The script uses `adb shell screencap -p /sdcard/x.png` plus `adb pull`.
- Do not replace screenshot capture with `adb exec-out screencap -p` captured into a PowerShell variable; that corrupts PNG bytes.
- On-screen numbers are rendered graphics, not text nodes, so OCR uses crop, glyph segmentation, upscaling, and Tesseract voting.
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

For temperature `T` and defaults `-SafebandLow 24.7 -SafebandHigh 24.9`:

- `24.7 <= T <= 24.9`: no action; wait `-NormalIntervalMinutes`.
- `T < 24.7`: open AC and, if powered on, increase setpoint by 1 up to `28`; wait `-IntervalMinutes`.
- `T > 24.9`: open AC and, if powered on, decrease setpoint by 1 down to `26`; wait `-HighIntervalMinutes`.
- If AC is powered off, do not change the setpoint and wait the interval chosen by the temperature state.

Do not change thresholds, setpoint caps, shortcut labels, package names, or emulator launch flags unless explicitly requested.
