# Boundary Critical Recovery Design

## Goal

Change critical-zone recovery so it only happens when a safeband reading is at risk of crossing the opposite side of the safeband. This keeps the automation from leaving a critical AC setpoint too early while still recovering before the temperature is likely to overshoot outside the safeband.

## Current Behavior

The decision logic returns `RecoverCritical` whenever all of these are true:

- The current temperature is inside the active safeband.
- A previous setpoint intervention record exists.
- The recorded intervention setpoint is critical: `<= SetpointFloor` or `>= SetpointCeiling`.

The runtime then opens the AC, verifies it is powered on, reads the actual setpoint, and moves any still-critical actual setpoint to the nearest non-critical interior setpoint.

## New Behavior

Critical-zone recovery inside the safeband becomes boundary-specific:

- A low-critical recorded setpoint, such as `25`, recovers only when the current temperature equals `SafebandLow`.
- A high-critical recorded setpoint, such as `28`, recovers only when the current temperature equals `SafebandHigh`.
- A critical recorded setpoint does not recover for safeband readings away from the relevant boundary.
- The existing intervention record is preserved when recovery is not due, so a later boundary reading can still trigger recovery.

Example: with safeband `[24.8, 24.9]`, if the last recorded setpoint is `25` after cooling from above the safeband, recovery waits until the current temperature is `24.8`. At that point the automation moves the setpoint to `26`, because keeping `25` risks dropping below the safeband.

## Approach

Implement the boundary check in pure decision logic rather than runtime side effects.

Add or update a small helper in `scripts/avd-ac-regulator.logic.ps1` to answer whether a critical recorded setpoint should recover at the current safeband position:

- Return false when there is no last intervention record.
- Return false when the recorded setpoint is not critical.
- Return true for low-critical recorded setpoints only when `Temperature == SafebandLow`.
- Return true for high-critical recorded setpoints only when `Temperature == SafebandHigh`.

`Get-CycleDecision` will use that helper in its existing `side -eq 'safe'` branch. Runtime recovery in `scripts/avd-ac-regulator.ps1` remains unchanged.

## Tests

Update `tests/decision-logic.tests.ps1` to cover the new decision behavior:

- Low-critical setpoint at `SafebandLow` returns `RecoverCritical` and opens AC.
- Low-critical setpoint away from `SafebandLow` returns `NoAction`.
- High-critical setpoint at `SafebandHigh` returns `RecoverCritical` and opens AC.
- High-critical setpoint away from `SafebandHigh` returns `NoAction`.

Keep existing tests for non-critical records, out-of-safeband intervention, improving readings, same-temperature retry gates, and safeband schedules.

## Scope

This change does not alter safeband thresholds, setpoint floor or ceiling values, OCR, emulator behavior, AC tap logic, app package names, shortcuts, or launch flags.
