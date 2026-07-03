function New-SetActionRecord {
    param(
        [Parameter(Mandatory = $true)][double]$Temperature,
        [Parameter(Mandatory = $true)][int]$Setpoint,
        [Parameter(Mandatory = $true)][int]$CycleIndex
    )

    [pscustomobject]@{
        Temperature = $Temperature
        Setpoint = $Setpoint
        CycleIndex = $CycleIndex
    }
}

function Test-InSafeband {
    param(
        [Parameter(Mandatory = $true)][double]$Temperature,
        [Parameter(Mandatory = $true)][double]$SafebandLow,
        [Parameter(Mandatory = $true)][double]$SafebandHigh
    )

    return ($Temperature -ge $SafebandLow -and $Temperature -le $SafebandHigh)
}

function Get-TemperatureSide {
    param(
        [Parameter(Mandatory = $true)][double]$Temperature,
        [Parameter(Mandatory = $true)][double]$SafebandLow,
        [Parameter(Mandatory = $true)][double]$SafebandHigh
    )

    if ($Temperature -lt $SafebandLow) { return 'low' }
    if ($Temperature -gt $SafebandHigh) { return 'high' }
    return 'safe'
}

function Test-CriticalSetpoint {
    param(
        [Parameter(Mandatory = $true)][int]$Setpoint,
        [Parameter(Mandatory = $true)][int]$SetpointFloor,
        [Parameter(Mandatory = $true)][int]$SetpointCeiling
    )

    return ($Setpoint -le $SetpointFloor -or $Setpoint -ge $SetpointCeiling)
}

function Get-NearestNonCriticalSetpoint {
    param(
        [Parameter(Mandatory = $true)][int]$Setpoint,
        [Parameter(Mandatory = $true)][int]$SetpointFloor,
        [Parameter(Mandatory = $true)][int]$SetpointCeiling
    )

    if ($Setpoint -le $SetpointFloor) { return ($SetpointFloor + 1) }
    if ($Setpoint -ge $SetpointCeiling) { return ($SetpointCeiling - 1) }
    return $Setpoint
}

function Test-SameTemperature {
    param(
        [Parameter(Mandatory = $true)][double]$A,
        [Parameter(Mandatory = $true)][double]$B
    )

    return ([Math]::Round($A, 1) -eq [Math]::Round($B, 1))
}

function Test-TemperatureGettingWorse {
    param(
        [Parameter(Mandatory = $true)][double]$CurrentTemperature,
        $LastSetAction,
        [Parameter(Mandatory = $true)][double]$SafebandLow,
        [Parameter(Mandatory = $true)][double]$SafebandHigh
    )

    $currentSide = Get-TemperatureSide -Temperature $CurrentTemperature -SafebandLow $SafebandLow -SafebandHigh $SafebandHigh
    if ($currentSide -eq 'safe') { return $false }
    if ($null -eq $LastSetAction) { return $true }

    $lastSide = Get-TemperatureSide -Temperature ([double]$LastSetAction.Temperature) -SafebandLow $SafebandLow -SafebandHigh $SafebandHigh
    if ($currentSide -ne $lastSide) { return $true }

    if ($currentSide -eq 'low') { return ($CurrentTemperature -lt [double]$LastSetAction.Temperature) }
    if ($currentSide -eq 'high') { return ($CurrentTemperature -gt [double]$LastSetAction.Temperature) }
    return $false
}

function Test-TemperatureImproving {
    param(
        [Parameter(Mandatory = $true)][double]$CurrentTemperature,
        $LastSetAction,
        [Parameter(Mandatory = $true)][double]$SafebandLow,
        [Parameter(Mandatory = $true)][double]$SafebandHigh
    )

    $currentSide = Get-TemperatureSide -Temperature $CurrentTemperature -SafebandLow $SafebandLow -SafebandHigh $SafebandHigh
    if ($currentSide -eq 'safe') { return $false }
    if ($null -eq $LastSetAction) { return $false }

    $lastSide = Get-TemperatureSide -Temperature ([double]$LastSetAction.Temperature) -SafebandLow $SafebandLow -SafebandHigh $SafebandHigh
    if ($currentSide -ne $lastSide) { return $false }

    if ($currentSide -eq 'low') { return ($CurrentTemperature -gt [double]$LastSetAction.Temperature) }
    if ($currentSide -eq 'high') { return ($CurrentTemperature -lt [double]$LastSetAction.Temperature) }
    return $false
}

