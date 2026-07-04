# Time-Based Safebands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional repeatable `-SafebandAt` command-line parameter that selects different safeband ranges by local time while preserving the existing all-night default behavior.

**Architecture:** Keep temperature decision logic scalar: `Get-CycleDecision` continues to receive one low/high pair. Add pure schedule parsing/resolution helpers to `scripts/avd-ac-regulator.logic.ps1`, then have the runtime resolve the active pair at the start of each cycle before calling existing decision code.

**Tech Stack:** PowerShell 7+, dependency-free `.ps1` logic helpers, existing custom test script `tests/decision-logic.tests.ps1`, no new runtime dependencies.

## Global Constraints

- Existing default behavior remains unchanged when `-SafebandAt` is omitted: use `-SafebandLow 24.8` and `-SafebandHigh 24.9` for every cycle.
- `-SafebandAt` values use `HH:mm,low,high` with local host time in 24-hour format.
- Active schedule selection uses the latest entry whose `After` time is less than or equal to current local time, wrapping to the last entry before the first entry of the day.
- Reject schedule entries with malformed field count, invalid `HH:mm`, non-finite bounds, `low > high`, or duplicate times.
- Do not change thresholds, setpoint caps, shortcut labels, package names, or emulator launch flags.
- Do not add new dependencies or require a parent app build.

---

## File Structure

- Modify `scripts/avd-ac-regulator.logic.ps1`: add pure helpers `ConvertTo-SafebandSchedule` and `Get-ActiveSafeband`. This file remains dependency-free and unit-testable.
- Modify `tests/decision-logic.tests.ps1`: add tests for schedule parsing, sorting, active-range selection, and validation failures.
- Modify `scripts/avd-ac-regulator.ps1`: capture repeated `-SafebandAt` tokens, parse schedule once, resolve active safeband per cycle, and update runtime logs/comparisons to use active bounds.
- Modify `README.md`: document the new option, validation, and example usage.

---

### Task 1: Pure Safeband Schedule Helpers

**Files:**
- Modify: `scripts/avd-ac-regulator.logic.ps1`
- Modify: `tests/decision-logic.tests.ps1`

**Interfaces:**
- Consumes: raw user values as `[string[]]$Entries`; active time as `[TimeSpan]$CurrentTime`.
- Produces: `ConvertTo-SafebandSchedule([string[]]$Entries) -> object[]` where each item has `After`, `Low`, and `High`; `Get-ActiveSafeband($Schedule, [TimeSpan]$CurrentTime) -> object` with `After`, `Low`, and `High`.

- [ ] **Step 1: Add failing parser and resolver tests**

Append these assertions to `tests/decision-logic.tests.ps1` after the existing opposite-side intervention assertions and before the runtime parse checks:

