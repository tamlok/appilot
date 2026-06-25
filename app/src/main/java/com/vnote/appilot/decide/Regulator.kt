package com.vnote.appilot.decide

import com.vnote.appilot.core.model.AcAction
import com.vnote.appilot.core.model.RegulatorConfig

/**
 * The decision the regulator makes for one cycle. Distinct from [AcAction] (a
 * device tap): this is the *intent* — nudge the setpoint colder, warmer, or hold.
 * Wave 6's service maps a non-NONE action onto the configured tap target(s).
 */
enum class RegulatorAction {
    /** Reading is too hot — lower the setpoint by one step. */
    DOWN,

    /** Reading is too cold — raise the setpoint by one step. */
    UP,

    /** Reading is satisfied (inside the band, or held by the deadband). */
    NONE,
}

/**
 * Map a decision onto the device-level [AcAction] Wave 6 will actuate.
 * NONE maps to `null` (no tap). DOWN/UP cool/warm the setpoint.
 */
fun RegulatorAction.toAcAction(): AcAction? = when (this) {
    RegulatorAction.DOWN -> AcAction.TEMP_DOWN
    RegulatorAction.UP -> AcAction.TEMP_UP
    RegulatorAction.NONE -> null
}

/**
 * Pure, side-effect-free threshold + hysteresis decision engine.
 *
 * Pure-JVM by design (no `android.*`): the whole thing is a function of its three
 * arguments, so it is exhaustively unit-testable on plain JUnit with no emulator.
 */
object Regulator {

    /**
     * Decide the action for one polling cycle. Emits **at most one** nudge per call
     * (the "one nudge per cycle" constraint), so a caller fires a single tap sequence.
     *
     * ## Band (deadband on the *inside*)
     * The band `[lowC, highC]` is inclusive — any reading within it returns [NONE].
     * This interior is itself a deadband: small wobble inside the band never nudges.
     *
     * ## Hysteresis (deadband on *reversal*)
     * Naive thresholding flip-flops when a single nudge overshoots the *opposite*
     * edge (e.g. a DOWN nudge undershoots below `lowC`, the next cycle issues UP,
     * which overshoots above `highC`, ad infinitum). To break that loop we require
     * a reversal to clear a margin past the threshold before it is allowed:
     *
     * ```
     *   margin d = step / 2          // half a nudge — one overshoot can't reverse us
     *   reverse to UP   only if  currentTemp <  lowC  - d   (when we last went DOWN)
     *   reverse to DOWN only if  currentTemp >  highC + d   (when we last went UP)
     * ```
     *
     * `d = step/2` is chosen so that an overshoot no larger than half a step is
     * absorbed as [NONE] instead of triggering the opposite nudge; only a genuine,
     * larger excursion past the far threshold is corrected. Continuing in the **same**
     * direction (still too hot after a DOWN) is never suppressed, so the loop still
     * converges toward the band.
     *
     * @param currentTemp the temperature just read this cycle, in Celsius.
     * @param config supplies `band.lowC`, `band.highC` and `step`.
     * @param lastAction what the previous cycle decided; drives the reversal guard.
     */
    fun decide(
        currentTemp: Double,
        config: RegulatorConfig,
        lastAction: RegulatorAction,
    ): RegulatorAction {
        val low = config.band.lowC
        val high = config.band.highC
        val d = config.step / 2.0 // reversal deadband margin: half a nudge

        return when {
            // Too hot -> candidate DOWN. If we just nudged UP, only reverse once the
            // reading is clearly (more than d) above highC; otherwise hold.
            currentTemp > high ->
                if (lastAction == RegulatorAction.UP && currentTemp <= high + d) {
                    RegulatorAction.NONE
                } else {
                    RegulatorAction.DOWN
                }

            // Too cold -> candidate UP. Symmetric reversal guard against a prior DOWN.
            currentTemp < low ->
                if (lastAction == RegulatorAction.DOWN && currentTemp >= low - d) {
                    RegulatorAction.NONE
                } else {
                    RegulatorAction.UP
                }

            // Inside the inclusive band — satisfied.
            else -> RegulatorAction.NONE
        }
    }
}
