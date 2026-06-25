package com.vnote.appilot.ui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val STEP_TITLES = listOf("Targets", "Read region", "Tap targets", "Thresholds")

/**
 * Four-step calibration stepper wiring a full source/actuator pair from scratch:
 * (a) pick targets, (b) mark the read region + glyphs, (c) mark tap targets,
 * (d) set thresholds — then Save persists the exact config snapshot.
 *
 * Reopening the flow [reload]s any previously saved config so it is restored.
 */
@Composable
fun CalibrationFlow(viewModel: CalibrationViewModel, modifier: Modifier = Modifier) {
    var step by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.reload() }

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Step ${step + 1}/4 — ${STEP_TITLES[step]}",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (viewModel.status.isNotEmpty()) {
                    Text(viewModel.status, Modifier.testTag("statusLabel"))
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (step) {
                    0 -> PickTargetsStep(viewModel)
                    1 -> MarkRegionStep(viewModel)
                    2 -> MarkTapTargetsStep(viewModel)
                    else -> SetThresholdsStep(viewModel)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = { step-- },
                        modifier = Modifier.weight(1f).testTag("backButton"),
                    ) { Text("Back") }
                }
                if (step < STEP_TITLES.lastIndex) {
                    Button(
                        onClick = { step++ },
                        modifier = Modifier.weight(1f).testTag("nextButton"),
                    ) { Text("Next") }
                } else {
                    Button(
                        onClick = { scope.launch { viewModel.save() } },
                        modifier = Modifier.weight(1f).testTag("saveButton"),
                    ) { Text("Save") }
                }
            }
        }
    }
}
