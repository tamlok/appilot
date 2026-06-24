package com.vnote.appilot.launch

import android.app.Activity
import android.content.Context
import android.content.Intent
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

    /**
     * An [ActivityResultContract] launching `ACTION_CREATE_SHORTCUT` and parsing
     * the result into a storable [LaunchTarget.CapturedShortcut] (or `null` when
     * the user cancels or no shortcut intent comes back).
     */
    class Contract : ActivityResultContract<Unit, LaunchTarget.CapturedShortcut?>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(Intent.ACTION_CREATE_SHORTCUT)

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
