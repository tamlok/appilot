package com.vnote.appilot.actuate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vnote.appilot.actuate.harness.TapHarnessActivity
import com.vnote.appilot.core.model.Ratio
import com.vnote.appilot.core.model.RatioRect
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Instrumented proof of the actuate-layer coordinate tapper on a real device
 * (emulator-5554, Android 15). [A11yEnabler] handles enabling + binding the
 * service into the test process; this test launches the full-screen
 * [TapHarnessActivity] and calls [Tapper.tap] over a rect covering the button.
 *
 * misleading_success guard: success is asserted on the harness button's REAL
 * onClick counter ([TapHarnessActivity.clicks]) — NOT merely on the gesture's
 * own completion callback. The completion callback is asserted separately to
 * prove the cancel/resume seam (a cancelled gesture would report false).
 */
@RunWith(AndroidJUnit4::class)
class RegulatorAccessibilityServiceTest {

    private val enabler = A11yEnabler(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() = enabler.prepareDevice()

    @After
    fun tearDown() = enabler.teardown()

    @Test
    fun tap_firesRealClickHandlerOnHarnessButton() {
        val service = enabler.forceConnect(A11yEnabler.CONNECT_TIMEOUT_MS)
        assertNotNull(
            "a11y service never connected. enabled=" + enabler.enabledServices(),
            service,
        )

        TapHarnessActivity.resetClicks()
        enabler.launchHarness(A11yEnabler.HARNESS_TIMEOUT_MS)

        // Rect spanning the central area of the screen -> its center lands on the
        // full-bleed harness button regardless of resolution/orientation.
        val targetRect = RatioRect(Ratio(0.25), Ratio(0.4), Ratio(0.75), Ratio(0.6))

        val latch = CountDownLatch(1)
        val gestureOk = AtomicBoolean(false)
        service!!.tap(targetRect) { ok ->
            gestureOk.set(ok)
            latch.countDown()
        }

        assertTrue(
            "gesture completion callback never fired (cancel/resume seam broken)",
            latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        assertTrue("gesture reported cancellation / failure to dispatch", gestureOk.get())

        // The load-bearing assertion: a REAL click landed on the live button.
        assertTrue(
            "harness button onClick handler never fired. resumed=" +
                TapHarnessActivity.resumed + " clicks=" + TapHarnessActivity.clicks.get(),
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
        const val GESTURE_TIMEOUT_MS = 5_000L
        const val CLICK_TIMEOUT_MS = 5_000L
    }
}
