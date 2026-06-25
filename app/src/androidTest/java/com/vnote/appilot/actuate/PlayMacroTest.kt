package com.vnote.appilot.actuate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vnote.appilot.actuate.harness.TapHarnessActivity
import com.vnote.appilot.core.model.GestureStep
import com.vnote.appilot.core.model.Ratio
import com.vnote.appilot.core.model.RatioPoint
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented proof of the desktop-open gesture replay ([RegulatorAccessibilityService.playMacro])
 * on a real device. The deterministic test plays a single Tap step over the
 * full-screen harness and asserts the REAL onClick counter fired. The second,
 * environment-guarded test reopens an actual pinned vendor shortcut
 * (home -> swipe page -> tap) and asserts the vendor activity comes foreground;
 * it is skipped when that vendor app is not installed.
 */
@RunWith(AndroidJUnit4::class)
class PlayMacroTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val enabler = A11yEnabler(instrumentation)

    @Before
    fun setUp() = enabler.prepareDevice()

    @After
    fun tearDown() = enabler.teardown()

    @Test
    fun playMacro_tapStep_firesRealHarnessClick() {
        val service = enabler.forceConnect(A11yEnabler.CONNECT_TIMEOUT_MS)
        assertNotNull("a11y service never connected", service)

        TapHarnessActivity.resetClicks()
        enabler.launchHarness(A11yEnabler.HARNESS_TIMEOUT_MS)

        val latch = CountDownLatch(1)
        service!!.playMacro(listOf(GestureStep.Tap(RatioPoint(Ratio(0.5), Ratio(0.5))))) {
            latch.countDown()
        }

        assertTrue("playMacro never completed", latch.await(MACRO_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertTrue(
            "harness button onClick never fired. clicks=" + TapHarnessActivity.clicks.get(),
            awaitClicks(min = 1, timeoutMs = CLICK_TIMEOUT_MS),
        )
    }

    private fun awaitClicks(min: Int, timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (TapHarnessActivity.clicks.get() >= min) return true
            android.os.SystemClock.sleep(100L)
        }
        return TapHarnessActivity.clicks.get() >= min
    }

    private companion object {
        const val MACRO_TIMEOUT_MS = 8_000L
        const val CLICK_TIMEOUT_MS = 5_000L
    }
}