```powershell
$schedule = ConvertTo-SafebandSchedule -Entries @('21:00,24.7,24.8')
Assert-Equal 1 @($schedule).Count 'single safeband schedule entry parses'
Assert-Equal ([TimeSpan]::ParseExact('21:00', 'hh\:mm', [Globalization.CultureInfo]::InvariantCulture)) $schedule[0].After 'single safeband schedule time parses'
Assert-Equal 24.7 $schedule[0].Low 'single safeband schedule low parses'
Assert-Equal 24.8 $schedule[0].High 'single safeband schedule high parses'

$schedule = ConvertTo-SafebandSchedule -Entries @('04:30,25,25.2', '21:00,24.7,24.8', '00:00,24.8,24.9')
Assert-Equal ([TimeSpan]::ParseExact('00:00', 'hh\:mm', [Globalization.CultureInfo]::InvariantCulture)) $schedule[0].After 'schedule entries sort by time first'
Assert-Equal ([TimeSpan]::ParseExact('04:30', 'hh\:mm', [Globalization.CultureInfo]::InvariantCulture)) $schedule[1].After 'schedule entries sort by time second'
Assert-Equal ([TimeSpan]::ParseExact('21:00', 'hh\:mm', [Globalization.CultureInfo]::InvariantCulture)) $schedule[2].After 'schedule entries sort by time third'

$active = Get-ActiveSafeband -Schedule $schedule -CurrentTime ([TimeSpan]::ParseExact('21:00', 'hh\:mm', [Globalization.CultureInfo]::InvariantCulture))
Assert-Equal 24.7 $active.Low 'active safeband exact match picks matching entry low'
Assert-Equal 24.8 $active.High 'active safeband exact match picks matching entry high'

$active = Get-ActiveSafeband -Schedule $schedule -CurrentTime ([TimeSpan]::ParseExact('03:15', 'hh\:mm', [Globalization.CultureInfo]::InvariantCulture))
Assert-Equal 24.8 $active.Low 'active safeband between entries picks previous entry low'
Assert-Equal 24.9 $active.High 'active safeband between entries picks previous entry high'

$active = Get-ActiveSafeband -Schedule $schedule -CurrentTime ([TimeSpan]::ParseExact('20:59', 'hh\:mm', [Globalization.CultureInfo]::InvariantCulture))
Assert-Equal 25.0 $active.Low 'active safeband before late entry picks daytime entry low'
Assert-Equal 25.2 $active.High 'active safeband before late entry picks daytime entry high'

$active = Get-ActiveSafeband -Schedule $schedule -CurrentTime ([TimeSpan]::ParseExact('00:00', 'hh\:mm', [Globalization.CultureInfo]::InvariantCulture))
Assert-Equal 24.8 $active.Low 'active safeband midnight exact match picks midnight low'

$lateOnlySchedule = ConvertTo-SafebandSchedule -Entries @('21:00,24.7,24.8', '04:30,25,25.2')
$active = Get-ActiveSafeband -Schedule $lateOnlySchedule -CurrentTime ([TimeSpan]::ParseExact('03:59', 'hh\:mm', [Globalization.CultureInfo]::InvariantCulture))
Assert-Equal 24.7 $active.Low 'active safeband wraps to last entry before first entry low'
Assert-Equal 24.8 $active.High 'active safeband wraps to last entry before first entry high'

function Assert-Throws([scriptblock]$ScriptBlock, [string]$Name) {
    try {
        & $ScriptBlock
        $script:Failures++
        Write-Host ("FAIL {0}: expected exception" -f $Name)
    } catch {
        Write-Host ("PASS {0}" -f $Name)
    }
}

Assert-Throws { ConvertTo-SafebandSchedule -Entries @('21:00,24.7') } 'schedule rejects missing field'
Assert-Throws { ConvertTo-SafebandSchedule -Entries @('24:00,24.7,24.8') } 'schedule rejects invalid hour'
Assert-Throws { ConvertTo-SafebandSchedule -Entries @('21:60,24.7,24.8') } 'schedule rejects invalid minute'
Assert-Throws { ConvertTo-SafebandSchedule -Entries @('21:00,NaN,24.8') } 'schedule rejects NaN low'
Assert-Throws { ConvertTo-SafebandSchedule -Entries @('21:00,24.7,Infinity') } 'schedule rejects infinity high'
Assert-Throws { ConvertTo-SafebandSchedule -Entries @('21:00,24.9,24.8') } 'schedule rejects reversed bounds'
Assert-Throws { ConvertTo-SafebandSchedule -Entries @('21:00,24.7,24.8', '21:00,24.8,24.9') } 'schedule rejects duplicate times'
Assert-Throws { Get-ActiveSafeband -Schedule @() -CurrentTime ([TimeSpan]::Zero) } 'active safeband rejects empty schedule'
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pwsh -NoProfile -File .\tests\decision-logic.tests.ps1`

Expected: FAIL because `ConvertTo-SafebandSchedule` and `Get-ActiveSafeband` are not recognized.

- [ ] **Step 3: Add pure helper implementation**

