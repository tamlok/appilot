# Time-Based Safeband Design

## Overview

The AC regulator currently uses one safeband for the whole run, configured with `-SafebandLow` and `-SafebandHigh`. Add an optional repeatable command-line parameter so the user can provide different safebands that become active after specific local times.

When no time-based safeband is supplied, behavior remains unchanged: the script uses `-SafebandLow` and `-SafebandHigh` for every cycle.

## Command-Line Interface

Add a repeatable option named `-SafebandAt`:

```powershell
pwsh -File .\scripts\avd-ac-regulator.ps1 `
  -SafebandAt '21:00,24.7,24.8' `
  -SafebandAt '00:00,24.8,24.9' `
  -SafebandAt '04:30,25,25.2'
```

Each value has the format:

```text
HH:mm,low,high
```

Fields:

- `HH:mm`: local host time in 24-hour format.
- `low`: lower no-action temperature bound.
- `high`: upper no-action temperature bound.

The existing `-SafebandLow` and `-SafebandHigh` options stay supported. They remain the default all-night safeband and are used whenever `-SafebandAt` is omitted.

## Runtime Behavior

At startup, parse and validate all `-SafebandAt` entries into schedule records with `After`, `Low`, and `High` fields.

At each cycle, resolve the active safeband from current local host time:

- Choose the latest schedule entry whose `After` time is less than or equal to the current time.
- If the current time is earlier than the first scheduled entry, wrap to the last entry, treating it as the previous day's active range.
- Pass the resolved `Low` and `High` values into existing decision logic for that cycle.

For the example schedule:

- `21:00` through `23:59`: `[24.7, 24.8]`
- `00:00` through `04:29`: `[24.8, 24.9]`
- `04:30` through `20:59`: `[25.0, 25.2]`

## Validation

Reject invalid schedules before any emulator work begins:

- Entry must contain exactly three comma-separated fields.
- Time must be valid `HH:mm` 24-hour time.
- Bounds must be finite numbers.
- `low <= high`.
- Duplicate `HH:mm` entries are rejected to avoid ambiguous active ranges.

Existing validation for scalar `-SafebandLow` and `-SafebandHigh` remains unchanged.

## Components

Add small pure helper functions to `scripts/avd-ac-regulator.logic.ps1` so parsing and schedule resolution are dependency-free and unit-testable:

- `ConvertTo-SafebandSchedule`: parse and validate `-SafebandAt` strings.
- `Get-ActiveSafeband`: choose the active schedule record for a supplied `TimeSpan`.

Keep temperature decision functions unchanged. They should continue to accept scalar `SafebandLow` and `SafebandHigh` values.

Update `scripts/avd-ac-regulator.ps1` to:

- Add `[string[]]$SafebandAt` to the parameter block.
- Build the schedule once at startup.
- Resolve the active safeband inside `Invoke-Cycle` before calling `Get-CycleDecision`.
- Use the active values in logs and setpoint adjustment comparisons.

Update `README.md` and built-in `-Help` output to document the new option and examples.

## Logging

Startup logs should show either the scalar safeband or the parsed schedule. Cycle logs should show the active safeband when a decision is made so behavior is auditable from the console output.

## Testing

Extend `tests/decision-logic.tests.ps1` with dependency-free tests for:

- Parsing one valid `-SafebandAt` entry.
- Parsing and sorting multiple entries.
- Selecting the active safeband for exact match, between entries, and wraparound before the first entry.
- Rejecting malformed entries, invalid times, non-finite bounds, reversed bounds, and duplicate times.
- Confirming the runtime script still parses successfully.

Existing decision tests should continue to pass unchanged.
