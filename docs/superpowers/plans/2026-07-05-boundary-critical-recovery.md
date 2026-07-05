# Boundary Critical Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make critical-zone recovery happen only at the opposite safeband boundary from the recorded critical setpoint direction.

**Architecture:** Keep the behavior in pure decision logic so emulator and AC side effects remain unchanged. Add one focused helper in `scripts/avd-ac-regulator.logic.ps1`, then route the safe-temperature branch in `Get-CycleDecision` through it. Validate the behavior with dependency-free PowerShell tests in `tests/decision-logic.tests.ps1`.

**Tech Stack:** Windows PowerShell, pure PowerShell decision helpers, existing `tests/decision-logic.tests.ps1` test harness.

## Global Constraints

- Do not change safeband thresholds, setpoint caps, shortcut labels, package names, or emulator launch flags.
- Do not alter OCR, screenshot capture, tap coordinates, app startup, or AC runtime recovery side effects.
- Preserve the last intervention record when a critical setpoint is not recovered because the temperature is inside the safeband but away from the relevant boundary.
- Keep screenshot capture binary-safe; do not replace existing screenshot capture behavior.
- Use repo-relative paths only when editing files.
- Stage and commit only files changed for this feature; leave unrelated working-tree changes untouched. If an implementation file already has unrelated changes in the working tree, do not stage or commit that file until those unrelated hunks are separated or explicitly approved.

---

## File Structure

- Modify `scripts/avd-ac-regulator.logic.ps1`: owns pure decision records and helper predicates. Add `Test-CriticalRecoveryDue` and update `Get-CycleDecision` safe-branch behavior.
- Modify `tests/decision-logic.tests.ps1`: owns dependency-free tests for pure decision logic. Replace old anywhere-in-safeband critical recovery expectations with boundary-specific expectations.
- Do not modify `scripts/avd-ac-regulator.ps1`: runtime recovery already opens the AC, verifies power, reads setpoint OCR, moves to the nearest non-critical interior setpoint, and clears the record only after confirmation.

## Pre-Implementation Git Check

- [ ] **Step 1: Check for unrelated changes in implementation files**

Run:

```powershell
git status --short -- scripts/avd-ac-regulator.logic.ps1 tests/decision-logic.tests.ps1
git diff -- scripts/avd-ac-regulator.logic.ps1 tests/decision-logic.tests.ps1
```

Expected: `scripts/avd-ac-regulator.logic.ps1` and `tests/decision-logic.tests.ps1` are either clean or contain only changes that are explicitly part of this boundary critical recovery feature. If either file contains unrelated existing hunks, continue implementation carefully but skip the commit step until those hunks are separated or explicitly approved.

### Task 1: Add Boundary-Specific Decision Tests

**Files:**
- Modify: `tests/decision-logic.tests.ps1:64-69`
- Test: `tests/decision-logic.tests.ps1`

**Interfaces:**
- Consumes: Existing `Decide([double]$Temperature, $LastSetAction = $null, [int]$CycleIndex = 1)` helper.
- Consumes: Existing `New-SetActionRecord -Temperature <double> -Setpoint <int> -CycleIndex <int>` helper.
- Produces: Failing assertions that define the new `Get-CycleDecision` safe-branch contract.

- [ ] **Step 1: Replace old critical recovery tests with boundary-specific tests**

In `tests/decision-logic.tests.ps1`, replace the current block:

```powershell
$d = Decide -Temperature 24.85 -LastSetAction (New-SetActionRecord -Temperature 24.6 -Setpoint 25 -CycleIndex 1)
Assert-Equal 'RecoverCritical' $d.Action 'safeband with low critical record recovers'
Assert-True $d.ShouldOpenAc 'critical recovery should open AC'

$d = Decide -Temperature 24.85 -LastSetAction (New-SetActionRecord -Temperature 25.2 -Setpoint 28 -CycleIndex 1)
Assert-Equal 'RecoverCritical' $d.Action 'safeband with high critical record recovers'
```

with this exact block:

