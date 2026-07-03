$ErrorActionPreference = 'Stop'

. "$PSScriptRoot\..\scripts\avd-ac-regulator.logic.ps1"

$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot '..\scripts\avd-ac-regulator.ps1'), [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count) {
    $errors | ForEach-Object { Write-Host $_.Message }
    throw 'Runtime script has parser errors.'
}

$script:Failures = 0

function Assert-Equal($Expected, $Actual, [string]$Name) {
    if ($Expected -ne $Actual) {
        $script:Failures++
        Write-Host ("FAIL {0}: expected={1}; actual={2}" -f $Name, $Expected, $Actual)
    } else {
        Write-Host ("PASS {0}" -f $Name)
    }
}

function Assert-True([bool]$Condition, [string]$Name) {
    Assert-Equal $true $Condition $Name
}

function Assert-False([bool]$Condition, [string]$Name) {
    Assert-Equal $false $Condition $Name
}

function Decide([double]$Temperature, $LastSetAction = $null, [int]$CycleIndex = 1) {
    Get-CycleDecision `
        -Temperature $Temperature `
        -LastSetAction $LastSetAction `
        -CycleIndex $CycleIndex `
        -SafebandLow 24.8 `
        -SafebandHigh 24.9 `
        -SetpointFloor 25 `
        -SetpointCeiling 28 `
        -UnchangedThresholdCycles 4
}

Assert-True (Test-InSafeband -Temperature 24.8 -SafebandLow 24.8 -SafebandHigh 24.9) 'safeband includes low bound'
Assert-True (Test-InSafeband -Temperature 24.9 -SafebandLow 24.8 -SafebandHigh 24.9) 'safeband includes high bound'
Assert-False (Test-InSafeband -Temperature 24.7 -SafebandLow 24.8 -SafebandHigh 24.9) 'below safeband is outside'

Assert-True (Test-CriticalSetpoint -Setpoint 25 -SetpointFloor 25 -SetpointCeiling 28) 'floor is critical'
Assert-True (Test-CriticalSetpoint -Setpoint 28 -SetpointFloor 25 -SetpointCeiling 28) 'ceiling is critical'
Assert-True (Test-CriticalSetpoint -Setpoint 24 -SetpointFloor 25 -SetpointCeiling 28) 'below floor is critical'
Assert-True (Test-CriticalSetpoint -Setpoint 29 -SetpointFloor 25 -SetpointCeiling 28) 'above ceiling is critical'
Assert-False (Test-CriticalSetpoint -Setpoint 26 -SetpointFloor 25 -SetpointCeiling 28) 'interior low is non-critical'
Assert-False (Test-CriticalSetpoint -Setpoint 27 -SetpointFloor 25 -SetpointCeiling 28) 'interior high is non-critical'
Assert-Equal 26 (Get-NearestNonCriticalSetpoint -Setpoint 24 -SetpointFloor 25 -SetpointCeiling 28) 'below floor recovers to floor plus one'
Assert-Equal 27 (Get-NearestNonCriticalSetpoint -Setpoint 29 -SetpointFloor 25 -SetpointCeiling 28) 'above ceiling recovers to ceiling minus one'

$d = Decide -Temperature 24.85
Assert-Equal 'NoAction' $d.Action 'safeband without record does not open AC'
Assert-False $d.ShouldOpenAc 'safeband without record should not open AC'

$d = Decide -Temperature 24.85 -LastSetAction (New-SetActionRecord -Temperature 24.6 -Setpoint 26 -CycleIndex 1)
Assert-Equal 'NoAction' $d.Action 'safeband with non-critical record does not open AC'

$d = Decide -Temperature 24.85 -LastSetAction (New-SetActionRecord -Temperature 24.6 -Setpoint 25 -CycleIndex 1)
Assert-Equal 'RecoverCritical' $d.Action 'safeband with low critical record recovers'
Assert-True $d.ShouldOpenAc 'critical recovery should open AC'

$d = Decide -Temperature 24.85 -LastSetAction (New-SetActionRecord -Temperature 25.2 -Setpoint 28 -CycleIndex 1)
Assert-Equal 'RecoverCritical' $d.Action 'safeband with high critical record recovers'