Insert this code in `scripts/avd-ac-regulator.logic.ps1` after `New-SetActionRecord` and before `Test-InSafeband`:

```powershell
function ConvertTo-SafebandSchedule {
    param(
        [string[]]$Entries
    )

    $records = @()
    $seen = @{}
    foreach ($entry in @($Entries)) {
        $parts = @($entry -split ',') | ForEach-Object { $_.Trim() }
        if ($parts.Count -ne 3) {
            throw "Safeband schedule entry '$entry' must use format HH:mm,low,high."
        }

        $after = [TimeSpan]::Zero
        if (-not [TimeSpan]::TryParseExact($parts[0], 'hh\:mm', [Globalization.CultureInfo]::InvariantCulture, [ref]$after)) {
            throw "Safeband schedule time '$($parts[0])' must use HH:mm 24-hour format."
        }

        if ($seen.ContainsKey($after.Ticks)) {
            throw "Duplicate safeband schedule time '$($parts[0])'."
        }
        $seen[$after.Ticks] = $true

        $low = 0.0
        $high = 0.0
        if (-not [double]::TryParse($parts[1], [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$low)) {
            throw "Safeband schedule low bound '$($parts[1])' must be a number."
        }
        if (-not [double]::TryParse($parts[2], [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$high)) {
            throw "Safeband schedule high bound '$($parts[2])' must be a number."
        }
        if ([double]::IsNaN($low) -or [double]::IsInfinity($low) -or [double]::IsNaN($high) -or [double]::IsInfinity($high)) {
            throw 'Safeband schedule bounds must be finite numbers.'
        }
        if ($low -gt $high) {
            throw 'Safeband schedule low bound must be less than or equal to high bound.'
        }

        $records += [pscustomobject]@{
            After = $after
            Low = $low
            High = $high
        }
    }

    return @($records | Sort-Object -Property After)
}

function Get-ActiveSafeband {
    param(
        [Parameter(Mandatory = $true)]$Schedule,
        [Parameter(Mandatory = $true)][TimeSpan]$CurrentTime
    )

    $entries = @($Schedule)
    if ($entries.Count -eq 0) {
        throw 'Safeband schedule must contain at least one entry.'
    }

    $active = $null
    foreach ($entry in $entries) {
        if ([TimeSpan]$entry.After -le $CurrentTime) {
            $active = $entry
        }
    }
    if ($null -eq $active) {
        $active = $entries[$entries.Count - 1]
    }
    return $active
}
```

- [ ] **Step 4: Run tests to verify helper behavior passes**

Run: `pwsh -NoProfile -File .\tests\decision-logic.tests.ps1`

Expected: PASS for all existing and new assertions.

- [ ] **Step 5: Commit Task 1**

Run:

```powershell
git add -- scripts/avd-ac-regulator.logic.ps1 tests/decision-logic.tests.ps1
$env:GIT_AUTHOR_DATE='2026-07-05T02:17:00+08:00'; $env:GIT_COMMITTER_DATE='2026-07-05T02:17:00+08:00'; git commit -m "feat: add safeband schedule helpers"
```

Expected: commit contains only the helper functions and tests.

---

### Task 2: Runtime Safeband Schedule Integration

**Files:**
- Modify: `scripts/avd-ac-regulator.ps1`
- Modify: `tests/decision-logic.tests.ps1`

**Interfaces:**
- Consumes: `ConvertTo-SafebandSchedule -Entries $SafebandAt`, `Get-ActiveSafeband -Schedule $SAFEBAND_SCHEDULE -CurrentTime (Get-Date).TimeOfDay`.
- Produces: runtime variables `$SAFEBAND_SCHEDULE`, `Get-CurrentSafeband`, and per-cycle `$activeSafeband` values used for decision and setpoint comparison.

- [ ] **Step 1: Add failing runtime text tests**

Append these assertions near the existing runtime text assertions in `tests/decision-logic.tests.ps1`:

