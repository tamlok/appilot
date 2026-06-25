package com.vnote.appilot.launch

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import com.vnote.appilot.core.model.LaunchTarget

/**
 * Captures a launcher shortcut as a lossless [LaunchTarget.CapturedShortcut]
 * using the Activity Result API.
 *
 * Flow: `startActivityForResult(ACTION_CREATE_SHORTCUT)` -> the chosen app
 * returns an `EXTRA_SHORTCUT_INTENT` carrying the FULL replayable intent. Wave 0
 * proved this is the ONLY capture path that keeps the data-URI PATH intact —
 * `dumpsys` redacts it via `Uri.toSafeString()`. We therefore serialize the
 * captured intent verbatim through [LaunchTargetResolver] with no transform.
 *
 * No vendor knowledge lives here: the captured intent (component, action, data,
 * extras) is whatever the user picked.
 */
object CreateShortcutPicker {

    data class ShortcutCreator(val component: ComponentName, val label: String)

    /**
     * Enumerates the installed activities that handle `ACTION_CREATE_SHORTCUT`,
     * using the scoped `<queries>` visibility (NOT `QUERY_ALL_PACKAGES`).
     *
     * We resolve the list ourselves and later launch an EXPLICIT-component
     * intent so we never trigger the system disambiguation chooser
     * (`ResolverActivity`) — that chooser can crash on a malformed launcher
     * icon, taking the flow down with it. Bypassing it is both more robust and
     * fully deterministic.
     */
    fun shortcutCreatorActivities(context: Context): List<ShortcutCreator> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_CREATE_SHORTCUT)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return resolved
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                ShortcutCreator(
                    ComponentName(ai.packageName, ai.name),
                    ri.loadLabel(pm).toString().ifBlank { ai.packageName },
                )
            }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * An [ActivityResultContract] launching `ACTION_CREATE_SHORTCUT` targeted at
     * an EXPLICIT [ComponentName] (chosen from [shortcutCreatorActivities]) and
     * parsing the result into a storable [LaunchTarget.CapturedShortcut] (or
     * `null` when the user cancels or no shortcut intent comes back). Targeting
     * an explicit component avoids the fragile system chooser entirely.
     */
    class Contract : ActivityResultContract<ComponentName, LaunchTarget.CapturedShortcut?>() {
        override fun createIntent(context: Context, input: ComponentName): Intent =
            Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(input)

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): LaunchTarget.CapturedShortcut? {
            if (resultCode != Activity.RESULT_OK) return null
            return toCapturedShortcut(intent)
        }
    }

    /** Extracts the inner `EXTRA_SHORTCUT_INTENT`, or `null` when absent. */
    fun extractShortcutIntent(result: Intent?): Intent? {
        val source = result ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            source.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
        }
    }

    /**
     * Maps a `CREATE_SHORTCUT` [result] to a [LaunchTarget.CapturedShortcut],
     * preserving the captured intent losslessly. The fallback package defaults
     * to the captured intent's own component package when not supplied.
     */
    fun toCapturedShortcut(
        result: Intent?,
        fallbackPackage: String? = null,
    ): LaunchTarget.CapturedShortcut? {
        val shortcut = extractShortcutIntent(result) ?: return null
        val pkg = fallbackPackage
            ?: shortcut.component?.packageName
            ?: shortcut.`package`
            ?: ""
        return LaunchTargetResolver.toCapturedShortcut(shortcut, pkg)
    }
}
