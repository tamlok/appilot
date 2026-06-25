package com.vnote.appilot.actuate

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vnote.appilot.actuate.harness.TapHarnessActivity
import com.vnote.appilot.core.model.AcAction
import com.vnote.appilot.core.model.Ratio
import com.vnote.appilot.core.model.RatioRect
import com.vnote.appilot.core.model.TapTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke for [Actuator] on a real device: TEMP_DOWN with 2 steps must
 * land exactly 2 real clicks on the harness button — proving the action ->
 * tap-sequence mapping drives the live coordinate tapper end to end (not just the
 * pure-JVM fake in [ActuatorTest]).
 */
@RunWith(AndroidJUnit4::class)
class ActuatorSmokeTest {

    private val enabler = A11yEnabler(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() = enabler.prepareDevice()

    @After
    fun tearDown() = enabler.teardown()

    @Test
    fun tempDownTwoSteps_landsTwoRealClicks() {
        val service = enabler.forceConnect(A11yEnabler.CONNECT_TIMEOUT_MS)
        assertNotNull(
            "a11y service never connected. enabled=" + enabler.enabledServices(),
            service,
        )

        TapHarnessActivity.resetClicks()
        enabler.launchHarness(A11yEnabler.HARNESS_TIMEOUT_MS)

        val targets = listOf(
            TapTarget(
                RatioRect(Ratio(0.25), Ratio(0.4), Ratio(0.75), Ratio(0.6)),
                AcAction.TEMP_DOWN,
            ),
        )
        val actuator = Actuator(service!!, targets, interTapDelayMs = 200L)

        // perform() blocks (each tap waits for its gesture-completion callback,
        // delivered on the main looper); run it on this instrumentation thread,
        // which is NOT the main thread, so the callbacks are free to fire.
        val dispatched = actuator.perform(AcAction.TEMP_DOWN, steps = 2)
        assertEquals("expected 2 taps dispatched", 2, dispatched)

        assertEquals(
            "TEMP_DOWN x2 should land exactly 2 real clicks",
            2,
            awaitClicks(target = 2, timeoutMs = CLICK_TIMEOUT_MS),
        )
    }

    private fun awaitClicks(target: Int, timeoutMs: Long): Int {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (TapHarnessActivity.clicks.get() >= target) break
            SystemClock.sleep(100L)
        }
        return TapHarnessActivity.clicks.get()
    }

    private companion object {
        const val CLICK_TIMEOUT_MS = 5_000L
    }
}
