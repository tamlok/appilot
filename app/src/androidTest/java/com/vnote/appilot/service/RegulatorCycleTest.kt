package com.vnote.appilot.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vnote.appilot.actuate.A11yEnabler
import com.vnote.appilot.core.model.AcAction
import com.vnote.appilot.core.model.LaunchTarget
import com.vnote.appilot.core.model.Ratio
import com.vnote.appilot.core.model.RatioRect
import com.vnote.appilot.core.model.ReadRegion
import com.vnote.appilot.core.model.RegulatorConfig
import com.vnote.appilot.core.model.TapTarget
import com.vnote.appilot.core.model.TempBand
import com.vnote.appilot.core.store.ConfigStore
import com.vnote.appilot.core.store.TemplateStore
import com.vnote.appilot.decide.RegulatorAction
import com.vnote.appilot.launch.LaunchTargetResolver
import com.vnote.appilot.read.projection.MediaProjectionSession
import com.vnote.appilot.read.projection.ScreenCapture
import com.vnote.appilot.service.harness.HarnessAcActivity
import com.vnote.appilot.service.harness.HarnessSourceActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented proof of the Wave 6 orchestration cycle on emulator-5554
 * (Android 15). Drives exactly ONE full cycle against the in-repo harness pair —
 * NO Tuya/Haier — and asserts the REAL effects, not just intermediate decisions:
 *
 *  - launch source (HarnessSourceActivity, temp 30 drawn as glyphs) ->
 *    readiness -> projection capture -> crop ReadRegion -> template match -> 30.0
 *  - decide: 30 > band.high(26) -> DOWN
 *  - launch actuator (HarnessAcActivity) -> readiness -> tap N = step (=3)
 *  - assert the harness tap counter went up by EXACTLY step (misleading_success
 *    guard: the live onClick counter, not the gesture callback), the FGS started
 *    with mediaProjection|specialUse, and the next cycle was rescheduled.
 *
 * Setup auto-grants: a11y via [A11yEnabler]'s flagged-UiAutomation technique, and
 * MediaProjection consent via the `PROJECT_MEDIA` appop (RESULT_OK with no UI on
 * this image — so we never touch UiDevice, which would re-suppress the a11y
 * service). Teardown releases projection, cancels the alarm and disables a11y.
 */
@RunWith(AndroidJUnit4::class)
class RegulatorCycleTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val enabler = A11yEnabler(instrumentation)

    @Before
    fun setUp() {
        enabler.prepareDevice()
        enabler.shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        enabler.shell("appops set ${context.packageName} PROJECT_MEDIA allow")
        seedTemplates()
        ForegroundRegistry.reset()
        HarnessAcActivity.resetClicks()
        RegulatorService.lastAction = RegulatorAction.NONE
        MediaProjectionSession.clear()
    }

    @After
    fun tearDown() {
        ScreenCapture.release(context)
        RegulatorScheduler.cancel(context)
        runBlocking { ConfigStore(context).clear() }
        enabler.teardown()
        ForegroundRegistry.reset()
    }

    @Test
    fun oneCycle_readsAboveBand_tapsActuatorStepTimes() {
        val service = enabler.forceConnect(A11yEnabler.CONNECT_TIMEOUT_MS)
        assertNotNull("a11y never connected. enabled=" + enabler.enabledServices(), service)

        // Pre-warm projection consent so no dialog steals foreground mid-cycle.
        grantProjectionConsent()
        assertTrue("consent should be cached", MediaProjectionSession.hasConsent())

        val step = 3
        runBlocking { ConfigStore(context).save(harnessConfig(step)) }

        val baseline = RegulatorService.cycleCompletions.get()
        RegulatorService.start(context)

        assertTrue(
            "cycle never completed. lastResult=${RegulatorService.lastCycleResult} err=${RegulatorService.lastError}",
            awaitCondition(CYCLE_TIMEOUT_MS) { RegulatorService.cycleCompletions.get() > baseline },
        )

        // Load-bearing assertion: real taps landed on the live actuator button.
        assertEquals(
            "actuator counter must rise by exactly step. result=${RegulatorService.lastCycleResult}" +
                " err=${RegulatorService.lastError}",
            step,
            HarnessAcActivity.clicks.get(),
        )

        val types = RegulatorService.lastForegroundServiceType
        assertTrue(
            "FGS must declare mediaProjection (was $types)",
            types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0,
        )
        assertTrue(
            "FGS must declare specialUse (was $types)",
            types and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0,
        )
        assertTrue("next cycle must be rescheduled", RegulatorScheduler.isScheduled(context))
        assertTrue(
            "decision must have acted DOWN, was ${RegulatorService.lastCycleResult}",
            (RegulatorService.lastCycleResult as? CycleResult.Acted)?.action == RegulatorAction.DOWN,
        )
    }

    /** Source/actuator are explicit-component CapturedShortcuts to the harness. */
    private fun harnessConfig(step: Int): RegulatorConfig {
        val sourceIntent = Intent(context, HarnessSourceActivity::class.java)
            .putExtra(HarnessSourceActivity.EXTRA_TEMP, 30.0)
        val actuatorIntent = Intent(context, HarnessAcActivity::class.java)
        return RegulatorConfig(
            band = TempBand(lowC = 18.0, highC = 26.0),
            step = step.toDouble(),
            intervalMinutes = 10,
            source = LaunchTargetResolver.toCapturedShortcut(sourceIntent, context.packageName),
            actuator = LaunchTargetResolver.toCapturedShortcut(actuatorIntent, context.packageName),
            tapTargets = listOf(
                TapTarget(RatioRect(Ratio(0.25), Ratio(0.4), Ratio(0.75), Ratio(0.6)), AcAction.TEMP_DOWN),
            ),
            readRegion = ReadRegion(RatioRect(Ratio(0.20), Ratio(0.38), Ratio(0.80), Ratio(0.62))),
        )
    }

    /** Seed the matcher's templates from the bundled debug glyph assets. */
    private fun seedTemplates() {
        val store = TemplateStore(context)
        val keys = (0..9).map { it.toString() } + listOf("dot", "minus")
        for (key in keys) {
            context.assets.open("harness_digits/$key.png").use { store.saveTemplate(key, it.readBytes()) }
        }
    }

    private fun grantProjectionConsent() {
        var granted = false
        val worker = Thread { granted = ScreenCapture.requestConsent(context, 20_000L) }
        worker.start()
        worker.join(25_000L)
        assertTrue("projection consent not granted via appop", granted)
    }

    private fun awaitCondition(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (cond()) return true
            SystemClock.sleep(200L)
        }
        return cond()
    }

    private companion object {
        const val CYCLE_TIMEOUT_MS = 90_000L
    }
}