```powershell
Assert-True ($runtimeText.Contains('[Parameter(ValueFromRemainingArguments = $true)]')) 'runtime captures repeated SafebandAt tokens'
Assert-True ($runtimeText.Contains('Read-SafebandAtArguments')) 'runtime exposes SafebandAt raw argument parser'
Assert-True ($runtimeText -like '*ConvertTo-SafebandSchedule -Entries $SafebandAt*') 'runtime parses SafebandAt at startup'
Assert-True ($runtimeText -like '*function Get-CurrentSafeband*') 'runtime exposes active safeband resolver'
Assert-True ($runtimeText -like '*Get-ActiveSafeband -Schedule $SAFEBAND_SCHEDULE*') 'runtime resolves active safeband from schedule'
Assert-True ($runtimeText -like '*-SafebandLow $activeLow -SafebandHigh $activeHigh*') 'runtime passes active safeband into decision logic'
Assert-True ($runtimeText -like '*Invoke-CriticalZoneRecovery -Temperature $t -SafebandLow $activeLow -SafebandHigh $activeHigh*') 'runtime passes active safeband into critical recovery log'
Assert-True ($runtimeText -like '*$decision.TemperatureSide -eq ''low''*') 'runtime actuator branch uses decision low side'
Assert-True ($runtimeText -like '*$decision.TemperatureSide -eq ''high''*') 'runtime actuator branch uses decision high side'
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pwsh -NoProfile -File .\tests\decision-logic.tests.ps1`

Expected: FAIL on the new runtime text assertions.

- [ ] **Step 3: Add repeated SafebandAt token capture and parser**

Do not add `[string[]]$SafebandAt` as a normal named parameter. PowerShell rejects repeated named parameters before script code runs, even when the target type is an array. Instead, capture repeated `-SafebandAt <value>` tokens with `ValueFromRemainingArguments`.

At the end of the parameter block in `scripts/avd-ac-regulator.ps1`, replace the current last parameter:

```powershell
    [switch]$ColdBoot        # Kill all AVDs and cold-boot every cycle. Default reuses the emulator.
)
```

with:

```powershell
    [switch]$ColdBoot,       # Kill all AVDs and cold-boot every cycle. Default reuses the emulator.
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs
)
```

Add this parser function after `Write-Usage` and before `if ($Help)`:

```powershell
function Read-SafebandAtArguments {
    param(
        [string[]]$Arguments
    )

    $entries = @()
    for ($i = 0; $i -lt @($Arguments).Count; $i++) {
        $arg = $Arguments[$i]
        if ($arg -ne '-SafebandAt') {
            throw "Unknown argument '$arg'. Use -Help to see supported options."
        }
        if (($i + 1) -ge @($Arguments).Count) {
            throw '-SafebandAt requires a value in HH:mm,low,high format.'
        }

        $entries += $Arguments[$i + 1]
        $i++
    }

    return $entries
}
```

Add this assignment after `$ErrorActionPreference = 'Stop'`:

```powershell
$SafebandAt = Read-SafebandAtArguments -Arguments $RemainingArgs
```

Move the dot-source line for `avd-ac-regulator.logic.ps1` before schedule validation if needed so `ConvertTo-SafebandSchedule` is available before startup state is built. The validation block should still run before emulator, OCR, or AVD work.

- [ ] **Step 4: Add startup schedule state**

Replace the current fixed threshold state:

```powershell
$SAFEBAND_LOW  = $SafebandLow   # [SAFEBAND_LOW, SAFEBAND_HIGH] => no action
$SAFEBAND_HIGH = $SafebandHigh
```

with:

```powershell
$SAFEBAND_LOW  = $SafebandLow   # Default [SAFEBAND_LOW, SAFEBAND_HIGH] => no action
$SAFEBAND_HIGH = $SafebandHigh
$SAFEBAND_SCHEDULE = @()
if (@($SafebandAt).Count -gt 0) {
    $SAFEBAND_SCHEDULE = ConvertTo-SafebandSchedule -Entries $SafebandAt
}
```

