package com.vnote.appilot.core.model

import kotlinx.serialization.Serializable

/**
 * A calibrated tap: a screen [RatioRect] bound to the [AcAction] it triggers.
 * The actuate layer resolves [rect]'s center to a pixel point for `dispatchGesture`.
 */
@Serializable
data class TapTarget(
    val rect: RatioRect,
    val action: AcAction,
)
