package com.vnote.appilot.launch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.vnote.appilot.core.model.LaunchTarget

/**
 * One launchable app the user may pick as a source/actuator, paired with its
 * human-readable [label] for display.
 */
data class InstalledApp(
    val target: LaunchTarget.App,
    val label: String,
)

/**
 * Enumerates installed apps that expose a MAIN/LAUNCHER entry point, so the
 * calibration UI can offer them as a [LaunchTarget.App].
 *
 * Visibility is granted by the scoped `<queries>` element in the manifest (a
 * MAIN/LAUNCHER `<intent>`), NOT by `QUERY_ALL_PACKAGES`. No package name is
 * hardcoded here: every entry is discovered at runtime.
 */
class AppPicker(private val packageManager: PackageManager) {

    /**
     * Returns the distinct launchable apps, de-duplicated by package and sorted
     * by label. Each maps directly to a storable [LaunchTarget.App].
     */
    fun installedLauncherApps(): List<InstalledApp> {
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(probe, 0)
            .map { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                InstalledApp(
                    target = LaunchTarget.App(packageName),
                    label = resolveInfo.loadLabel(packageManager).toString(),
                )
            }
            .distinctBy { it.target.packageName }
            .sortedBy { it.label.lowercase() }
    }

    companion object {
        /** Builds an [AppPicker] from a [context]'s package manager. */
        fun from(context: Context): AppPicker = AppPicker(context.packageManager)
    }
}
