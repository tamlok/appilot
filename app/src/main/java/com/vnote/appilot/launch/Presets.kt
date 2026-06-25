package com.vnote.appilot.launch

import android.content.Intent
import com.vnote.appilot.core.model.GestureStep
import com.vnote.appilot.core.model.LaunchTarget
import com.vnote.appilot.core.model.Ratio
import com.vnote.appilot.core.model.RatioPoint
import com.vnote.appilot.core.model.RegulatorConfig
import com.vnote.appilot.core.store.ConfigStore

/**
 * Hardcoded launch targets for this build's fixed setup: monitor the Tuya
 * temperature shortcut (source) and control the Haier AC shortcut (actuator).
 *
 * Vendor specifics live ONLY here, by deliberate choice — the generic capture UI
 * was dropped in favor of a fixed pair. Tuya is reached by its captured
 * explicit-component intent; Haier is reached by replaying the home -> swipe ->
 * tap gesture (its pinned-shortcut intent is unrecoverable to a non-launcher
 * app, but the gesture reopens its AC page reliably).
 */
object Presets {

    fun tuyaSource(): LaunchTarget.CapturedShortcut {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(TUYA_PACKAGE, "com.thingclips.smart.hometab.activity.shortcut")
            putExtra("url", "thingSmart://pinned_shortcut")
            putExtra("shortcut_dev_id", "6cd07555416b69c0e3zu8u")
            putExtra("type", 1)
            putExtra("shortcut_home_id", 205535327)
        }
        return LaunchTargetResolver.toCapturedShortcut(intent, TUYA_PACKAGE)
    }

    fun haierActuator(): LaunchTarget.LauncherGesture = LaunchTarget.LauncherGesture(
        steps = listOf(
            GestureStep.Home,
            GestureStep.Swipe(
                RatioPoint(Ratio(0.85), Ratio(0.5)),
                RatioPoint(Ratio(0.15), Ratio(0.5)),
            ),
            GestureStep.Tap(RatioPoint(Ratio(0.385), Ratio(0.235))),
        ),
        expectedPackage = HAIER_PACKAGE,
        label = "Haier AC",
    )

    fun defaultConfig(): RegulatorConfig =
        ConfigStore.DEFAULT.copy(source = tuyaSource(), actuator = haierActuator())

    const val TUYA_PACKAGE = "com.tuya.smartiot"
    const val HAIER_PACKAGE = "com.haier.uhome.uplus"
}