```powershell
$d = Decide -Temperature 24.8 -LastSetAction (New-SetActionRecord -Temperature 25.2 -Setpoint 25 -CycleIndex 1)
Assert-Equal 'RecoverCritical' $d.Action 'safeband low boundary with low critical record recovers'
Assert-True $d.ShouldOpenAc 'low-boundary critical recovery should open AC'

$d = Decide -Temperature 24.85 -LastSetAction (New-SetActionRecord -Temperature 25.2 -Setpoint 25 -CycleIndex 1)
Assert-Equal 'NoAction' $d.Action 'safeband middle with low critical record waits'
Assert-False $d.ShouldOpenAc 'low critical recovery away from low boundary should not open AC'

$d = Decide -Temperature 24.9 -LastSetAction (New-SetActionRecord -Temperature 24.6 -Setpoint 28 -CycleIndex 1)
Assert-Equal 'RecoverCritical' $d.Action 'safeband high boundary with high critical record recovers'
Assert-True $d.ShouldOpenAc 'high-boundary critical recovery should open AC'

$d = Decide -Temperature 24.85 -LastSetAction (New-SetActionRecord -Temperature 24.6 -Setpoint 28 -CycleIndex 1)
Assert-Equal 'NoAction' $d.Action 'safeband middle with high critical record waits'
Assert-False $d.ShouldOpenAc 'high critical recovery away from high boundary should not open AC'
```

- [ ] **Step 2: Run tests to verify the new expectations fail**

Run:

```powershell
pwsh -NoProfile -File .\tests\decision-logic.tests.ps1
```

Expected: at least the two middle-boundary waiting tests fail because current logic recovers critical setpoints anywhere inside the safeband.

### Task 2: Implement Boundary-Specific Recovery Predicate

**Files:**
- Modify: `scripts/avd-ac-regulator.logic.ps1:108-128`
- Test: `tests/decision-logic.tests.ps1`

**Interfaces:**
- Consumes: Existing `Test-CriticalSetpoint -Setpoint <int> -SetpointFloor <int> -SetpointCeiling <int>`.
- Produces: `Test-CriticalRecoveryDue -Temperature <double> -LastSetAction <object> -SafebandLow <double> -SafebandHigh <double> -SetpointFloor <int> -SetpointCeiling <int>` returning `[bool]`.

- [ ] **Step 1: Add the helper after `Get-NearestNonCriticalSetpoint`**

In `scripts/avd-ac-regulator.logic.ps1`, insert this function immediately after `Get-NearestNonCriticalSetpoint`:

```powershell
function Test-CriticalRecoveryDue {
    param(
        [Parameter(Mandatory = $true)][double]$Temperature,
        $LastSetAction,
        [Parameter(Mandatory = $true)][double]$SafebandLow,
        [Parameter(Mandatory = $true)][double]$SafebandHigh,
        [Parameter(Mandatory = $true)][int]$SetpointFloor,
        [Parameter(Mandatory = $true)][int]$SetpointCeiling
    )

    if ($null -eq $LastSetAction) { return $false }

    $setpoint = [int]$LastSetAction.Setpoint
    if (-not (Test-CriticalSetpoint -Setpoint $setpoint -SetpointFloor $SetpointFloor -SetpointCeiling $SetpointCeiling)) {
        return $false
    }

    if ($setpoint -le $SetpointFloor) { return ($Temperature -eq $SafebandLow) }
    return ($Temperature -eq $SafebandHigh)
}
```

- [ ] **Step 2: Run tests to verify behavior still fails until wired in**

Run:

```powershell
pwsh -NoProfile -File .\tests\decision-logic.tests.ps1
```

Expected: the same boundary waiting assertions still fail because `Get-CycleDecision` does not use the helper yet.

### Task 3: Wire Helper Into Cycle Decision

**Files:**
- Modify: `scripts/avd-ac-regulator.logic.ps1:222-227`
- Test: `tests/decision-logic.tests.ps1`

**Interfaces:**
- Consumes: `Test-CriticalRecoveryDue -Temperature <double> -LastSetAction <object> -SafebandLow <double> -SafebandHigh <double> -SetpointFloor <int> -SetpointCeiling <int>` returning `[bool]`.
- Produces: `Get-CycleDecision` returns `RecoverCritical` only when the helper returns true; otherwise safe readings return `NoAction`.

- [ ] **Step 1: Update the `side -eq 'safe'` branch**

In `scripts/avd-ac-regulator.logic.ps1`, replace this block inside `Get-CycleDecision`:

