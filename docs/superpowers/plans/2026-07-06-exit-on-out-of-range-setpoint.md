# Exit on Out-of-Range Setpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the regulator immediately when a readable Haier AC setpoint is outside the configured inclusive automation range.

**Architecture:** Add a small runtime helper that validates a non-null setpoint against `$SET_FLOOR` and `$SET_CEIL`. Call it immediately after each successful `Read-SetTemp` result, and extend the main loop catch to exit on the distinct out-of-range sentinel while preserving the existing AC-off sentinel.

**Tech Stack:** PowerShell 5+/pwsh, Android emulator automation script, dependency-free PowerShell structural tests.

## Global Constraints

- `SetpointFloor <= setpoint <= SetpointCeiling` is allowed.
- `setpoint < SetpointFloor` or `setpoint > SetpointCeiling` is out of range and exits the program.
- Boundary values remain allowed because existing critical-zone behavior intentionally uses the floor and ceiling as critical values.
- Do not alter safeband thresholds, setpoint caps, OCR app-range filtering, shortcut labels, package names, or emulator launch flags.
- Apply validation after `Read-SetTemp` returns a non-null setpoint and before adjustment, recovery, intervention record update, or critical-record clearing.
- Preserve existing AC-off behavior and unrelated uncommitted safeband parser edits.
- Do not commit implementation changes unless explicitly requested and allowed by the repository's nighttime commit rule.

---

## File Structure

- Modify: `scripts/avd-ac-regulator.ps1` - add setpoint range exit helper, call it in both setpoint-read paths, and exit from the main loop catch on the new sentinel.
- Modify: `tests/decision-logic.tests.ps1` - add structural assertions proving the helper, call sites, and catch sentinel exist in the expected order.

### Task 1: Terminate on Out-of-Range Setpoint

**Files:**
- Modify: `scripts/avd-ac-regulator.ps1:223-242`
- Modify: `scripts/avd-ac-regulator.ps1:694-700`
- Modify: `scripts/avd-ac-regulator.ps1:758-760`
- Modify: `scripts/avd-ac-regulator.ps1:897-902`
- Modify: `tests/decision-logic.tests.ps1:193-207`

**Interfaces:**
- Consumes: existing `Log`, `$SET_FLOOR`, `$SET_CEIL`, and `Read-SetTemp` values.
- Produces: `Assert-AllowedSetpoint([int]$Setpoint)` that throws `AC setpoint is outside allowed range; exiting.` when `$Setpoint -lt $SET_FLOOR -or $Setpoint -gt $SET_CEIL`.

- [ ] **Step 1: Write the failing structural test**

Add these assertions after the existing AC-off structural variables in `tests/decision-logic.tests.ps1`:

```powershell
$setpointRangeHelper = $runtimeText.IndexOf('function Assert-AllowedSetpoint')
$outOfRangeSentinel = $runtimeText.IndexOf("throw 'AC setpoint is outside allowed range; exiting.'", $setpointRangeHelper)
$criticalRecoverySetpointRead = $runtimeText.IndexOf('$sp = Read-SetTemp $shot', $criticalRecoveryCall)
$criticalRecoverySetpointGuard = $runtimeText.IndexOf('Assert-AllowedSetpoint ([int]$sp)', $criticalRecoverySetpointRead)
$cycleSetpointRead = $runtimeText.IndexOf('$sp = Read-SetTemp $shot', $cycleCall)
$cycleSetpointGuard = $runtimeText.IndexOf('Assert-AllowedSetpoint ([int]$sp)', $cycleSetpointRead)
$mainCatchOutOfRangeCheck = $runtimeText.IndexOf('if ($_.Exception.Message -eq ''AC setpoint is outside allowed range; exiting.'')', $mainCatch)
$mainCatchOutOfRangeExit = $(if ($mainCatchOutOfRangeCheck -ge 0) { $runtimeText.IndexOf('exit 1', $mainCatchOutOfRangeCheck) } else { -1 })
Assert-True ($setpointRangeHelper -ge 0) 'runtime exposes setpoint range guard'
Assert-True ($outOfRangeSentinel -gt $setpointRangeHelper) 'setpoint range guard exits on out-of-range setpoint'
Assert-True ($criticalRecoverySetpointGuard -gt $criticalRecoverySetpointRead) 'critical recovery validates setpoint range after read'
Assert-True ($cycleSetpointGuard -gt $cycleSetpointRead) 'normal intervention validates setpoint range after read'
Assert-True ($mainCatchOutOfRangeCheck -gt $mainCatch) 'main loop checks out-of-range setpoint sentinel'
Assert-True ($mainCatchOutOfRangeExit -gt $mainCatchOutOfRangeCheck) 'main loop exits on out-of-range setpoint sentinel'
Assert-True ($mainCatchOutOfRangeExit -lt $postCycleWait) 'main loop exits on out-of-range setpoint before waiting for another cycle'
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pwsh -NoProfile -File .\tests\decision-logic.tests.ps1`

