package com.vnote.appilot.ui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vnote.appilot.core.model.AcAction
import kotlinx.coroutines.launch

private val ACTIONS: List<Pair<String, AcAction>> = listOf(
    "TEMP_DOWN" to AcAction.TEMP_DOWN,
    "TEMP_UP" to AcAction.TEMP_UP,
    "SWING" to AcAction.SWING,
    "MODE" to AcAction.Custom("MODE"),
)

/**
 * Step (c): overlay-mark AC tap targets and bind each to an [AcAction]. The
 * currently selected action is applied to the next dragged rectangle, which is
 * appended as a [com.vnote.appilot.core.model.TapTarget].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkTapTargetsStep(viewModel: CalibrationViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf<AcAction>(AcAction.TEMP_DOWN) }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Mark AC tap targets", style = MaterialTheme.typography.titleLarge)
        Text("Tap targets: ${viewModel.config.tapTargets.size}", Modifier.testTag("tapCount"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ACTIONS.forEach { (label, value) ->
                FilterChip(
                    selected = action == value,
                    onClick = { action = value },
                    label = { Text(label) },
                    modifier = Modifier.testTag("action_$label"),
                )
            }
        }
        Button(
            onClick = { scope.launch { viewModel.captureScreenshot() } },
            modifier = Modifier.testTag("captureScreenshot2").padding(top = 8.dp),
        ) { Text("Capture screen") }

        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) {
            val bitmap = viewModel.screenshot
            if (bitmap != null) {
                DragRectOverlay(
                    bitmap = bitmap,
                    rects = viewModel.config.tapTargets.map { it.rect },
                    onRectDrawn = { viewModel.addTapTarget(it, action) },
                    testTag = "tapOverlay",
                )
            } else {
                Text("Capture a screenshot, then drag over each AC button.")
            }
        }
    }
}