```powershell
if ($side -eq 'safe') {
    if ($null -ne $LastSetAction -and (Test-CriticalSetpoint -Setpoint ([int]$LastSetAction.Setpoint) -SetpointFloor $SetpointFloor -SetpointCeiling $SetpointCeiling)) {
        return (New-CycleDecision -Action 'RecoverCritical' -Reason 'temperature in safeband and last setpoint is critical' -TemperatureSide $side -ShouldOpenAc $true)
    }
    return (New-CycleDecision -Action 'NoAction' -Reason 'temperature in safeband' -TemperatureSide $side)
}
```

with this exact block:

```powershell
if ($side -eq 'safe') {
    if (Test-CriticalRecoveryDue -Temperature $Temperature -LastSetAction $LastSetAction -SafebandLow $SafebandLow -SafebandHigh $SafebandHigh -SetpointFloor $SetpointFloor -SetpointCeiling $SetpointCeiling) {
        return (New-CycleDecision -Action 'RecoverCritical' -Reason 'temperature at safeband boundary and last setpoint is critical' -TemperatureSide $side -ShouldOpenAc $true)
    }
    return (New-CycleDecision -Action 'NoAction' -Reason 'temperature in safeband' -TemperatureSide $side)
}
```

- [ ] **Step 2: Run decision tests**

Run:

```powershell
pwsh -NoProfile -File .\tests\decision-logic.tests.ps1
```

Expected: the command exits with status 0, prints only `PASS ...` assertion lines, and throws no failure summary.

### Task 4: Review Diff And Commit Feature

**Files:**
- Modify: `scripts/avd-ac-regulator.logic.ps1`
- Modify: `tests/decision-logic.tests.ps1`

**Interfaces:**
- Consumes: Passing test results from Task 3.
- Produces: One focused commit for the implementation, with unrelated working-tree changes left unstaged.

- [ ] **Step 1: Inspect changed files**

Run:

```powershell
git status --short
git diff -- scripts/avd-ac-regulator.logic.ps1 tests/decision-logic.tests.ps1
git log --oneline -10
```

Expected: the implementation diff touches only the helper, the `Get-CycleDecision` safe-branch update, and the boundary-specific decision tests. Other pre-existing files may appear in status, but must not be staged. If either implementation file includes unrelated hunks, skip Steps 2 and 3 and report that the feature is implemented and tested but not committed because the file contains unrelated changes.

- [ ] **Step 2: Stage only clean feature files**

Run:

```powershell
git add -- scripts/avd-ac-regulator.logic.ps1 tests/decision-logic.tests.ps1
git diff --cached --stat
git diff --cached -- scripts/avd-ac-regulator.logic.ps1 tests/decision-logic.tests.ps1
```

Expected: staged diff contains only the helper, the `Get-CycleDecision` branch update, and the boundary-specific decision tests. If unrelated hunks are staged, run `git restore --staged -- scripts/avd-ac-regulator.logic.ps1 tests/decision-logic.tests.ps1` and do not commit until unrelated hunks are separated or explicitly approved.

- [ ] **Step 3: Commit at an already-reached night-time timestamp**

Choose a random night-time timestamp that has already occurred. Use today if the selected time has already occurred; otherwise use yesterday. Then run the commit command with both author and committer dates set to that timestamp. Example when `2026-07-05T22:07:00+08:00` has already occurred:

```powershell
$env:GIT_AUTHOR_DATE='2026-07-05T22:07:00+08:00'; $env:GIT_COMMITTER_DATE='2026-07-05T22:07:00+08:00'; git commit -m "feat: recover critical setpoints at safeband boundaries"
```

Expected: commit succeeds and includes only `scripts/avd-ac-regulator.logic.ps1` and `tests/decision-logic.tests.ps1`.

## Verification

Before reporting completion, run:

```powershell
pwsh -NoProfile -File .\tests\decision-logic.tests.ps1
git status --short
```

Expected: the test command exits with status 0, prints only `PASS ...` assertion lines, and throws no failure summary. Status may still show unrelated pre-existing changes. If the feature was committed, the feature files should be clean; if unrelated hunks prevented a safe commit, status may still show the implementation files as modified.

## Self-Review Notes

- Spec coverage: Task 1 defines boundary-specific expectations; Task 2 adds the pure helper; Task 3 wires it into `Get-CycleDecision`; Task 4 commits only the feature files. Runtime recovery behavior remains unchanged by file structure constraint.
- Placeholder scan: no placeholder work remains; every code-editing step includes exact code blocks.
- Type consistency: `Test-CriticalRecoveryDue` parameters match its call in `Get-CycleDecision`; tests use existing `Decide` and `New-SetActionRecord` helpers.
