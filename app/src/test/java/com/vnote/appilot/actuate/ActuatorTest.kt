package com.vnote.appilot.actuate

import com.vnote.appilot.core.model.AcAction
import com.vnote.appilot.core.model.Ratio
import com.vnote.appilot.core.model.RatioRect
import com.vnote.appilot.core.model.TapTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM proof of the action -> tap-sequence mapping. No `android.*`: the
 * [Tapper] seam is faked, so the [Actuator] logic is exercised deterministically
 * off-device.
 */
class ActuatorTest {

    private val downRect = RatioRect(Ratio(0.1), Ratio(0.8), Ratio(0.2), Ratio(0.9))
    private val upRect = RatioRect(Ratio(0.8), Ratio(0.8), Ratio(0.9), Ratio(0.9))
    private val targets = listOf(
        TapTarget(downRect, AcAction.TEMP_DOWN),
        TapTarget(upRect, AcAction.TEMP_UP),
    )

    private class FakeTapper : Tapper {
        val taps = mutableListOf<RatioRect>()

        override fun tap(target: RatioRect, onDone: (Boolean) -> Unit) {
            taps.add(target)
            onDone(true)
        }
    }

    @Test
    fun tempDownWithNSteps_issuesNTapsOnBoundTarget_inOrder() {
        val fake = FakeTapper()
        val delays = mutableListOf<Long>()
        val actuator = Actuator(fake, targets, interTapDelayMs = 42L, sleep = { delays.add(it) })

        val count = actuator.perform(AcAction.TEMP_DOWN, steps = 3)

        assertEquals(3, count)
        assertEquals(listOf(downRect, downRect, downRect), fake.taps)
        // Inter-tap delay is applied BETWEEN taps only -> N - 1 invocations.
        assertEquals(listOf(42L, 42L), delays)
    }

    @Test
    fun routesEachActionToItsConfiguredTarget() {
        val fake = FakeTapper()
        val actuator = Actuator(fake, targets, sleep = {})

        actuator.perform(AcAction.TEMP_UP, steps = 1)

        assertEquals(listOf(upRect), fake.taps)
    }

    @Test
    fun unmappedAction_isNoOp() {
        val fake = FakeTapper()
        val actuator = Actuator(fake, targets, sleep = {})

        val count = actuator.perform(AcAction.SWING, steps = 5)

        assertEquals(0, count)
        assertTrue(fake.taps.isEmpty())
    }

    @Test
    fun nonPositiveSteps_isNoOp() {
        val fake = FakeTapper()
        val actuator = Actuator(fake, targets, sleep = {})

        assertEquals(0, actuator.perform(AcAction.TEMP_DOWN, steps = 0))
        assertEquals(0, actuator.perform(AcAction.TEMP_DOWN, steps = -2))
        assertTrue(fake.taps.isEmpty())
    }
}
