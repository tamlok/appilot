package com.vnote.appilot.ui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Step (d): set the comfort [com.vnote.appilot.core.model.TempBand] (lowC/highC),
 * the per-nudge step, and the polling interval (minutes). Each field validates
 * and pushes valid values straight into the view model's config snapshot.
 */
@Composable
fun SetThresholdsStep(viewModel: CalibrationViewModel, modifier: Modifier = Modifier) {
    var low by remember { mutableStateOf(viewModel.config.band.lowC.toString()) }
    var high by remember { mutableStateOf(viewModel.config.band.highC.toString()) }
    var step by remember { mutableStateOf(viewModel.config.step.toString()) }
    var interval by remember { mutableStateOf(viewModel.config.intervalMinutes.toString()) }

    fun pushBand() {
        val l = low.toDoubleOrNull()
        val h = high.toDoubleOrNull()
        if (l != null && h != null && l <= h) viewModel.setBand(l, h)
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set thresholds", style = MaterialTheme.typography.titleLarge)
        NumberField("Low °C", low, "lowField") { low = it; pushBand() }
        NumberField("High °C", high, "highField") { high = it; pushBand() }
        NumberField("Step °C per nudge", step, "stepField") {
            step = it
            it.toDoubleOrNull()?.let(viewModel::setStep)
        }
        NumberField("Interval (minutes)", interval, "intervalField") {
            interval = it
            it.toIntOrNull()?.let(viewModel::setInterval)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
