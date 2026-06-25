package com.vnote.appilot.actuate

import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
 * (emulator-5554, Android 15).
 *
 * Enabling flow: the test grants ACCESS_RESTRICTED_SETTINGS (else Android 13+
 * silently drops the enabled_accessibility_services write for a sideloaded app),
 * writes the service component + accessibility_enabled, waits for
 * [RegulatorAccessibilityService.instance], launches the full-screen
 * [TapHarnessActivity], and calls [Tapper.tap] over a rect covering the button.
 *
 * CRITICAL: the instrumentation's [UiAutomation] suppresses ALL other
 * accessibility services by default, so the service can NEVER bind unless the
 * connection is taken with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES. The whole
 * test therefore routes shell access through that one flagged UiAutomation and
 * never touches UiDevice (whose no-arg getUiAutomation() would flip suppression
 * back on).
 *
 * misleading_success guard: success is asserted on the harness button's REAL
 * onClick counter ([TapHarnessActivity.clicks]) — NOT merely on the gesture's
 * own completion callback. The completion callback is separately asserted to
 * prove the cancel/resume seam (a cancelled gesture would report false).
 */
@RunWith(AndroidJUnit4::class)
class RegulatorAccessibilityServiceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val uiAutomation: UiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private val component =
        "${context.packageName}/${RegulatorAccessibilityService::class.java.name}"

    @Before
    fun wakeAndAllowRestrictedSettings() {
        shell("svc power stayon true")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow")
    }

    @After
    fun disableService() {
        // Cleanup receipt: disable the a11y service so no enabled service or
        // bound instance survives the run, and drop the harness from the top.
        shell("settings put secure accessibility_enabled 0")
        shell("settings delete secure enabled_accessibility_services")
        shell("appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS default")
        shell("input keyevent KEYCODE_HOME")
        shell("svc power stayon false")
    }

    @Test
    fun tap_firesRealClickHandlerOnHarnessButton() {
        val service = forceConnect(CONNECT_TIMEOUT_MS)
        assertNotNull(
            "a11y service never connected. enabled=" +
                shell("settings get secure enabled_accessibility_services").trim() +
                " a11yEnabled=" + shell("settings get secure accessibility_enabled").trim(),
            service,
        )

        TapHarnessActivity.resetClicks()
        launchHarness()

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

    /**
     * Bind the service into THIS (already-running, instrumented) process. AMS
     * binds reliably for a freshly-spawned process, but for a live one the bind
     * needs a clean accessibility_enabled 0 -> 1 transition with the list+appop
     * already in place, and sometimes a re-toggle — so each round re-writes the
     * list (retried until it sticks past the restricted gate), toggles, then polls
     * the instance before re-toggling.
     */
    private fun forceConnect(timeoutMs: Long): RegulatorAccessibilityService? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            shell("settings put secure accessibility_enabled 0")
            SystemClock.sleep(300L)
            if (!writeEnabledServicesSticks(component)) continue
            shell("settings put secure accessibility_enabled 1")
            val roundEnd = SystemClock.uptimeMillis() + RETOGGLE_INTERVAL_MS
            while (SystemClock.uptimeMillis() < roundEnd) {
                RegulatorAccessibilityService.instance?.let { return it }
                SystemClock.sleep(200L)
            }
        }
        return RegulatorAccessibilityService.instance
    }

    private fun writeEnabledServicesSticks(target: String): Boolean {
        repeat(WRITE_RETRIES) {
            shell("settings put secure enabled_accessibility_services $target")
            if (shell("settings get secure enabled_accessibility_services").trim() == target) {
                return true
            }
            SystemClock.sleep(300L)
        }
        return false
    }

    private fun launchHarness() {
        TapHarnessActivity.resumed = false
        val intent = Intent(context, TapHarnessActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        // Wait until the harness window actually OWNS input focus: a gesture
        // injected before the window is the current focus is not delivered.
        val deadline = SystemClock.uptimeMillis() + HARNESS_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (TapHarnessActivity.resumed && harnessHasInputFocus()) break
            SystemClock.sleep(150L)
        }
        SystemClock.sleep(500L)
    }

    private fun harnessHasInputFocus(): Boolean =
        shell("dumpsys window").lineSequence()
            .filter { it.contains("mCurrentFocus") }
            .any { it.contains("TapHarnessActivity") }

    private fun awaitClicks(min: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (TapHarnessActivity.clicks.get() >= min) return true
            SystemClock.sleep(100L)
        }
        return TapHarnessActivity.clicks.get() >= min
    }

    private fun shell(cmd: String): String {
        val pfd = uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            return stream.readBytes().toString(Charsets.UTF_8)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val RETOGGLE_INTERVAL_MS = 5_000L
        const val HARNESS_TIMEOUT_MS = 5_000L
        const val GESTURE_TIMEOUT_MS = 5_000L
        const val CLICK_TIMEOUT_MS = 5_000L
        const val WRITE_RETRIES = 8
    }
}
