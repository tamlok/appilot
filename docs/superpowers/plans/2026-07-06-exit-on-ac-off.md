# Exit on AC Off Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the regulator immediately whenever the Haier AC screen indicates the AC is powered off.

**Architecture:** Keep the existing runtime decision flow intact. Change the two existing `Test-AcOn` false branches so they log the powered-off condition and throw a sentinel terminating error. Update the main loop catch block to exit the process when that sentinel is seen, because ordinary cycle exceptions are logged and the loop otherwise continues.

**Tech Stack:** PowerShell 5+/pwsh, Android emulator automation script, dependency-free PowerShell structural tests.

## Global Constraints

- Apply only to normal out-of-safeband intervention and critical-zone recovery paths after `Open-Ac` returns a screenshot.
- Do not change safeband thresholds, setpoint limits, emulator launch behavior, OCR logic, package names, or shortcut labels.
- Preserve existing behavior when AC is on, when no action is due, and when non-AC-off exceptions occur.
- Keep patches minimal and do not overwrite unrelated uncommitted edits in `scripts/avd-ac-regulator.ps1` or `tests/decision-logic.tests.ps1`.
- Use repository-relative paths in patches.
- Do not commit implementation changes unless explicitly requested and allowed by the repository's nighttime commit rule.

---

## File Structure

- Modify: `scripts/avd-ac-regulator.ps1` - runtime loop and critical-zone recovery AC power-state handling plus main-loop AC-off termination.
- Modify: `tests/decision-logic.tests.ps1` - structural assertions proving both AC-off branches terminate.

### Task 1: Terminate on AC-Off Detection

**Files:**
- Modify: `scripts/avd-ac-regulator.ps1:689-692`
- Modify: `scripts/avd-ac-regulator.ps1:754-757`
- Modify: `scripts/avd-ac-regulator.ps1:897-899`
- Modify: `tests/decision-logic.tests.ps1:185-190`

**Interfaces:**
- Consumes: existing `Open-Ac`, `Test-AcOn`, and `Log` functions.
- Produces: existing runtime behavior plus a sentinel `throw 'AC is powered off; exiting.'` in both AC-off branches and `exit 1` from the main loop catch when that sentinel is caught.

- [ ] **Step 1: Write the failing structural test**

Add these assertions after the existing `$openAcCall` calculation in `tests/decision-logic.tests.ps1`:

```powershell
$criticalRecoveryCall = $runtimeText.IndexOf('function Invoke-CriticalZoneRecovery')
$criticalRecoveryAcOff = $runtimeText.IndexOf("Log 'Step 4: AC is powered off -> critical-zone recovery skipped.'", $criticalRecoveryCall)
$criticalRecoveryExit = $runtimeText.IndexOf("throw 'AC is powered off; exiting.'", $criticalRecoveryAcOff)
$cycleAcOff = $runtimeText.IndexOf("Log 'Step 4: AC is powered off -> no setpoint intervention occurred.'", $cycleCall)
$cycleExit = $runtimeText.IndexOf("throw 'AC is powered off; exiting.'", $cycleAcOff)
$mainCatch = $runtimeText.IndexOf('} catch {')
$mainCatchAcOffCheck = $runtimeText.IndexOf('if ($_.Exception.Message -eq ''AC is powered off; exiting.'')', $mainCatch)
$mainCatchExit = $runtimeText.IndexOf('exit 1', $mainCatchAcOffCheck)
$postCycleWait = $runtimeText.IndexOf('Waiting $IntervalMinutes minutes before next cycle', $mainCatch)
Assert-True ($criticalRecoveryAcOff -gt $criticalRecoveryCall) 'critical recovery detects AC off'
Assert-True ($criticalRecoveryExit -gt $criticalRecoveryAcOff) 'critical recovery exits when AC is off'
Assert-True ($cycleAcOff -gt $cycleCall) 'normal intervention detects AC off'
Assert-True ($cycleExit -gt $cycleAcOff) 'normal intervention exits when AC is off'
Assert-True ($mainCatchAcOffCheck -gt $mainCatch) 'main loop checks AC-off sentinel'
Assert-True ($mainCatchExit -gt $mainCatchAcOffCheck) 'main loop exits on AC-off sentinel'
Assert-True ($mainCatchExit -lt $postCycleWait) 'main loop exits before waiting for another cycle'
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pwsh -NoProfile -File .\tests\decision-logic.tests.ps1`

Expected before implementation: the new exit assertions fail because the runtime still returns from AC-off branches and the main loop catch has no AC-off sentinel handling.

- [ ] **Step 3: Implement critical-zone recovery termination**

Replace this branch in `scripts/avd-ac-regulator.ps1`:

```powershell
    if (-not (Test-AcOn $shot)) {
        Log 'Step 4: AC is powered off -> critical-zone recovery skipped.'
        return
    }
```

with:

```powershell
    if (-not (Test-AcOn $shot)) {
        Log 'Step 4: AC is powered off -> critical-zone recovery skipped.'
        throw 'AC is powered off; exiting.'
    }
```

- [ ] **Step 4: Implement normal intervention termination**

Replace this branch in `scripts/avd-ac-regulator.ps1`:

```powershell
    if (-not (Test-AcOn $shot)) {
        Log 'Step 4: AC is powered off -> no setpoint intervention occurred.'
        return
    }
```

with:

```powershell
    if (-not (Test-AcOn $shot)) {
        Log 'Step 4: AC is powered off -> no setpoint intervention occurred.'
        throw 'AC is powered off; exiting.'
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `pwsh -NoProfile -File .\tests\decision-logic.tests.ps1`

Expected after branch implementation only: the branch exit assertions pass, but the main-loop sentinel assertions still fail.

- [ ] **Step 6: Implement main-loop termination for the sentinel**

Replace this catch block in `scripts/avd-ac-regulator.ps1`:

```powershell
    } catch {
        Log "Cycle #$cycle failed: $($_.Exception.Message)"
    }
```

with:

```powershell
    } catch {
        Log "Cycle #$cycle failed: $($_.Exception.Message)"
        if ($_.Exception.Message -eq 'AC is powered off; exiting.') {
            exit 1
        }
    }
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `pwsh -NoProfile -File .\tests\decision-logic.tests.ps1`

Expected after implementation: all assertions print `PASS` and the command exits with code 0.

- [ ] **Step 8: Inspect the final diff**

Run: `git diff -- scripts/avd-ac-regulator.ps1 tests/decision-logic.tests.ps1`

Expected: the diff contains only the two `throw 'AC is powered off; exiting.'` runtime changes, the main-loop sentinel `exit 1`, and the structural assertions, while preserving unrelated pre-existing edits.
