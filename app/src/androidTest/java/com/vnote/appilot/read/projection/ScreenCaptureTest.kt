package com.vnote.appilot.read.projection

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Instrumented proof of the MediaProjection single-frame capture on a real
 * device (emulator-5554, Android 15).
 *
 * Consent is AUTO-GRANTED with TWO complementary, locale-independent tactics:
 *  - the test pre-grants the `PROJECT_MEDIA` appop via `adb shell appops`, which
 *    on this image makes the system consent activity return `RESULT_OK` without
 *    UI; and
 *  - as a fallback, [ScreenCapture.requestConsent] launches the system dialog on
 *    a worker thread while this thread uses `uiautomator` to click the positive
 *    button - found by the AlertDialog `android:id/button1` resource-id (NOT
 *    text, since this emulator runs a Chinese locale).
 * Either way the grant is cached in [MediaProjectionSession] for the session.
 *
 * The test then (a) captures one frame and asserts a non-null [Bitmap] at the
 * real display dimensions that is NOT uniformly one colour (>= 2 distinct ARGB
 * values, proving a real screenshot rather than a blank buffer), and (b) captures
 * a SECOND frame WITHOUT re-prompting, proving the cached-consent reuse contract.
 */
@RunWith(AndroidJUnit4::class)
class ScreenCaptureTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun prepareDevice() {
        // POST_NOTIFICATIONS (33+) so the FGS notification is allowed - granted
        // directly, no dialog to drive.
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            "android.permission.POST_NOTIFICATIONS",
        )
        // Pre-grant the media-projection appop: on this image it makes the system
        // consent activity return RESULT_OK without showing UI (uiautomator click
        // below is the fallback if it still prompts).
        shell("appops set ${context.packageName} PROJECT_MEDIA allow")
        // Clear stray background ANR dialogs from earlier waves, then land on the
        // launcher home screen - a known, colourful (>= 2 colours) surface so the
        // capture is provably non-blank.
        shell("am kill-all")
        shell("input keyevent KEYCODE_BACK")
        // Keep the screen on, awake and unlocked so the capture is not black.
        shell("svc power stayon true")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("input keyevent KEYCODE_HOME")
        MediaProjectionSession.clear()
        device.waitForIdle()
    }

    @After
    fun teardown() {
        // Cleanup receipt: stop the projection + foreground service and forget the
        // consent token so no VirtualDisplay / FGS survives the test.
        ScreenCapture.release(context)
        shell("svc power stayon false")
        device.waitForIdle()
    }

    @Test
    fun capturesNonBlankFrame_andReusesConsent() {
        val (expectedW, expectedH) = realDisplaySize()

        grantConsentViaDialog()
        assertTrue("consent should be cached after granting", MediaProjectionSession.hasConsent())

        val first = ScreenCapture.captureFrame(context)
        assertDisplayBitmap(first, expectedW, expectedH)

        // Reuse: no second dialog is shown; the live session projection is reused
        // (the single-use consent token is NOT re-fetched).
        val second = ScreenCapture.captureFrame(context)
        assertTrue("consent must remain cached for reuse", MediaProjectionSession.hasConsent())
        assertDisplayBitmap(second, expectedW, expectedH)
    }

    private fun assertDisplayBitmap(bitmap: Bitmap, expectedW: Int, expectedH: Int) {
        assertEquals("bitmap width", expectedW, bitmap.width)
        assertEquals("bitmap height", expectedH, bitmap.height)
        assertTrue(
            "bitmap must be a real screenshot (>= 2 distinct pixel values), not blank",
            distinctSampledColors(bitmap) >= 2,
        )
    }

    /** Sample a grid of pixels and count distinct ARGB values. */
    private fun distinctSampledColors(bitmap: Bitmap): Int {
        val colors = HashSet<Int>()
        val steps = 12
        for (i in 1 until steps) {
            for (j in 1 until steps) {
                val x = bitmap.width * i / steps
                val y = bitmap.height * j / steps
                colors.add(bitmap.getPixel(x, y))
            }
        }
        return colors.size
    }

    /**
     * Drive consent: launch the system dialog on a worker thread (the call
     * blocks until answered) and click the positive button from this thread.
     */
    private fun grantConsentViaDialog() {
        var granted = false
        val worker = Thread {
            granted = ScreenCapture.requestConsent(context, timeoutMs = 30_000L)
        }
        worker.start()

        val deadline = SystemClock.uptimeMillis() + 15_000L
        var handled = false
        while (SystemClock.uptimeMillis() < deadline) {
            if (!worker.isAlive) {
                handled = true
                break
            }
            val button = findConsentButton()
            if (button != null) {
                button.click()
                handled = true
                break
            }
            SystemClock.sleep(250L)
        }

        worker.join(20_000L)
        assertTrue("consent was neither auto-granted nor dialog-driven", handled)
        assertTrue("consent was not granted", granted)
    }

    private fun findConsentButton(): UiObject2? {
        device.findObject(By.res("android:id/button1"))?.let { return it }
        device.findObject(By.clazz("android.widget.Button").text(POSITIVE_BUTTON))?.let { return it }
        return device.findObject(By.text(POSITIVE_BUTTON))
    }

    private fun realDisplaySize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    private fun shell(cmd: String) {
        device.executeShellCommand(cmd)
    }

    private companion object {
        // Locale-independent positive-button fallback: this emulator runs a
        // Chinese locale, so we match the Android 14/15 label "立即开始" / "开始"
        // / "允许" alongside the English variants ("start now" / "start" /
        // "allow"). The primary selector is the AlertDialog button1 resource-id.
        val POSITIVE_BUTTON: Pattern =
            Pattern.compile("(?i)(start now|start recording|start|allow|立即开始|开始|允许|启动|确定)")
    }
}
