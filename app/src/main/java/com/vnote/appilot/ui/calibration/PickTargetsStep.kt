package com.vnote.appilot.ui.calibration

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import com.vnote.appilot.core.model.LaunchTarget
import com.vnote.appilot.launch.AppPicker
import com.vnote.appilot.launch.CreateShortcutPicker

private enum class Role { SOURCE, ACTUATOR }

/** Human label for a stored [LaunchTarget] (no vendor strings — all runtime). */
fun launchTargetLabel(target: LaunchTarget): String = when (target) {
    is LaunchTarget.App -> if (target.packageName.isBlank()) "(none)" else target.packageName
    is LaunchTarget.DeepLink -> target.uri
    is LaunchTarget.CapturedShortcut -> "shortcut: ${target.fallbackPackage}"
}

/**
 * Step (a): choose the source + actuator [LaunchTarget]s. One role is active at
 * a time; tapping an enumerated launcher app (via [AppPicker]) assigns it, and
 * "Capture shortcut" runs [CreateShortcutPicker] for a pinned-shortcut intent.
 */
@Composable
fun PickTargetsStep(viewModel: CalibrationViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var role by remember { mutableStateOf(Role.SOURCE) }
    val apps = remember { AppPicker.from(context).installedLauncherApps() }
    val creators = remember { CreateShortcutPicker.shortcutCreatorActivities(context) }
    var showCreators by remember { mutableStateOf(false) }

    val sourceShortcut = rememberLauncherForActivityResult(CreateShortcutPicker.Contract()) {
        it?.let(viewModel::setSource)
    }
    val actuatorShortcut = rememberLauncherForActivityResult(CreateShortcutPicker.Contract()) {
        it?.let(viewModel::setActuator)
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Pick launch targets", style = MaterialTheme.typography.titleLarge)
        Text("Source: ${launchTargetLabel(viewModel.config.source)}", Modifier.testTag("sourceLabel"))
        Text("Actuator: ${launchTargetLabel(viewModel.config.actuator)}", Modifier.testTag("actuatorLabel"))

        Row(Modifier.padding(vertical = 8.dp)) {
            RoleChoice("Source", role == Role.SOURCE, "role_source") { role = Role.SOURCE }
            RoleChoice("Actuator", role == Role.ACTUATOR, "role_actuator") { role = Role.ACTUATOR }
        }
        Button(
            onClick = { showCreators = !showCreators },
            modifier = Modifier.testTag("captureShortcut"),
        ) { Text(if (showCreators) "Hide shortcut apps" else "Capture shortcut") }

        if (showCreators) {
            if (creators.isEmpty()) {
                Text(
                    "No shortcut-capable apps found",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                creators.forEach { creator ->
                    Text(
                        creator.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showCreators = false
                                val launcher = if (role == Role.SOURCE) sourceShortcut else actuatorShortcut
                                try {
                                    launcher.launch(creator.component)
                                } catch (e: ActivityNotFoundException) {
                                    Log.w("PickTargets", "Shortcut creator not launchable: ${creator.component}", e)
                                } catch (e: SecurityException) {
                                    Log.w("PickTargets", "Shortcut creator not exported: ${creator.component}", e)
                                }
                            }
                            .testTag("creator_${creator.component.packageName}")
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Apps", style = MaterialTheme.typography.titleMedium)
        apps.forEach { app ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (role == Role.SOURCE) viewModel.setSource(app.target)
                        else viewModel.setActuator(app.target)
                    }
                    .testTag("app_${app.target.packageName}")
                    .padding(vertical = 8.dp),
            ) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Text(app.target.packageName, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RoleChoice(label: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .selectable(selected = selected, onClick = onSelect)
            .testTag(tag)
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}
