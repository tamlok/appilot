package com.vnote.appilot.ui.calibration

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vnote.appilot.core.model.LaunchTarget

/** Human label for a stored [LaunchTarget]. */
fun launchTargetLabel(target: LaunchTarget): String = when (target) {
    is LaunchTarget.App -> if (target.packageName.isBlank()) "(none)" else target.packageName
    is LaunchTarget.DeepLink -> target.uri
    is LaunchTarget.CapturedShortcut -> "shortcut: ${target.fallbackPackage}"
    is LaunchTarget.LauncherGesture -> "desktop: ${target.label.ifBlank { target.expectedPackage }}"
}

/**
 * Step (a): the source + actuator are a fixed preset (Tuya temperature shortcut
 * + Haier AC desktop gesture), shown read-only. The remaining steps still mark
 * the read region, AC tap targets, and the comfort band.
 */
@Composable
fun PickTargetsStep(viewModel: CalibrationViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Launch targets (preset)", style = MaterialTheme.typography.titleLarge)
        Text(
            "Source: ${launchTargetLabel(viewModel.config.source)}",
            Modifier.padding(top = 8.dp).testTag("sourceLabel"),
        )
        Text(
            "Actuator: ${launchTargetLabel(viewModel.config.actuator)}",
            Modifier.padding(top = 4.dp).testTag("actuatorLabel"),
        )
        Text(
            "Monitors the Tuya temperature shortcut and controls the Haier AC by " +
                "replaying its home-screen open. Continue to mark the read region, " +
                "AC buttons, and comfort band.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
