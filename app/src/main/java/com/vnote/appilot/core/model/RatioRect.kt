package com.vnote.appilot.core.model

import kotlinx.serialization.Serializable

/**
 * A rectangle expressed in [Ratio]s of the screen, independent of resolution and
 * orientation. `left <= right` and `top <= bottom` are required so the rect is
 * always well-formed.
 */
@Serializable
data class RatioRect(
    val left: Ratio,
    val top: Ratio,
    val right: Ratio,
    val bottom: Ratio,
) {
    init {
        require(left.value <= right.value) {
            "left (${left.value}) must be <= right (${right.value})"
        }
        require(top.value <= bottom.value) {
            "top (${top.value}) must be <= bottom (${bottom.value})"
        }
    }

    /** Horizontal center of the rect, as a [Ratio]. */
    val centerX: Ratio get() = Ratio((left.value + right.value) / 2.0)

    /** Vertical center of the rect, as a [Ratio]. */
    val centerY: Ratio get() = Ratio((top.value + bottom.value) / 2.0)
}