function Test-SameTemperatureEscalationDue {
    param(
        [Parameter(Mandatory = $true)][double]$CurrentTemperature,
        $LastSetAction,
        [Parameter(Mandatory = $true)][int]$CycleIndex,
        [Parameter(Mandatory = $true)][int]$UnchangedThresholdCycles
    )

    if ($null -eq $LastSetAction) { return $false }
    if (-not (Test-SameTemperature -A $CurrentTemperature -B ([double]$LastSetAction.Temperature))) { return $false }
    return (($CycleIndex - [int]$LastSetAction.CycleIndex) -gt $UnchangedThresholdCycles)
}

function New-CycleDecision {
    param(
        [Parameter(Mandatory = $true)][string]$Action,
        [Parameter(Mandatory = $true)][string]$Reason,
        [Parameter(Mandatory = $true)][string]$TemperatureSide,
        [bool]$ShouldOpenAc = $false,
        [bool]$ShouldUpdateObservation = $false
    )

    [pscustomobject]@{
        Action = $Action
        Reason = $Reason
        TemperatureSide = $TemperatureSide
        ShouldOpenAc = $ShouldOpenAc
        ShouldUpdateObservation = $ShouldUpdateObservation
    }
}

function Get-CycleDecision {
    param(
        [Parameter(Mandatory = $true)][double]$Temperature,
        $LastSetAction,
        [Parameter(Mandatory = $true)][int]$CycleIndex,
        [Parameter(Mandatory = $true)][double]$SafebandLow,
        [Parameter(Mandatory = $true)][double]$SafebandHigh,
        [Parameter(Mandatory = $true)][int]$SetpointFloor,
        [Parameter(Mandatory = $true)][int]$SetpointCeiling,
        [Parameter(Mandatory = $true)][int]$UnchangedThresholdCycles
    )

    $side = Get-TemperatureSide -Temperature $Temperature -SafebandLow $SafebandLow -SafebandHigh $SafebandHigh
    if ($side -eq 'safe') {
        if ($null -ne $LastSetAction -and (Test-CriticalSetpoint -Setpoint ([int]$LastSetAction.Setpoint) -SetpointFloor $SetpointFloor -SetpointCeiling $SetpointCeiling)) {
            return (New-CycleDecision -Action 'RecoverCritical' -Reason 'temperature in safeband and last setpoint is critical' -TemperatureSide $side -ShouldOpenAc $true)
        }
        return (New-CycleDecision -Action 'NoAction' -Reason 'temperature in safeband' -TemperatureSide $side)
    }

    $gettingWorse = Test-TemperatureGettingWorse -CurrentTemperature $Temperature -LastSetAction $LastSetAction -SafebandLow $SafebandLow -SafebandHigh $SafebandHigh
    $sameTempDue = Test-SameTemperatureEscalationDue -CurrentTemperature $Temperature -LastSetAction $LastSetAction -CycleIndex $CycleIndex -UnchangedThresholdCycles $UnchangedThresholdCycles
    if (-not $gettingWorse -and -not $sameTempDue) {
        if (Test-TemperatureImproving -CurrentTemperature $Temperature -LastSetAction $LastSetAction -SafebandLow $SafebandLow -SafebandHigh $SafebandHigh) {
            return (New-CycleDecision -Action 'UpdateObservation' -Reason 'temperature moving toward safeband' -TemperatureSide $side -ShouldUpdateObservation $true)
        }
        return (New-CycleDecision -Action 'NoAction' -Reason 'no intervention due' -TemperatureSide $side)
    }

    if ($side -eq 'low') {
        return (New-CycleDecision -Action 'IncreaseSetpoint' -Reason 'temperature below safeband' -TemperatureSide $side -ShouldOpenAc $true)
    }

    return (New-CycleDecision -Action 'DecreaseSetpoint' -Reason 'temperature above safeband' -TemperatureSide $side -ShouldOpenAc $true)
}
