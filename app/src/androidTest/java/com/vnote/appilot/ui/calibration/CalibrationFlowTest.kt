package com.vnote.appilot.ui.calibration

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vnote.appilot.core.model.AcAction
import com.vnote.appilot.core.model.RegulatorConfig
import com.vnote.appilot.core.store.ConfigStore
import com.vnote.appilot.launch.Presets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the calibration flow end-to-end on-device and proves the persisted
 * [RegulatorConfig] equals what was entered.
 *
 * The live screenshot step (MediaProjection consent + frame grab) is non-
 * deterministic and needs a system dialog, so [CalibrationDeps.screenshotOverride]
 * is set to a fixture [Bitmap] for the test; everything else (target pick, region
 * drag, tap-target drag, thresholds, save) is exercised through the real UI. The
 * "entered" config is read straight off the activity's view model, then compared
 * against an independent [ConfigStore.load] — not merely asserting save() ran.
 */
@RunWith(AndroidJUnit4::class)
class CalibrationFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<CalibrationActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ConfigStore(context)

    @Before
    fun setUp() = runBlocking {
        store.clear()
        CalibrationDeps.screenshotOverride = { fixtureBitmap() }
    }

    @After
    fun tearDown() = runBlocking {
        CalibrationDeps.screenshotOverride = null
        store.clear()
    }

    @Test
    fun configuresPairFromScratch_persistsExactlyWhatWasEntered() {
        // Step (a) — targets are a fixed preset (Tuya source + Haier actuator),
        // shown read-only; nothing to pick, just advance.
        rule.onNodeWithTag("nextButton").performClick()

        // Step (b) — read region: capture (fixture) then drag a rectangle.
        rule.onNodeWithTag("captureScreenshot").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { rule.activity.viewModel.screenshot != null }
        rule.onNodeWithTag("regionOverlay").performTouchInput {
            swipe(percentOffset(0.2f, 0.30f), percentOffset(0.6f, 0.55f), durationMillis = 300)
        }
        rule.onNodeWithTag("captureGlyphs").performClick()
        rule.onNodeWithTag("nextButton").performClick()

        // Step (c) — tap target: drag one rectangle bound to TEMP_DOWN (default).
        rule.onNodeWithTag("tapOverlay").performTouchInput {
            swipe(percentOffset(0.1f, 0.80f), percentOffset(0.35f, 0.95f), durationMillis = 300)
        }
        rule.onNodeWithTag("nextButton").performClick()

        // Step (d) — thresholds.
        rule.onNodeWithTag("lowField").performScrollTo().performTextReplacement("19.0")
        rule.onNodeWithTag("highField").performScrollTo().performTextReplacement("25.0")
        rule.onNodeWithTag("stepField").performScrollTo().performTextReplacement("0.5")
        rule.onNodeWithTag("intervalField").performScrollTo().performTextReplacement("15")
        rule.waitForIdle()

        var entered: RegulatorConfig? = null
        rule.runOnUiThread { entered = rule.activity.viewModel.config }

        rule.onNodeWithTag("saveButton").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { rule.activity.viewModel.status == "Saved" }

        val reloaded = runBlocking { store.load() }

        // stale_state probe: an independent reload must equal the entered snapshot.
        assertNotNull("config should be persisted", reloaded)
        assertEquals(entered, reloaded)

        // misleading_success probe: assert the real field values, not just save().
        assertEquals(Presets.tuyaSource(), reloaded!!.source)
        assertEquals(Presets.haierActuator(), reloaded.actuator)
        assertEquals(19.0, reloaded.band.lowC, 0.0)
        assertEquals(25.0, reloaded.band.highC, 0.0)
        assertEquals(0.5, reloaded.step, 0.0)
        assertEquals(15, reloaded.intervalMinutes)
        assertTrue("a tap target was marked", reloaded.tapTargets.isNotEmpty())
        assertEquals(AcAction.TEMP_DOWN, reloaded.tapTargets.first().action)
        assertNotEquals(
            "read region must be the dragged rect, not the default placeholder",
            ConfigStore.DEFAULT.readRegion,
            reloaded.readRegion,
        )
    }

    private fun fixtureBitmap(): Bitmap =
        Bitmap.createBitmap(480, 1040, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
}
