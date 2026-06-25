package com.vnote.appilot.core.model

import kotlinx.serialization.Serializable

/**
 * A user-configured way to open a screen for one role (`source` or `actuator`).
 *
 * Sealed so callers can [when] exhaustively without an `else` branch. No
 * Android-framework types leak in here: the captured intent is stored purely as
 * a String uri (see Wave 2 `LaunchTargetResolver`).
 */
@Serializable
sealed interface LaunchTarget {

    /** Launch via `getLaunchIntentForPackage(packageName)`. */
    @Serializable
    data class App(val packageName: String) : LaunchTarget

    /** Launch a raw `VIEW` deep link (no explicit component). */
    @Serializable
    data class DeepLink(val uri: String) : LaunchTarget

    /**
     * Replay a captured pinned-shortcut / explicit-component intent.
     *
     * @param intentUri the intent serialized via `Intent.toUri(...)`.
     * @param fallbackPackage package to launch if replay throws `SecurityException`.
     */
    @Serializable
    data class CapturedShortcut(
        val intentUri: String,
        val fallbackPackage: String,
    ) : LaunchTarget
}