Expected before implementation: the new setpoint range guard assertions fail because the helper, call sites, and catch sentinel do not exist.

- [ ] **Step 3: Add the setpoint range guard**

Add this helper near the existing intervention-record helpers in `scripts/avd-ac-regulator.ps1`:

```powershell
function Assert-AllowedSetpoint([int]$Setpoint) {
    if ($Setpoint -lt $SET_FLOOR -or $Setpoint -gt $SET_CEIL) {
        Log ("Step 4: AC setpoint {0} C is outside allowed range [{1}, {2}] C." -f $Setpoint, $SET_FLOOR, $SET_CEIL)
        throw 'AC setpoint is outside allowed range; exiting.'
    }
}
```

- [ ] **Step 4: Call the guard in critical-zone recovery**

Change this block in `Invoke-CriticalZoneRecovery`:

```powershell
    $sp = Read-SetTemp $shot
    if ($null -eq $sp) {
        Log 'Step 4: could not read AC setpoint -> critical-zone recovery skipped.'
        return
    }
```

to:

```powershell
    $sp = Read-SetTemp $shot
    if ($null -eq $sp) {
        Log 'Step 4: could not read AC setpoint -> critical-zone recovery skipped.'
        return
    }
    Assert-AllowedSetpoint ([int]$sp)
```

- [ ] **Step 5: Call the guard in normal intervention**

Change this block in `Invoke-Cycle`:

```powershell
    $sp = Read-SetTemp $shot
    if ($null -eq $sp) { throw 'Step 4: could not read AC setpoint' }
    Log ("Step 4: AC is on; current setpoint = {0} C" -f $sp)
```

to:

```powershell
    $sp = Read-SetTemp $shot
    if ($null -eq $sp) { throw 'Step 4: could not read AC setpoint' }
    Assert-AllowedSetpoint ([int]$sp)
    Log ("Step 4: AC is on; current setpoint = {0} C" -f $sp)
```

- [ ] **Step 6: Extend main-loop sentinel handling**

Change this catch block in `scripts/avd-ac-regulator.ps1`:

```powershell
    } catch {
        Log "Cycle #$cycle failed: $($_.Exception.Message)"
        if ($_.Exception.Message -eq 'AC is powered off; exiting.') {
            exit 1
        }
    }
```

to:

```powershell
    } catch {
        Log "Cycle #$cycle failed: $($_.Exception.Message)"
        if ($_.Exception.Message -eq 'AC is powered off; exiting.') {
            exit 1
        }
        if ($_.Exception.Message -eq 'AC setpoint is outside allowed range; exiting.') {
            exit 1
        }
    }
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `pwsh -NoProfile -File .\tests\decision-logic.tests.ps1`

Expected after implementation: all assertions print `PASS` and the command exits with code 0.

- [ ] **Step 8: Inspect the final diff**

Run: `git diff -- scripts/avd-ac-regulator.ps1 tests/decision-logic.tests.ps1 docs/superpowers/plans/2026-07-06-exit-on-out-of-range-setpoint.md`

Expected: the diff contains the setpoint guard helper, two guard calls, main-loop out-of-range sentinel exit, structural tests, and this plan while preserving existing AC-off and unrelated safeband parser changes.
