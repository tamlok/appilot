package com.vnote.appilot.actuate

import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vnote.appilot.actuate.harness.TapHarnessActivity
import com.vnote.appilot.core.model.GestureStep
import com.vnote.appilot.core.model.LaunchTarget
import com.vnote.appilot.core.model.Ratio
import com.vnote.appilot.core.model.RatioRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Instrumented proof that the desktop-open RECORDER receives real accessibility
 * events through the service while recording: a real click (the full-screen
 * harness button, tapped via the service) becomes the macro's Tap step, and the
 * macro finalizes when a foreign package (Settings) comes foreground — capturing
 * it as `expectedPackage`. This pins the live capture path end-to-end without
 * depending on a specific launcher layout.
 */
@RunWith(AndroidJUnit4::class)
class RecordIntegrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val enabler = A11yEnabler(instrumentation)

    @Before
    fun setUp() = enabler.prepareDevice()

    @After
    fun tearDown() = enabler.teardown()

    @Test
    fun recording_capturesRealClick_andFinalizesOnForeignWindow() {
        val service = enabler.forceConnect(A11yEnabler.CONNECT_TIMEOUT_MS)
        assertNotNull("a11y service never connected", service)

        val captured = AtomicReference<LaunchTarget.LauncherGesture?>(null)
        service!!.startRecording { captured.set(it) }
        Thread.sleep(1_500L)

        TapHarnessActivity.resetClicks()
        enabler.launchHarness(A11yEnabler.HARNESS_TIMEOUT_MS)

        val tapDone = CountDownLatch(1)
        service.tap(RatioRect(Ratio(0.25), Ratio(0.4), Ratio(0.75), Ratio(0.6))) { tapDone.countDown() }
        tapDone.await(5_000L, TimeUnit.MILLISECONDS)
        assertTrue("harness click never registered", awaitClicks(min = 1, timeoutMs = 5_000L))

        context.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        val gesture = awaitCaptured(captured, timeoutMs = 8_000L)
        assertNotNull(
            "recording never finalized — a TYPE_VIEW_CLICKED or window event was not received by the service",
            gesture,
        )
        assertEquals("com.android.settings", gesture!!.expectedPackage)
        val tap = gesture.steps.last() as GestureStep.Tap
        assertTrue("tap not near the harness button center", tap.spot.x.value in 0.2..0.8)
    }

    private fun awaitCaptured(
        ref: AtomicReference<LaunchTarget.LauncherGesture?>,
        timeoutMs: Long,
    ): LaunchTarget.LauncherGesture? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            ref.get()?.let { return it }
            android.os.SystemClock.sleep(200L)
        }
        return ref.get()
    }

    private fun awaitClicks(min: Int, timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (TapHarnessActivity.clicks.get() >= min) return true
            android.os.SystemClock.sleep(100L)
        }
        return TapHarnessActivity.clicks.get() >= min
    }
}
