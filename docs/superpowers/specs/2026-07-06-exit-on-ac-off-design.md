# Exit on AC Off Design

## Purpose

When the regulator opens the Haier AC screen and detects that the AC is powered off, the program should stop immediately instead of skipping the current intervention and continuing later cycles. This avoids repeated automation against an intentionally powered-off AC.

## Scope

The change applies to both runtime paths that already inspect AC power state after opening the Haier control screen:

- Normal out-of-safeband setpoint intervention.
- Critical-zone recovery when temperature has returned to a safeband boundary.

No safeband thresholds, setpoint limits, emulator launch behavior, OCR logic, package names, or shortcut labels change.

## Runtime Behavior

After `Open-Ac` returns a screenshot, the script calls `Test-AcOn`. If that check returns false, the script logs that the AC is powered off and raises a terminating error. Because the script uses `$ErrorActionPreference = 'Stop'`, the run exits immediately and does not enter the next cycle.

If the AC is on, the existing behavior continues unchanged: read the setpoint, perform the selected adjustment or recovery, and update or clear the intervention record according to the current rules.

## Error Handling

The AC-off condition is intentional and explicit. It should use a clear terminating message such as `AC is powered off; exiting.` after the existing step log. Other runtime errors, such as failing to read the AC setpoint, keep their current handling.

## Testing

The existing dependency-free PowerShell test file should remain the verification entry point. Add structural coverage that confirms the runtime script contains the AC-off terminating behavior in both power-state checks. Then run:

```powershell
pwsh -NoProfile -File .\tests\decision-logic.tests.ps1
```
