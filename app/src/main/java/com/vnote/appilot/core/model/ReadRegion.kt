package com.vnote.appilot.core.model

import kotlinx.serialization.Serializable

/**
 * Marks where the temperature number is drawn on the source screen. The read
 * layer crops [rect] from a screenshot before template-matching the digits.
 */
@Serializable
data class ReadRegion(
    val rect: RatioRect,
)
