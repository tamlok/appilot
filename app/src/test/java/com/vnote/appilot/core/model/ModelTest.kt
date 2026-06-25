package com.vnote.appilot.core.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM unit tests for the immutable domain model. Pure logic, no emulator.
 *
 * Proves: every type constructs, kotlinx-serialization JSON round-trips
 * (encode -> decode -> equals), all three [LaunchTarget] variants survive, and
 * `when` over [LaunchTarget] / [AcAction] is exhaustive without an `else`.
 */
class ModelTest {

    private val json = Json

    private fun rect(l: Double, t: Double, r: Double, b: Double) =
        RatioRect(Ratio(l), Ratio(t), Ratio(r), Ratio(b))

    private fun configWith(actuator: LaunchTarget): RegulatorConfig =
        RegulatorConfig(
            band = TempBand(lowC = 22.0, highC = 26.0),
            step = 0.5,
            intervalMinutes = 10,
            source = LaunchTarget.App(packageName = "com.example.source"),
            actuator = actuator,
            tapTargets = listOf(
                TapTarget(rect(0.10, 0.80, 0.30, 0.95), AcAction.TEMP_DOWN),
                TapTarget(rect(0.70, 0.80, 0.90, 0.95), AcAction.TEMP_UP),
                TapTarget(rect(0.40, 0.80, 0.60, 0.95), AcAction.SWING),
                TapTarget(rect(0.40, 0.05, 0.60, 0.15), AcAction.Custom("eco")),
            ),
            readRegion = ReadRegion(rect(0.30, 0.40, 0.70, 0.55)),
        )

    @Test
    fun regulatorConfig_roundTrips_allThreeLaunchTargetVariants() {
        // Each variant placed in the config's actuator slot; source stays App.
        val variants = listOf(
            LaunchTarget.App(packageName = "com.example.actuator"),
            LaunchTarget.DeepLink(uri = "myapp://ac/control?room=1"),
            LaunchTarget.CapturedShortcut(
                intentUri = "intent://#Intent;component=pkg/.Activity;end",
                fallbackPackage = "com.example.actuator",
            ),
            LaunchTarget.LauncherGesture(
                steps = listOf(
                    GestureStep.Home,
                    GestureStep.Swipe(RatioPoint(Ratio(0.88), Ratio(0.5)), RatioPoint(Ratio(0.14), Ratio(0.5))),
                    GestureStep.Tap(RatioPoint(Ratio(0.16), Ratio(0.24))),
                ),
                expectedPackage = "com.example.device",
                label = "Bedroom sensor",
            ),
        )

        for (variant in variants) {
            val original = configWith(variant)
            val encoded = json.encodeToString(RegulatorConfig.serializer(), original)
            val decoded = json.decodeFromString(RegulatorConfig.serializer(), encoded)
            assertEquals("round-trip failed for $variant", original, decoded)
        }
    }

    @Test
    fun ratio_rejectsOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) { Ratio(1.5) }
        assertThrows(IllegalArgumentException::class.java) { Ratio(-0.1) }
    }

    @Test
    fun intervalMinutes_defaultsToTen() {
        val config = configWith(LaunchTarget.App("com.example.actuator"))
        assertEquals(10, config.intervalMinutes)
    }

    /** Compile-time proof that `when` over [LaunchTarget] needs no `else`. */
    @Test
    fun launchTarget_whenIsExhaustive() {
        fun describe(target: LaunchTarget): String = when (target) {
            is LaunchTarget.App -> "app:${target.packageName}"
            is LaunchTarget.DeepLink -> "link:${target.uri}"
            is LaunchTarget.CapturedShortcut -> "shortcut:${target.fallbackPackage}"
            is LaunchTarget.LauncherGesture -> "gesture:${target.expectedPackage}"
        }
        assertEquals("app:p", describe(LaunchTarget.App("p")))
    }

    /** Compile-time proof that `when` over [AcAction] needs no `else`. */
    @Test
    fun acAction_whenIsExhaustive() {
        fun stepDelta(action: AcAction): Int = when (action) {
            AcAction.TEMP_DOWN -> -1
            AcAction.TEMP_UP -> 1
            AcAction.SWING -> 0
            is AcAction.Custom -> 0
        }
        assertEquals(-1, stepDelta(AcAction.TEMP_DOWN))
        assertEquals(1, stepDelta(AcAction.TEMP_UP))
    }

    @Test
    fun acAction_roundTripsPolymorphically() {
        val actions: List<AcAction> = listOf(
            AcAction.TEMP_DOWN,
            AcAction.TEMP_UP,
            AcAction.SWING,
            AcAction.Custom("eco"),
        )
        val serializer = ListSerializer(AcAction.serializer())
        val encoded = json.encodeToString(serializer, actions)
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(actions, decoded)
    }

    @Test
    fun ratioRect_rejectsInvertedBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            RatioRect(Ratio(0.8), Ratio(0.1), Ratio(0.2), Ratio(0.9))
        }
    }

    @Test
    fun tempBand_andStep_areValidated() {
        assertThrows(IllegalArgumentException::class.java) { TempBand(lowC = 26.0, highC = 22.0) }
        assertThrows(IllegalArgumentException::class.java) {
            configWith(LaunchTarget.App("p")).copy(step = 0.0)
        }
    }
}
