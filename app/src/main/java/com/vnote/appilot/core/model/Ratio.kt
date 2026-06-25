package com.vnote.appilot.core.model

import kotlinx.serialization.Serializable

/**
 * A single resolution/orientation-independent fraction in the inclusive range
 * `0.0..1.0`. Multiply by a pixel dimension to resolve a concrete coordinate.
 */
@JvmInline
@Serializable
value class Ratio(val value: Double) {
    init {
        require(value in 0.0..1.0) { "Ratio must be in 0.0..1.0 but was $value" }
    }

    companion object {
        val ZERO = Ratio(0.0)
        val ONE = Ratio(1.0)
    }
}
