package com.vnote.appilot.actuate

import android.os.SystemClock
import com.vnote.appilot.core.model.GestureStep
import com.vnote.appilot.core.model.LaunchTarget
import com.vnote.appilot.core.model.Ratio
import com.vnote.appilot.core.model.RatioPoint

/**
 * Builds a desktop-open [LaunchTarget.LauncherGesture] from the user's own
 * demonstration, fed accessibility events by [RegulatorAccessibilityService]
 * while recording:
 *
 *  - each launcher page scroll -> a standard next/prev page [GestureStep.Swipe]
 *    (debounced so the multiple scroll events of one fling collapse to one step);
 *  - the icon tap -> a [GestureStep.Tap] at the clicked node's center, in screen
 *    ratios (resolution/orientation independent);
 *  - the window-state change to a NON-launcher, non-own package right after the
 *    tap -> the macro's `expectedPackage`, which finalizes the recording.
 *
 * The macro always begins with [GestureStep.Home] so replay starts from a known
 * launcher state.
 */
class MacroRecorder(
    private val now: () -> Long = { SystemClock.uptimeMillis() },
    private val onComplete: (LaunchTarget.LauncherGesture) -> Unit,
) {

    private val steps = mutableListOf<GestureStep>(GestureStep.Home)
    private var launcherPackage: String? = null
    private var tapped = false
    private var done = false
    private var lastSwipeAt: Long? = null

    fun onScrolled(toRightPage: Boolean) {
        if (tapped || done) return
        val current = now()
        val previous = lastSwipeAt
        if (previous != null && current - previous < DEBOUNCE_MS) return
        lastSwipeAt = current
        steps.add(if (toRightPage) SWIPE_TO_RIGHT else SWIPE_TO_LEFT)
    }

    fun onClicked(packageName: String, centerXRatio: Double, centerYRatio: Double) {
        if (tapped || done) return
        tapped = true
        launcherPackage = packageName
        steps.add(
            GestureStep.Tap(
                RatioPoint(
                    Ratio(centerXRatio.coerceIn(0.0, 1.0)),
                    Ratio(centerYRatio.coerceIn(0.0, 1.0)),
                ),
            ),
        )
    }

    fun onWindowState(packageName: String, ownPackage: String) {
        if (!tapped || done) return
        if (packageName.isBlank() || packageName == ownPackage || packageName == launcherPackage) return
        done = true
        onComplete(LaunchTarget.LauncherGesture(steps.toList(), packageName, ""))
    }

    private companion object {
        const val DEBOUNCE_MS = 350L
        val SWIPE_TO_RIGHT = GestureStep.Swipe(
            RatioPoint(Ratio(0.85), Ratio(0.5)),
            RatioPoint(Ratio(0.15), Ratio(0.5)),
        )
        val SWIPE_TO_LEFT = GestureStep.Swipe(
            RatioPoint(Ratio(0.15), Ratio(0.5)),
            RatioPoint(Ratio(0.85), Ratio(0.5)),
        )
    }
}
