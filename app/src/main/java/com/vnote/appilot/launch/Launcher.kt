package com.vnote.appilot.launch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vnote.appilot.actuate.RegulatorAccessibilityService
import com.vnote.appilot.core.model.LaunchTarget

/** Which dispatch path [Launcher.launch] actually took, for QA + logging. */
enum class LaunchOutcome {
    /** CapturedShortcut/explicit-component replay succeeded (Wave 0 tier 1). */
    PRIMARY,

    /** Explicit replay failed; `getLaunchIntentForPackage` succeeded (tier 2). */
    FALLBACK_PACKAGE,

    /** `App` target launched via `getLaunchIntentForPackage`. */
    PACKAGE,

    /** `DeepLink` target launched via a raw `VIEW` intent (tier 3). */
    DEEP_LINK,

    /** `LauncherGesture` macro kicked off via the accessibility service. */
    GESTURE,

    /** Nothing launchable: no fallback intent / package not installed. */
    FAILED,
}

/**
 * Replays a [LaunchTarget], honoring the Wave 0 launch-strategy ranking:
 *
 * 1. **CapturedShortcut / explicit-component** (primary) — the only path proven
 *    to land the vendor's exact deep page.
 * 2. **getLaunchIntentForPackage** (fallback) — used when the explicit replay
 *    throws `SecurityException` (exported gate closed) or `ActivityNotFound`.
 * 3. **raw VIEW deep-link** — the tertiary, vendor-dependent path used by
 *    [LaunchTarget.DeepLink].
 *
 * Two seams are injected so the ordering is unit-testable with no device:
 * - [starter] dispatches an [Intent] (`context::startActivity` in production).
 * - [launchIntentForPackage] resolves a package's launch intent.
 *
 * This class holds NO vendor-specific knowledge — every package, component and
 * URI arrives inside the [LaunchTarget].
 */
class Launcher(
    private val starter: (Intent) -> Unit,
    private val launchIntentForPackage: (String) -> Intent?,
    private val playGesture: (LaunchTarget.LauncherGesture) -> Boolean = { false },
) {

    /** Dispatches [target] per type, returning the path taken. */
    fun launch(target: LaunchTarget): LaunchOutcome = when (target) {
        is LaunchTarget.App -> launchPackage(target.packageName, LaunchOutcome.PACKAGE)
        is LaunchTarget.DeepLink -> launchDeepLink(target.uri)
        is LaunchTarget.CapturedShortcut -> launchCaptured(target)
        is LaunchTarget.LauncherGesture ->
            if (playGesture(target)) LaunchOutcome.GESTURE else LaunchOutcome.FAILED
    }

    private fun launchCaptured(target: LaunchTarget.CapturedShortcut): LaunchOutcome {
        val primary = LaunchTargetResolver.toIntent(target)
        return try {
            starter(primary)
            LaunchOutcome.PRIMARY
        } catch (_: SecurityException) {
            launchPackage(target.fallbackPackage, LaunchOutcome.FALLBACK_PACKAGE)
        } catch (_: ActivityNotFoundException) {
            launchPackage(target.fallbackPackage, LaunchOutcome.FALLBACK_PACKAGE)
        }
    }

    private fun launchPackage(packageName: String, success: LaunchOutcome): LaunchOutcome {
        val intent = launchIntentForPackage(packageName) ?: return LaunchOutcome.FAILED
        starter(intent)
        return success
    }

    private fun launchDeepLink(uri: String): LaunchOutcome {
        starter(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        return LaunchOutcome.DEEP_LINK
    }

    companion object {
        /**
         * Builds a [Launcher] bound to [context]. The starter adds
         * `FLAG_ACTIVITY_NEW_TASK` because the regulator launches from a
         * service/non-activity context.
         */
        fun from(context: Context): Launcher = Launcher(
            starter = { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            },
            launchIntentForPackage = { pkg ->
                context.packageManager.getLaunchIntentForPackage(pkg)
            },
            playGesture = { target ->
                val service = RegulatorAccessibilityService.instance
                if (service == null) {
                    false
                } else {
                    service.playMacro(target.steps) {}
                    true
                }
            },
        )
    }
}