$d = Decide -Temperature 24.7
Assert-Equal 'IncreaseSetpoint' $d.Action 'low temperature without record increases setpoint'
Assert-True $d.ShouldOpenAc 'low intervention should open AC'
Assert-Equal 'low' $d.TemperatureSide 'low intervention reports low side'
Assert-True ($null -ne $d.PSObject.Properties['Reason']) 'decision includes reason property'

$d = Decide -Temperature 25.0
Assert-Equal 'DecreaseSetpoint' $d.Action 'high temperature without record decreases setpoint'
Assert-True $d.ShouldOpenAc 'high intervention should open AC'
Assert-Equal 'high' $d.TemperatureSide 'high intervention reports high side'

$last = New-SetActionRecord -Temperature 24.6 -Setpoint 26 -CycleIndex 1
$d = Decide -Temperature 24.7 -LastSetAction $last -CycleIndex 2
Assert-Equal 'UpdateObservation' $d.Action 'low temperature improving updates observation without opening AC'
Assert-False $d.ShouldOpenAc 'improving low temperature should not open AC'
Assert-True $d.ShouldUpdateObservation 'improving low temperature should update observation'

$last = New-SetActionRecord -Temperature 25.1 -Setpoint 27 -CycleIndex 1
$d = Decide -Temperature 25.0 -LastSetAction $last -CycleIndex 2
Assert-Equal 'UpdateObservation' $d.Action 'high temperature improving updates observation without opening AC'

$last = New-SetActionRecord -Temperature 24.7 -Setpoint 26 -CycleIndex 1
$d = Decide -Temperature 24.7 -LastSetAction $last -CycleIndex 5
Assert-Equal 'NoAction' $d.Action 'same low temperature at threshold does not retry'
$d = Decide -Temperature 24.7 -LastSetAction $last -CycleIndex 6
Assert-Equal 'IncreaseSetpoint' $d.Action 'same low temperature after threshold retries'

$last = New-SetActionRecord -Temperature 24.7 -Setpoint 26 -CycleIndex 1
$d = Decide -Temperature 24.6 -LastSetAction $last -CycleIndex 2
Assert-Equal 'IncreaseSetpoint' $d.Action 'low temperature worsening retries immediately'

$last = New-SetActionRecord -Temperature 25.0 -Setpoint 27 -CycleIndex 1
$d = Decide -Temperature 25.1 -LastSetAction $last -CycleIndex 2
Assert-Equal 'DecreaseSetpoint' $d.Action 'high temperature worsening retries immediately'

$last = New-SetActionRecord -Temperature 24.7 -Setpoint 26 -CycleIndex 1
$d = Decide -Temperature 25.0 -LastSetAction $last -CycleIndex 2
Assert-Equal 'DecreaseSetpoint' $d.Action 'opposite-side high temperature triggers new intervention'

$last = New-SetActionRecord -Temperature 25.0 -Setpoint 27 -CycleIndex 1
$d = Decide -Temperature 24.7 -LastSetAction $last -CycleIndex 2
Assert-Equal 'IncreaseSetpoint' $d.Action 'opposite-side low temperature triggers new intervention'

$runtimeText = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\scripts\avd-ac-regulator.ps1') -Raw
$cycleCall = $runtimeText.IndexOf('function Invoke-Cycle')
$decisionCall = $runtimeText.IndexOf('$decision = Get-CycleDecision', $cycleCall)
$openAcCall = $runtimeText.IndexOf('$shot = Open-Ac', $cycleCall)
Assert-True ($decisionCall -ge 0) 'runtime calls Get-CycleDecision'
Assert-True ($openAcCall -gt $decisionCall) 'runtime decides before opening AC'
Assert-False ($runtimeText -like '*Invoke-CriticalZoneRecoveryIfNeeded*') 'runtime removed recovery predicate helper'
Assert-True ($runtimeText -like '*function Read-Temp*') 'runtime exposes Read-Temp wrapper'
Assert-True ($runtimeText -like '*function Read-SetTemp*') 'runtime exposes Read-SetTemp wrapper'
Assert-True ($runtimeText -like '*function Set-Temp*') 'runtime exposes Set-Temp wrapper'

if ($script:Failures -gt 0) {
    throw ("{0} decision logic test(s) failed." -f $script:Failures)
}