- [ ] **Step 5: Add active safeband resolver and logging**

Add this helper after `Clear-LastSetAction` and before `Update-LastSetActionObservation`:

```powershell
function Get-CurrentSafeband {
    if (@($script:SAFEBAND_SCHEDULE).Count -eq 0) {
        return [pscustomobject]@{
            After = $null
            Low = $script:SAFEBAND_LOW
            High = $script:SAFEBAND_HIGH
        }
    }

    return Get-ActiveSafeband -Schedule $script:SAFEBAND_SCHEDULE -CurrentTime (Get-Date).TimeOfDay
}
```

Replace the single startup safeband log in `Write-StartupInfo`:

```powershell
    Log ("Safeband: [{0}, {1}] C" -f $SAFEBAND_LOW, $SAFEBAND_HIGH)
```

with:

```powershell
    if (@($SAFEBAND_SCHEDULE).Count -eq 0) {
        Log ("Safeband: [{0}, {1}] C" -f $SAFEBAND_LOW, $SAFEBAND_HIGH)
    } else {
        $scheduleText = (@($SAFEBAND_SCHEDULE) | ForEach-Object { ("{0:hh\:mm} -> [{1}, {2}] C" -f $_.After, $_.Low, $_.High) }) -join '; '
        Log ("Safeband schedule: {0}" -f $scheduleText)
    }
```

- [ ] **Step 6: Use active bounds inside each cycle**

At the start of `Invoke-Cycle`, replace:

```powershell
    $t = Read-Temp
    $decision = Get-CycleDecision -Temperature $t -LastSetAction $script:LAST_SET_ACTION -CycleIndex $CycleIndex -SafebandLow $SAFEBAND_LOW -SafebandHigh $SAFEBAND_HIGH -SetpointFloor $SET_FLOOR -SetpointCeiling $SET_CEIL -UnchangedThresholdCycles $UnchangedThresholdCycles
```

with:

```powershell
    $t = Read-Temp
    $activeSafeband = Get-CurrentSafeband
    $activeLow = [double]$activeSafeband.Low
    $activeHigh = [double]$activeSafeband.High
    if ($null -ne $activeSafeband.After) {
        Log ("Step 3: active safeband since {0:hh\:mm}: [{1}, {2}] C" -f $activeSafeband.After, $activeLow, $activeHigh)
    }
    $decision = Get-CycleDecision -Temperature $t -LastSetAction $script:LAST_SET_ACTION -CycleIndex $CycleIndex -SafebandLow $activeLow -SafebandHigh $activeHigh -SetpointFloor $SET_FLOOR -SetpointCeiling $SET_CEIL -UnchangedThresholdCycles $UnchangedThresholdCycles
```

In `Invoke-Cycle`, replace the safeband-safe log:

```powershell
        Log ("Step 3: {0} C is in safeband [{1}, {2}] -> no action." -f $t, $SAFEBAND_LOW, $SAFEBAND_HIGH)
```

with:

```powershell
        Log ("Step 3: {0} C is in safeband [{1}, {2}] -> no action." -f $t, $activeLow, $activeHigh)
```

Replace the critical recovery call:

```powershell
        Invoke-CriticalZoneRecovery $t
```

with:

```powershell
        Invoke-CriticalZoneRecovery -Temperature $t -SafebandLow $activeLow -SafebandHigh $activeHigh
```

Update the `Invoke-CriticalZoneRecovery` signature and first log from:

```powershell
function Invoke-CriticalZoneRecovery([double]$Temperature) {
    $recordedSetpoint = [int]$script:LAST_SET_ACTION.Setpoint

    Log ("Step 3: {0} C is in safeband [{1}, {2}], but last setpoint {3} C is critical -> checking AC for recovery." -f $Temperature, $SAFEBAND_LOW, $SAFEBAND_HIGH, $recordedSetpoint)
```

to:

