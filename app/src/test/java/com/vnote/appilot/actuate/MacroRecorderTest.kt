package com.vnote.appilot.actuate

import com.vnote.appilot.core.model.GestureStep
import com.vnote.appilot.core.model.LaunchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM proof of the desktop-open macro recorder's step-building logic
 * (clock injected, no device): home prefix, page swipes (debounced), one tap at
 * the clicked node center, and finalization only on a non-launcher window.
 */
class MacroRecorderTest {

    private var clock = 1_000L
    private var result: LaunchTarget.LauncherGesture? = null

    private fun recorder() = MacroRecorder(now = { clock }) { result = it }

    @Test
    fun buildsHomeSwipeTap_andFinalizesOnlyOnTargetWindow() {
        val r = recorder()
        r.onScrolled(toRightPage = true)
        r.onClicked("com.android.launcher", 0.16, 0.235)

        r.onWindowState("com.android.launcher", OWN)
        assertNull("launcher window must not finalize", result)
        r.onWindowState(OWN, OWN)
        assertNull("own window must not finalize", result)

        r.onWindowState("com.tuya.smartiot", OWN)
        val gesture = result!!
        assertEquals("com.tuya.smartiot", gesture.expectedPackage)
        assertEquals(3, gesture.steps.size)
        assertTrue(gesture.steps[0] is GestureStep.Home)
        assertTrue(gesture.steps[1] is GestureStep.Swipe)
        val tap = gesture.steps[2] as GestureStep.Tap
        assertEquals(0.16, tap.spot.x.value, 1e-9)
        assertEquals(0.235, tap.spot.y.value, 1e-9)
    }

    @Test
    fun debouncesRapidScrolls_butKeepsDistinctPages() {
        val r = recorder()
        r.onScrolled(true)
        clock += 100
        r.onScrolled(true)
        clock += 1_000
        r.onScrolled(true)
        r.onClicked("l", 0.5, 0.5)
        r.onWindowState("app", OWN)

        assertEquals(4, result!!.steps.size)
    }

    @Test
    fun ignoresEventsAfterTap() {
        val r = recorder()
        r.onClicked("l", 0.3, 0.3)
        r.onScrolled(true)
        r.onClicked("l", 0.9, 0.9)
        r.onWindowState("app", OWN)

        val gesture = result!!
        assertEquals(2, gesture.steps.size)
        assertEquals(0.3, (gesture.steps[1] as GestureStep.Tap).spot.x.value, 1e-9)
    }

    private companion object {
        const val OWN = "com.vnote.appilot"
    }
}
