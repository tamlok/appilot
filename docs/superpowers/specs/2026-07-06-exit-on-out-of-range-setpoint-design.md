# Exit on Out-of-Range Setpoint Design

## Purpose

When the regulator opens the Haier AC screen and reads an AC setpoint outside the configured automation range, the program should stop immediately instead of changing or recording a setpoint. This prevents automation from continuing after a manual or unexpected setpoint change outside the allowed values.

## Scope

The allowed set values are the configured inclusive automation range:

- `SetpointFloor <= setpoint <= SetpointCeiling` is allowed.
- `setpoint < SetpointFloor` or `setpoint > SetpointCeiling` is out of range and exits the program.

The boundary values remain allowed because existing critical-zone behavior intentionally uses the floor and ceiling as critical values. This change does not alter safeband thresholds, setpoint caps, OCR app-range filtering, shortcut labels, package names, or emulator launch flags.

## Runtime Behavior

After `Read-SetTemp` returns a non-null setpoint from the Haier screen, the runtime validates that value against `$SET_FLOOR` and `$SET_CEIL` before any adjustment, recovery, intervention record update, or critical-record clearing.

If the setpoint is out of range, the script logs a clear message including the observed setpoint and allowed range, then throws a sentinel terminating error. The main loop catches that sentinel and exits the process before waiting for another cycle.

The validation applies to both runtime paths that read the AC setpoint:

- Normal out-of-safeband intervention in `Invoke-Cycle`.
- Critical-zone recovery in `Invoke-CriticalZoneRecovery`.

## Error Handling

Use a distinct sentinel message for this condition, such as `AC setpoint is outside allowed range; exiting.` The existing AC-off sentinel remains unchanged. Other exceptions should continue to be logged by the cycle catch and then follow the existing loop behavior.

## Testing

Keep tests dependency-free. Extend `tests/decision-logic.tests.ps1` with structural assertions that prove:

- The runtime contains an out-of-range setpoint exit sentinel.
- The check is performed after each `Read-SetTemp` non-null result in both critical recovery and normal intervention.
- The main loop catch exits on the out-of-range sentinel before the next-cycle wait.

Run:

```powershell
pwsh -NoProfile -File .\tests\decision-logic.tests.ps1
```
