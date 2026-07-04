# appilot — AVD AC Regulator

Standalone Windows/PowerShell automation that keeps a room inside a narrow
temperature band. It drives an Android emulator (AVD) with `adb`, reads a Tuya
temperature/humidity screen with OCR, and nudges a Haier air conditioner's
setpoint up or down until the reading is back inside the safeband.

Everything lives in [`scripts/avd-ac-regulator.ps1`](./scripts/avd-ac-regulator.ps1)
and its pure-logic companion. There is **no parent app to build or run** — any
Gradle/Android scaffolding you may see in the working tree is untracked leftover
and is not part of this project.

---

## How it works

Because the temperature and setpoint are rendered as graphics (not text nodes),
the script screenshots the emulator, crops the digits, upscales them, and runs
Tesseract OCR with multi-PSM voting. AC power state is inferred from the blue
fill ratio of the power button.

Each cycle (default interval: 1 minute) runs a closed loop:

1. **Ensure the emulator is running.** Default mode reuses an already-running
   emulator; `-ColdBoot` kills every AVD and cold-boots a fresh one with
   software rendering and snapshots disabled.
2. **Read the temperature.** Open the Tuya shortcut, capture a screenshot, and
   OCR the current temperature.
3. **Decide.** Compare the reading against the safeband and the last recorded
   intervention (see [Decision logic](#decision-logic)).
4. **Act on the AC.** Open the Haier shortcut. If the AC is powered off, do
   nothing. Otherwise:
   - Temperature **below** the safeband → increase setpoint by 1 (capped at the
     ceiling).
   - Temperature **above** the safeband → decrease setpoint by 1 (floored at the
     floor).
   - Temperature back **inside** the safeband while the last setpoint was
     "critical" → recover it toward the interior.

The loop self-heals: a crashed or unresponsive emulator is restarted, and the
emulator window is kept restored/topmost so rendering, screenshots, and taps
keep working on headless or GPU-less hosts.

---

## Repository layout

| Path | Purpose |
|---|---|
| [`scripts/avd-ac-regulator.ps1`](./scripts/avd-ac-regulator.ps1) | Runtime loop: emulator control, OCR, screenshots, taps, bootstrap (`-Init`). |
| [`scripts/avd-ac-regulator.logic.ps1`](./scripts/avd-ac-regulator.logic.ps1) | Pure decision logic, dependency-free and unit-tested. |
| [`tests/decision-logic.tests.ps1`](./tests/decision-logic.tests.ps1) | Dependency-free tests for the decision logic + runtime parse check. |
| [`AGENTS.md`](./AGENTS.md) | Deep operational/dev reference for this repo. |

---

## Requirements

- **Windows** with **PowerShell 7+** (`pwsh`).
- **Android SDK** with `platform-tools` (`adb`) and `emulator`. Default SDK path:
  `%LOCALAPPDATA%\Android\Sdk` (override with `-Sdk`).
- **Tesseract OCR** with `eng.traineddata` on `PATH` (or at
  `%USERPROFILE%\scoop\shims\tesseract.exe`).
- An **AVD** (default name `Medium_Phone`) with the **Tuya** and **Haier** apps
  installed, logged in, paired, and pinned as home-screen shortcuts.

The `-Init` command installs/locates Tesseract and its language data and checks
the rest of the toolchain for you.

---

## Quick start

Run everything from the repository root.

```powershell
# 1. Create the target AVD first — -Init cannot create it.
#    (Android Studio Device Manager, or avdmanager — see AGENTS.md.)

# 2. Prepare the automatable runtime (SDK checks, Tesseract + eng, GPU mode, app checks).
pwsh -File .\scripts\avd-ac-regulator.ps1 -Init

# 3. In the AVD: install, log in to, and pair the Tuya and Haier apps.
# 4. Pin the two required home-screen shortcuts (see table below).
# 5. Recalibrate coordinates only if the device resolution is not built in.

# 6. Verify with a single cycle.
pwsh -File .\scripts\avd-ac-regulator.ps1 -MaxCycles 1

# Then run the loop forever.
pwsh -File .\scripts\avd-ac-regulator.ps1
```

`-Init` verifies `adb` and `emulator`, installs or locates Tesseract plus
`eng.traineddata`, forces the AVD GPU mode away from host rendering when
possible, checks the installed target packages when an emulator is online, and
prints any remaining manual actions.

---

## Usage

```powershell
pwsh -File .\scripts\avd-ac-regulator.ps1 [options]
```

Print the built-in help at any time:

```powershell
pwsh -File .\scripts\avd-ac-regulator.ps1 -Help
```

### Options

| Option | Default | Description |
|---|---|---|
| `-Help` | `false` | Print help and exit. |
| `-Init` | `false` | Prepare runtime dependencies and exit without running cycles. |
| `-ColdBoot` | `false` | Kill all AVDs and cold-boot every cycle. Default reuses the running emulator. |
| `-AvdName <string>` | `Medium_Phone` | AVD name. |
| `-Serial <string>` | `emulator-5554` | `adb` serial. |
| `-Sdk <string>` | `%LOCALAPPDATA%\Android\Sdk` | Android SDK path. |
| `-WorkDir <string>` | `%LOCALAPPDATA%\Temp\avd-ac-regulator` | Temporary working directory. |
| `-Calibration <auto\|1080x2400\|480x854>` | `auto` | Coordinate calibration profile. |
| `-MaxCycles <int>` | `0` | Number of cycles to run; `0` means forever. |
| `-IntervalMinutes <int>` | `1` | Interval between cycles (minimum 1). |
| `-UnchangedThresholdCycles <int>` | `4` | Same-temperature retry threshold in cycles. |
| `-SafebandLow <double>` | `24.8` | Lower no-action temperature bound. |
| `-SafebandHigh <double>` | `24.9` | Upper no-action temperature bound. |
| `-SafebandAt <HH:mm,low,high>` | none | Repeatable time-based safeband entry, for example `21:00,24.7,24.8`. When omitted, `-SafebandLow` and `-SafebandHigh` apply all night. |
| `-SetpointFloor <int>` | `25` | Lowest AC setpoint used by automation. |
| `-SetpointCeiling <int>` | `28` | Highest AC setpoint used by automation. |

**Validation** (the script rejects out-of-range combinations up front):

- Safeband bounds must be finite and `-SafebandLow <= -SafebandHigh`.
- Each `-SafebandAt` entry must use `HH:mm,low,high`, with finite bounds,
  `low <= high`, and no duplicate `HH:mm` times.
- `-SetpointFloor <= -SetpointCeiling`, and the ceiling must be at least 2 above
  the floor (needed for critical-zone recovery).
- The setpoint range must stay within the supported app range `[16, 30]`.
- `-Calibration` must be one of `auto`, `1080x2400`, or `480x854`.
- `-IntervalMinutes >= 1` and `-UnchangedThresholdCycles >= 0`.

### Examples

```powershell
# One cycle against a running emulator (smoke test).
pwsh -File .\scripts\avd-ac-regulator.ps1 -MaxCycles 1

# Cold-boot the AVD every cycle.
pwsh -File .\scripts\avd-ac-regulator.ps1 -ColdBoot

# Widen the safeband.
pwsh -File .\scripts\avd-ac-regulator.ps1 -SafebandLow 24.5 -SafebandHigh 24.9

# Use different safebands during different local-time spans.
pwsh -File .\scripts\avd-ac-regulator.ps1 `
  -SafebandAt '21:00,24.7,24.8' `
  -SafebandAt '00:00,24.8,24.9' `
  -SafebandAt '04:30,25,25.2'
```

> Do not run two copies of the script against the same emulator serial at once.

---

## Decision logic

Defaults below assume `-SafebandLow 24.8 -SafebandHigh 24.9 -SetpointFloor 25
-SetpointCeiling 28`. The script keeps a single in-memory record of the last
setpoint intervention: `<current_temp, set_temp, set_cycle_index>`.

If `-SafebandAt` entries are provided, each cycle first selects the active range
from local host time. The active range is the latest schedule entry whose time is
less than or equal to the current local time; before the first entry of the day,
selection wraps to the last entry from the previous day.

For a temperature `T`:

- **`24.8 <= T <= 24.9` (in safeband):** no normal correction. If the last
  recorded intervention setpoint is *critical*, perform critical-zone recovery
  first.
- **`T < 24.8` (low side):** if there is no relevant low-side record, or `T` is
  lower than the last recorded temperature (worsening), increase the setpoint by
  1, up to `-SetpointCeiling`.
- **`T > 24.9` (high side):** if there is no relevant high-side record, or `T` is
  higher than the last recorded temperature (worsening), decrease the setpoint by
  1, down to `-SetpointFloor`.
- **`T` unchanged** vs. the recorded temperature: only try another one-step
  intervention when `(current cycle - recorded cycle) > UnchangedThresholdCycles`.
- **Out of safeband but improving** toward it: do not open the AC; reset the
  cycle-threshold baseline to the improving reading.
- **Out of safeband, unchanged and below threshold:** do not open the AC.
- **AC powered off:** do not change the setpoint and do not update the record.

### Critical-zone recovery

A setpoint is *critical* when it sits at or outside the automation boundaries
(`<= SetpointFloor` or `>= SetpointCeiling`). When `T` returns to the safeband
and the last recorded intervention setpoint is critical, the script opens the AC
and moves the actual setpoint to the nearest non-critical interior value
(`SetpointFloor + 1` for low-critical, `SetpointCeiling - 1` for high-critical).
The intervention record is cleared only after the setpoint is confirmed
non-critical — never while the AC is off or setpoint OCR fails.

---

## Calibration

Calibration covers home-screen shortcut tap centers, the Haier `+`/`-` buttons,
the OCR crop boxes for the current temperature and AC setpoint, and the
power-button sample box used for blue-fill ON detection.

Two profiles are built in — **`1080x2400`** and **`480x854`** — and
`-Calibration auto` (the default) selects one by the running device resolution,
falling back to `1080x2400` for unknown sizes. If you use a different
resolution, recalibrate the coordinate constants in
[`scripts/avd-ac-regulator.ps1`](./scripts/avd-ac-regulator.ps1) (see the
`Use-Calibration` function) and keep OCR crop boxes tight around the digits.

---

## Required AVD and apps

Default AVD name: `Medium_Phone`. Required packages and their home-screen
shortcuts:

| Shortcut | Package | Purpose |
|---|---|---|
| `温湿度报警器` | `com.tuya.smartiot` | Current temperature screen |
| `主卧空调` | `com.haier.uhome.uplus` | AC setpoint and power controls |

Verify the packages are installed:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell pm list packages `
  | Select-String 'com.tuya.smartiot|com.haier.uhome.uplus'
```

If `Medium_Phone` is missing, create it with Android Studio's Device Manager or:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat" `
  create avd -n Medium_Phone -k "system-images;android-34;google_apis;x86_64" -d pixel_5
```

---

## Tests

The decision logic is covered by dependency-free tests that also parse-check the
runtime script:

```powershell
pwsh -NoProfile -File .\tests\decision-logic.tests.ps1
```

---

## Runtime notes

- The target host may be a headless or GPU-less VM. The emulator is launched
  intentionally with a visible window and software rendering:
  `-gpu swiftshader_indirect -no-audio` (plus `-no-snapshot-load
  -no-snapshot-save -no-boot-anim`).
- Screenshots stay binary-safe by using `adb shell screencap -p` on the device
  and then `adb pull`. Do not switch to `adb exec-out screencap -p` captured into
  a PowerShell variable — that corrupts the PNG bytes.
- On-screen numbers are rendered graphics, so OCR relies on crop, glyph
  segmentation, upscaling, and Tesseract voting across multiple PSM modes.
- Pure decision logic is isolated in `avd-ac-regulator.logic.ps1`; emulator, OCR,
  screenshot, and tap actions stay in `avd-ac-regulator.ps1`.

For deeper operational details, bootstrap ordering, and contributor guidance,
see [`AGENTS.md`](./AGENTS.md).
