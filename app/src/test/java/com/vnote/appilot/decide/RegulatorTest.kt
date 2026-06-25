package com.vnote.appilot.decide

import com.vnote.appilot.core.model.AcAction
import com.vnote.appilot.core.model.LaunchTarget
import com.vnote.appilot.core.model.Ratio
import com.vnote.appilot.core.model.RatioRect
import com.vnote.appilot.core.model.ReadRegion
import com.vnote.appilot.core.model.RegulatorConfig
import com.vnote.appilot.core.model.TempBand
import com.vnote.appilot.decide.RegulatorAction.DOWN
import com.vnote.appilot.decide.RegulatorAction.NONE
import com.vnote.appilot.decide.RegulatorAction.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the decision engine. No android.* — runs on plain JUnit.
 *
 * Proves the three acceptance properties from Wave 5 Task 13:
 *  (a) a temperature sweep lands in the correct DOWN / UP / NONE region;
 *  (b) the band edges (exactly low/high, just inside, just outside) behave;
 *  (c) hysteresis/deadband prevents flip-flop when `lastAction` is fed back across
 *      cycles, and the contrast test shows the same plant DOES oscillate without it.
 */
class RegulatorTest {

    /** Build a config whose only meaningful fields here are `band` and `step`. */
    private fun cfg(low: Double, high: Double, step: Double): RegulatorConfig {
        val r = RatioRect(Ratio(0.1), Ratio(0.1), Ratio(0.2), Ratio(0.2))
        return RegulatorConfig(
            band = TempBand(lowC = low, highC = high),
            step = step,
            source = LaunchTarget.App("com.example.source"),
            actuator = LaunchTarget.App("com.example.actuator"),
            tapTargets = emptyList(),
            readRegion = ReadRegion(r),
        )
    }

    // (a) Temperature sweep ---------------------------------------------------

    @Test
    fun sweep_belowLow_up_insideBand_none_aboveHigh_down() {
        val c = cfg(low = 22.0, high = 26.0, step = 0.5)
        // lastAction = NONE everywhere so the reversal guard never fires:
        // a pure read of the DOWN / UP / NONE regions.
        var t = 18.0
        while (t <= 30.0001) {
            val action = Regulator.decide(t, c, NONE)
            val expected = when {
                t < 22.0 -> UP
                t > 26.0 -> DOWN
                else -> NONE
            }
            assertEquals("temp=$t", expected, action)
            t += 0.5
        }
    }

    // (b) Band edges ----------------------------------------------------------

    @Test
    fun edges_inclusiveBand_andJustOutside() {
        val c = cfg(low = 22.0, high = 26.0, step = 0.5)
        // Band is inclusive: exactly on a threshold is satisfied.
        assertEquals(NONE, Regulator.decide(22.0, c, NONE)) // exactly lowC
        assertEquals(NONE, Regulator.decide(26.0, c, NONE)) // exactly highC
        // Just inside.
        assertEquals(NONE, Regulator.decide(22.01, c, NONE))
        assertEquals(NONE, Regulator.decide(25.99, c, NONE))
        // Just outside (fresh decision, no opposite lastAction to suppress).
        assertEquals(UP, Regulator.decide(21.99, c, NONE))
        assertEquals(DOWN, Regulator.decide(26.01, c, NONE))
    }

    // (c) Hysteresis / deadband ----------------------------------------------

    @Test
    fun deadband_suppressesImmediateReversal_butNotLargeExcursion() {
        val c = cfg(low = 22.0, high = 26.0, step = 1.0) // deadband d = step/2 = 0.5
        // After a DOWN nudge the room overshoots just below low (< d): hold, don't UP.
        assertEquals(NONE, Regulator.decide(21.7, c, DOWN)) // 21.7 >= 22 - 0.5
        // A large overshoot beyond the deadband must still be corrected.
        assertEquals(UP, Regulator.decide(21.4, c, DOWN)) // 21.4 < 22 - 0.5
        // Symmetric: after an UP nudge, a small over-shoot above high is held.
        assertEquals(NONE, Regulator.decide(26.3, c, UP)) // 26.3 <= 26 + 0.5
        assertEquals(DOWN, Regulator.decide(26.6, c, UP)) // 26.6 > 26 + 0.5
        // Same-direction continuation is never suppressed (still too hot -> keep DOWN).
        assertEquals(DOWN, Regulator.decide(27.0, c, DOWN))
        // Still too cold -> keep UP.
        assertEquals(UP, Regulator.decide(20.0, c, UP))
    }

    @Test
    fun antiOscillation_feedingBackLastAction_neverFlipFlopsOnConsecutiveNudges() {
        // Narrow band + a swing wider than the band => naive control overshoots the
        // opposite edge every cycle. Hysteresis must break the flip-flop.
        val c = cfg(low = 24.0, high = 26.0, step = 3.0) // deadband d = 1.5
        val swing = 3.0
        var temp = 26.5
        var last = NONE
        val log = mutableListOf<RegulatorAction>()
        repeat(12) {
            val a = Regulator.decide(temp, c, last)
            log += a
            temp += when (a) {
                DOWN -> -swing
                UP -> swing
                NONE -> 0.0
            }
            last = a
        }
        // Invariant: no DOWN is immediately followed by UP (or vice versa); the
        // deadband always interposes a NONE cycle before reversing direction.
        for (i in 1 until log.size) {
            val prev = log[i - 1]
            val cur = log[i]
            val flip = (prev == DOWN && cur == UP) || (prev == UP && cur == DOWN)
            assertTrue("flip-flop $prev->$cur at cycle $i (log=$log)", !flip)
        }
    }

    @Test
    fun withoutHysteresis_samePlantOscillates_provingTheGuardIsLoadBearing() {
        val c = cfg(low = 24.0, high = 26.0, step = 3.0)
        val swing = 3.0
        var temp = 26.5
        val log = mutableListOf<RegulatorAction>()
        repeat(6) {
            // Never feed lastAction back -> the reversal guard can never engage.
            val a = Regulator.decide(temp, c, NONE)
            log += a
            temp += when (a) {
                DOWN -> -swing
                UP -> swing
                NONE -> 0.0
            }
        }
        val flips = log.zipWithNext().any { (p, n) ->
            (p == DOWN && n == UP) || (p == UP && n == DOWN)
        }
        assertTrue("expected an oscillation without feedback (log=$log)", flips)
    }

    // Downstream mapping ------------------------------------------------------

    @Test
    fun mapsToDeviceAcAction_forWave6Actuator() {
        assertEquals(AcAction.TEMP_DOWN, DOWN.toAcAction())
        assertEquals(AcAction.TEMP_UP, UP.toAcAction())
        assertNull(NONE.toAcAction())
    }
}
