package com.vnote.appilot.launch

import android.content.ActivityNotFoundException
import android.content.Intent
import com.vnote.appilot.core.model.LaunchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric JVM proof of the [Launcher] dispatch + secure-fallback ORDERING.
 *
 * The launcher is built around two injectable seams — a `(Intent) -> Unit`
 * starter and a `(String) -> Intent?` package-launch resolver — so we can throw
 * `SecurityException` from the primary replay and assert the fallback starter is
 * invoked with the package-launch intent, with NO Android device or vendor
 * strings involved.
 *
 * Wave 0 ranking under test: 1) CapturedShortcut/explicit-component (primary) ->
 * 2) getLaunchIntentForPackage (fallback) -> 3) raw VIEW deep-link.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherTest {

    private val started = mutableListOf<Intent>()

    /** A package-launch intent tagged so tests can recognize the fallback. */
    private fun packageIntent(pkg: String): Intent =
        Intent("PKG_LAUNCH").putExtra("pkg", pkg)

    private fun launcher(
        starter: (Intent) -> Unit,
        launchIntentForPackage: (String) -> Intent? = { packageIntent(it) },
    ): Launcher = Launcher(starter = starter, launchIntentForPackage = launchIntentForPackage)

    @Test
    fun capturedShortcut_primarySucceeds_dispatchesExplicitIntent_noFallback() {
        val captured = LaunchTarget.CapturedShortcut(
            intentUri = LaunchTargetResolver.serialize(
                Intent(Intent.ACTION_VIEW).setClassName("p.kg", "p.kg.Act"),
            ),
            fallbackPackage = "p.kg",
        )
        val outcome = launcher(starter = { started += it }).launch(captured)

        assertEquals(LaunchOutcome.PRIMARY, outcome)
        assertEquals(1, started.size)
        assertEquals("p.kg/.Act", started[0].component?.flattenToShortString())
    }

    @Test
    fun capturedShortcut_securityException_fallsBackToPackageLaunch_inOrder() {
        val captured = LaunchTarget.CapturedShortcut(
            intentUri = LaunchTargetResolver.serialize(
                Intent(Intent.ACTION_VIEW).setClassName("p.kg", "p.kg.Act"),
            ),
            fallbackPackage = "p.kg",
        )
        // Primary (explicit component) throws; the tagged package intent succeeds.
        val outcome = launcher(
            starter = { intent ->
                if (intent.component != null) throw SecurityException("not exported")
                started += intent
            },
        ).launch(captured)

        assertEquals(LaunchOutcome.FALLBACK_PACKAGE, outcome)
        assertEquals(1, started.size)
        assertEquals("PKG_LAUNCH", started[0].action)
        assertEquals("p.kg", started[0].getStringExtra("pkg"))
    }

    @Test
    fun capturedShortcut_activityNotFound_alsoFallsBack() {
        val captured = LaunchTarget.CapturedShortcut(
            intentUri = LaunchTargetResolver.serialize(
                Intent(Intent.ACTION_VIEW).setClassName("p.kg", "p.kg.Act"),
            ),
            fallbackPackage = "p.kg",
        )
        val outcome = launcher(
            starter = { intent ->
                if (intent.component != null) throw ActivityNotFoundException("no activity")
                started += intent
            },
        ).launch(captured)

        assertEquals(LaunchOutcome.FALLBACK_PACKAGE, outcome)
        assertEquals("p.kg", started.single().getStringExtra("pkg"))
    }

    @Test
    fun capturedShortcut_fallbackPackageMissing_returnsFailed_noCrash() {
        val captured = LaunchTarget.CapturedShortcut(
            intentUri = LaunchTargetResolver.serialize(
                Intent(Intent.ACTION_VIEW).setClassName("p.kg", "p.kg.Act"),
            ),
            fallbackPackage = "p.kg",
        )
        val outcome = launcher(
            starter = { throw SecurityException("blocked") },
            launchIntentForPackage = { null },
        ).launch(captured)

        assertEquals(LaunchOutcome.FAILED, outcome)
        assertTrue(started.isEmpty())
    }

    @Test
    fun appTarget_dispatchesPackageLaunchIntent() {
        val outcome =
            launcher(starter = { started += it }).launch(LaunchTarget.App("com.example.app"))

        assertEquals(LaunchOutcome.PACKAGE, outcome)
        assertEquals("com.example.app", started.single().getStringExtra("pkg"))
    }

    @Test
    fun appTarget_missingPackage_returnsFailed() {
        val outcome = launcher(
            starter = { started += it },
            launchIntentForPackage = { null },
        ).launch(LaunchTarget.App("com.missing"))

        assertEquals(LaunchOutcome.FAILED, outcome)
        assertTrue(started.isEmpty())
    }

    @Test
    fun deepLink_dispatchesRawViewIntent() {
        val outcome = launcher(starter = { started += it })
            .launch(LaunchTarget.DeepLink("myscheme://device/page?id=7"))

        assertEquals(LaunchOutcome.DEEP_LINK, outcome)
        assertEquals(Intent.ACTION_VIEW, started.single().action)
        assertEquals("myscheme://device/page?id=7", started.single().dataString)
    }
}
