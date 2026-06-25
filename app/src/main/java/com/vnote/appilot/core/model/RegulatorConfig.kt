package com.vnote.appilot.core.model

import kotlinx.serialization.Serializable

/**
 * The inclusive temperature band to keep the room within, in Celsius.
 * `lowC <= highC` is required. Outside this band the decision engine nudges.
 */
@Serializable
data class TempBand(
    val lowC: Double,
    val highC: Double,
) {
    init {
        require(lowC <= highC) { "lowC ($lowC) must be <= highC ($highC)" }
    }
}

/**
 * The complete, immutable configuration for one source/actuator pair.
 *
 * @param band keep the reading inside this band.
 * @param step degrees Celsius nudged per cycle when outside the band.
 * @param intervalMinutes polling interval; defaults to 10.
 * @param source where the temperature is read.
 * @param actuator where the AC is controlled.
 * @param tapTargets calibrated buttons on the actuator screen.
 * @param readRegion where the temperature is drawn on the source screen.
 */
@Serializable
data class RegulatorConfig(
    val band: TempBand,
    val step: Double,
    val intervalMinutes: Int = 10,
    val source: LaunchTarget,
    val actuator: LaunchTarget,
    val tapTargets: List<TapTarget>,
    val readRegion: ReadRegion,
) {
    init {
        require(step > 0.0) { "step ($step) must be positive" }
        require(intervalMinutes > 0) { "intervalMinutes ($intervalMinutes) must be positive" }
    }
}
