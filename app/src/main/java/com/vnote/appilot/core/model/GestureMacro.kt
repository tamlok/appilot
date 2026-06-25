package com.vnote.appilot.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A resolution/orientation-independent point in screen [Ratio]s. */
@Serializable
data class RatioPoint(val x: Ratio, val y: Ratio)

/**
 * One step in a desktop-open gesture macro, replayed via the accessibility
 * service to reopen a pinned launcher shortcut a non-launcher app cannot read or
 * start directly (`LauncherApps` needs the default-launcher role).
 */
@Serializable
sealed interface GestureStep {

    /** Go to the launcher home screen (`GLOBAL_ACTION_HOME`). */
    @Serializable
    @SerialName("home")
    data object Home : GestureStep

    /** Swipe from one point to another, e.g. to reach another home page. */
    @Serializable
    @SerialName("swipe")
    data class Swipe(
        val from: RatioPoint,
        val to: RatioPoint,
        val durationMs: Long = 300,
    ) : GestureStep

    /** Tap a point, e.g. the device shortcut icon. */
    @Serializable
    @SerialName("tap")
    data class Tap(val spot: RatioPoint, val durationMs: Long = 60) : GestureStep
}
