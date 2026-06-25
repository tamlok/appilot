package com.vnote.appilot.core.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vnote.appilot.core.model.AcAction
import com.vnote.appilot.core.model.LaunchTarget
import com.vnote.appilot.core.model.Ratio
import com.vnote.appilot.core.model.RatioRect
import com.vnote.appilot.core.model.ReadRegion
import com.vnote.appilot.core.model.RegulatorConfig
import com.vnote.appilot.core.model.TapTarget
import com.vnote.appilot.core.model.TempBand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented proof that the config store round-trips on a real device:
 * save -> reload yields an equal [RegulatorConfig], and digit-template PNG
 * bytes are restored byte-identical.
 */
@RunWith(AndroidJUnit4::class)
class ConfigStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val configStore = ConfigStore(context)
    private val templateStore = TemplateStore(context)

    @Before
    fun reset() = runBlocking {
        configStore.clear()
        templateStore.clear()
    }

    @Test
    fun absentConfig_loadsAsNull() = runBlocking {
        assertNull(configStore.load())
    }

    @Test
    fun saveThenLoad_roundTripsEqual_acrossAllLaunchTargetVariants() = runBlocking {
        // All three LaunchTarget variants must survive persistence; pair them
        // into source/actuator slots so every variant is exercised.
        val variants = listOf(
            LaunchTarget.App(packageName = "com.example.source"),
            LaunchTarget.DeepLink(uri = "myscheme://device/page?id=42"),
            LaunchTarget.CapturedShortcut(
                intentUri = "intent://x#Intent;action=android.intent.action.VIEW;end",
                fallbackPackage = "com.example.fallback",
            ),
        )

        for (source in variants) {
            for (actuator in variants) {
                val config = fullConfig(source, actuator)
                configStore.save(config)
                assertEquals(config, configStore.load())
                assertEquals(config, configStore.configFlow.first())
            }
        }
    }

    @Test
    fun digitTemplate_roundTripsByteIdentical() {
        val key = "digit_5"
        val png = fixturePngBytes()

        templateStore.saveTemplate(key, png)
        val restored = templateStore.loadTemplate(key)

        assertArrayEquals(png, restored)
        assertEquals(listOf(key), templateStore.templateKeys())
    }

    @Test
    fun capturedIntentUri_roundTripsExact() {
        val key = "source_shortcut"
        val uri = "intent://device#Intent;component=pkg/.Act;S.k=v%20w;end"

        templateStore.saveIntentUri(key, uri)

        assertEquals(uri, templateStore.loadIntentUri(key))
    }

    private fun fullConfig(
        source: LaunchTarget,
        actuator: LaunchTarget,
    ): RegulatorConfig = RegulatorConfig(
        band = TempBand(lowC = 19.5, highC = 25.5),
        step = 0.5,
        intervalMinutes = 15,
        source = source,
        actuator = actuator,
        tapTargets = listOf(
            TapTarget(rect(0.10, 0.80, 0.30, 0.95), AcAction.TEMP_DOWN),
            TapTarget(rect(0.70, 0.80, 0.90, 0.95), AcAction.TEMP_UP),
            TapTarget(rect(0.40, 0.80, 0.60, 0.95), AcAction.SWING),
            TapTarget(rect(0.40, 0.05, 0.60, 0.15), AcAction.Custom("MODE")),
        ),
        readRegion = ReadRegion(rect(0.42, 0.40, 0.58, 0.52)),
    )

    private fun rect(l: Double, t: Double, r: Double, b: Double): RatioRect =
        RatioRect(Ratio(l), Ratio(t), Ratio(r), Ratio(b))

    /** A small, valid 1x1 PNG (red pixel) used as a digit-template fixture. */
    private fun fixturePngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
        0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
        0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(),
        0xC0.toByte(), 0x00, 0x00, 0x00, 0x03, 0x00, 0x01,
        0x18, 0xDD.toByte(), 0x8D.toByte(), 0xB0.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
        0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )
}
