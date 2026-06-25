<#
.SYNOPSIS
  Wave 8 / F1 acceptance gate for ac-auto-regulator.

.DESCRIPTION
  One scripted gate that proves the system is vendor-agnostic AND works
  end-to-end deterministically. It runs, in order, exiting non-zero on the
  FIRST failure:

    1. Vendor-agnostic grep gate  - app/src/main must contain ZERO references
       to any vendor (tuya|haier|uplus|thingclips|thingSmart, case-insensitive).
       Vendor evidence strings are permitted only under app/src/test,
       app/src/androidTest and .omo/.
    2. Unit suite                 - :app:testDebugUnitTest (pure-JVM logic).
    3. Clean build                - :app:assembleDebug.
    4. Deterministic e2e          - :app:connectedDebugAndroidTest running ONLY
       com.vnote.appilot.service.RegulatorCycleTest on a CLEAN install
       (:app:uninstallDebug first). This drives a real launch->read->decide->tap
       cycle against the in-repo harness pair and asserts the actuator tap
       counter incremented by `step` - NO real vendors involved.

  The deterministic e2e + grep gate + unit suite are the binding acceptance.

.PARAMETER SkipE2e
  Skip the connected (on-device) steps; run only the grep gate + unit suite.
  Useful when no emulator/device is attached.

.NOTES
  Requires JAVA_HOME-capable JDK; the script pins it to Android Studio's bundled
  JBR if not already set. Run from anywhere - paths resolve to the repo root.
#>
[CmdletBinding()]
param(
    [switch]$SkipE2e
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent $PSScriptRoot
$E2eClass = 'com.vnote.appilot.service.RegulatorCycleTest'
$VendorPattern = 'tuya|haier|uplus|thingclips|thingSmart'

function Write-Section([string]$Title) {
    Write-Host ''
    Write-Host "==== $Title ====" -ForegroundColor Cyan
}

function Fail([string]$Message) {
    Write-Host "FAIL: $Message" -ForegroundColor Red
    exit 1
}

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
}

# Gradle wrapper invocation with a single lock/timeout retry (per Wave notes).
function Invoke-Gradle([string[]]$GradleArgs) {
    $wrapper = Join-Path $RepoRoot 'gradlew.bat'
    foreach ($attempt in 1..2) {
        Write-Host "> gradlew $($GradleArgs -join ' ')  (attempt $attempt)" -ForegroundColor DarkGray
        & $wrapper @GradleArgs
        if ($LASTEXITCODE -eq 0) { return }
        if ($attempt -eq 1) {
            Write-Host 'gradle failed (lock/timeout?); waiting 15s then retrying once...' -ForegroundColor Yellow
            Start-Sleep -Seconds 15
        }
    }
    Fail "gradle $($GradleArgs -join ' ') exited $LASTEXITCODE"
}

# ---- 1. Vendor-agnostic grep gate (HARD) ----------------------------------
Write-Section 'Vendor-agnostic grep gate (app/src/main)'
$mainDir = Join-Path $RepoRoot 'app\src\main'
$hits = Get-ChildItem -Path $mainDir -Recurse -File |
    Select-String -Pattern $VendorPattern -CaseSensitive:$false
if ($hits) {
    Write-Host 'Vendor strings found in product code:' -ForegroundColor Red
    $hits | ForEach-Object { Write-Host "  $($_.Path):$($_.LineNumber): $($_.Line.Trim())" }
    Fail 'grep gate: product code is NOT vendor-agnostic'
}
Write-Host "PASS: 0 vendor references in app/src/main (pattern: $VendorPattern)" -ForegroundColor Green

# ---- 2. Unit suite --------------------------------------------------------
Write-Section 'Unit suite (:app:testDebugUnitTest)'
Invoke-Gradle @(':app:testDebugUnitTest')
Write-Host 'PASS: unit suite green' -ForegroundColor Green

if ($SkipE2e) {
    Write-Section 'SUMMARY'
    Write-Host 'Grep gate + unit suite PASSED (e2e skipped via -SkipE2e).' -ForegroundColor Green
    exit 0
}

# ---- 3. Clean build -------------------------------------------------------
Write-Section 'Clean build (:app:assembleDebug)'
Invoke-Gradle @(':app:assembleDebug')
Write-Host 'PASS: assembleDebug green' -ForegroundColor Green

# ---- 4. Deterministic e2e on a CLEAN install ------------------------------
Write-Section 'Deterministic e2e (RegulatorCycleTest, clean install)'
# Uninstall first so connectedDebugAndroidTest reinstalls from scratch.
Invoke-Gradle @(':app:uninstallDebug')
Invoke-Gradle @(
    ':app:connectedDebugAndroidTest',
    "-Pandroid.testInstrumentationRunnerArguments.class=$E2eClass"
)
Write-Host "PASS: $E2eClass green on clean install" -ForegroundColor Green

Write-Section 'SUMMARY'
Write-Host 'F1 GATE PASSED: vendor-agnostic + unit + build + deterministic e2e all green.' -ForegroundColor Green
exit 0