```powershell
function Invoke-CriticalZoneRecovery([double]$Temperature, [double]$SafebandLow, [double]$SafebandHigh) {
    $recordedSetpoint = [int]$script:LAST_SET_ACTION.Setpoint

    Log ("Step 3: {0} C is in safeband [{1}, {2}], but last setpoint {3} C is critical -> checking AC for recovery." -f $Temperature, $SafebandLow, $SafebandHigh, $recordedSetpoint)
```

Replace both actuator branch conditions and threshold logs so low-side and high-side actions use the decision computed from active bounds. Replace:

```powershell
    if ($t -lt $SAFEBAND_LOW) {
```

with:

```powershell
    if ($decision.TemperatureSide -eq 'low') {
```

Replace:

```powershell
            Log ("Step 5: temperature < {0} -> setpoint +1 ({1} -> {2}) C" -f $SAFEBAND_LOW, $sp, $new)
```

with:

```powershell
            Log ("Step 5: temperature < {0} -> setpoint +1 ({1} -> {2}) C" -f $activeLow, $sp, $new)
```

Replace:

```powershell
    elseif ($t -gt $SAFEBAND_HIGH) {
```

with:

```powershell
    elseif ($decision.TemperatureSide -eq 'high') {
```

Replace:

```powershell
            Log ("Step 6: temperature > {0} -> setpoint -1 ({1} -> {2}) C" -f $SAFEBAND_HIGH, $sp, $new)
```

with:

```powershell
            Log ("Step 6: temperature > {0} -> setpoint -1 ({1} -> {2}) C" -f $activeHigh, $sp, $new)
```

- [ ] **Step 7: Run tests and parse checks**

Run: `pwsh -NoProfile -File .\tests\decision-logic.tests.ps1`

Expected: PASS, including the runtime parser check and new runtime text assertions.

Run: `pwsh -File .\scripts\avd-ac-regulator.ps1 -Help`

Expected: help prints successfully without parser or startup validation errors.

Run: `pwsh -File .\scripts\avd-ac-regulator.ps1 -Help -SafebandAt '21:00,24.7,24.8' -SafebandAt '00:00,24.8,24.9'`

Expected: help prints successfully without PowerShell parameter binding errors. This catches regressions where `-SafebandAt` is implemented as a normal named parameter.

- [ ] **Step 8: Commit Task 2**

Run:

```powershell
git add -- scripts/avd-ac-regulator.ps1 tests/decision-logic.tests.ps1
$env:GIT_AUTHOR_DATE='2026-07-05T03:34:00+08:00'; $env:GIT_COMMITTER_DATE='2026-07-05T03:34:00+08:00'; git commit -m "feat: select safebands by time"
```

Expected: commit contains only runtime integration and runtime text tests.

---

### Task 3: Help Text And README Documentation

**Files:**
- Modify: `scripts/avd-ac-regulator.ps1`
- Modify: `README.md`
- Modify: `tests/decision-logic.tests.ps1`

**Interfaces:**
- Consumes: implemented `-SafebandAt` option from Task 2.
- Produces: user-facing documentation in built-in help and README.

- [ ] **Step 1: Add failing documentation text tests**

Append these assertions near the runtime text assertions in `tests/decision-logic.tests.ps1`:

```powershell
$readmeText = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\README.md') -Raw
Assert-True ($runtimeText -like '*-SafebandAt <HH:mm,low,high>*') 'help documents SafebandAt option'
Assert-True ($runtimeText -like '*21:00,24.7,24.8*') 'help shows SafebandAt example'
Assert-True ($readmeText -like '*`-SafebandAt <HH:mm,low,high>`*') 'README options table documents SafebandAt'
Assert-True ($readmeText -like '*-SafebandAt ''21:00,24.7,24.8''*') 'README examples show SafebandAt usage'
Assert-True ($readmeText -like '*latest schedule entry whose time is less than or equal to the current local time*') 'README documents active schedule selection'
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pwsh -NoProfile -File .\tests\decision-logic.tests.ps1`

Expected: FAIL on the new documentation text assertions.

- [ ] **Step 3: Update built-in help text**

In `Write-Usage` inside `scripts/avd-ac-regulator.ps1`, add this option after `-SafebandHigh`:

```text
  -SafebandAt <HH:mm,low,high>          Repeatable time-based safeband entry. Example: '21:00,24.7,24.8'
```

Add this example after the existing safeband example:

```text
  pwsh -File .\scripts\avd-ac-regulator.ps1 -SafebandAt '21:00,24.7,24.8' -SafebandAt '00:00,24.8,24.9' -SafebandAt '04:30,25,25.2'
```

- [ ] **Step 4: Update README option table, validation, examples, and decision notes**

In `README.md`, add this option table row after `-SafebandHigh`:

```markdown
| `-SafebandAt <HH:mm,low,high>` | none | Repeatable time-based safeband entry, for example `21:00,24.7,24.8`. When omitted, `-SafebandLow` and `-SafebandHigh` apply all night. |
```

Add this validation bullet after the scalar safeband validation bullet:

```markdown
- Each `-SafebandAt` entry must use `HH:mm,low,high`, with finite bounds, `low <= high`, and no duplicate `HH:mm` times.
```

Add this example after the existing widen-safeband example:

```markdown
# Use different safebands during different local-time spans.
pwsh -File .\scripts\avd-ac-regulator.ps1 `
  -SafebandAt '21:00,24.7,24.8' `
  -SafebandAt '00:00,24.8,24.9' `
  -SafebandAt '04:30,25,25.2'
```

Add this short paragraph near the start of the Decision logic section after the defaults paragraph:

```markdown
If `-SafebandAt` entries are provided, each cycle first selects the active range from local host time. The active range is the latest schedule entry whose time is less than or equal to the current local time; before the first entry of the day, selection wraps to the last entry from the previous day.
```

- [ ] **Step 5: Run documentation and parser tests**

Run: `pwsh -NoProfile -File .\tests\decision-logic.tests.ps1`

Expected: PASS.

Run: `pwsh -File .\scripts\avd-ac-regulator.ps1 -Help`

Expected: help output includes `-SafebandAt <HH:mm,low,high>` and the three-entry example.

- [ ] **Step 6: Commit Task 3**

Run:

```powershell
git add -- scripts/avd-ac-regulator.ps1 tests/decision-logic.tests.ps1 README.md
$env:GIT_AUTHOR_DATE='2026-07-05T04:48:00+08:00'; $env:GIT_COMMITTER_DATE='2026-07-05T04:48:00+08:00'; git commit -m "docs: document time-based safebands"
```

Expected: commit contains only help text, README updates, and documentation text assertions.

---

## Final Verification

- [ ] Run: `pwsh -NoProfile -File .\tests\decision-logic.tests.ps1`

Expected: all assertions print `PASS` and the command exits with code 0.

- [ ] Run: `pwsh -File .\scripts\avd-ac-regulator.ps1 -Help`

Expected: help prints without errors and documents `-SafebandAt`.

- [ ] Run: `git status --short`

Expected: no modified tracked files from this implementation remain uncommitted. Existing unrelated untracked `.kilo/` files may remain.

---

## Plan Self-Review

- Spec coverage: Task 1 covers parsing, validation, sorting, and active schedule resolution. Task 2 covers repeated `-SafebandAt` token capture, startup parsing, per-cycle active bounds, decision integration, actuator branching, critical recovery logging, and schedule logging. Task 3 covers built-in help and README documentation.
- Placeholder scan: no placeholder steps remain; all code-changing steps include concrete snippets and commands.
- Type consistency: schedule records consistently expose `After`, `Low`, and `High`; runtime consistently uses `$activeLow` and `$activeHigh` when calling `Get-CycleDecision`, logging critical recovery, and executing low-side/high-side intervention branches.
