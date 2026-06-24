package com.vnote.appilot.launch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vnote.appilot.core.model.LaunchTarget
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke proof that [Launcher] dispatches against the real Android
 * framework without throwing.
 *
 * No vendor strings appear here: the benign target is THIS app's own launch
 * intent (guaranteed installed), and the fallback path is exercised with a
 * deliberately bogus explicit component that the framework cannot resolve.
 */
@RunWith(AndroidJUnit4::class)
class LauncherSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val launcher = Launcher.from(context)

    @Test
    fun launchesOwnApp_viaPackageLaunch_noException() {
        val outcome = launcher.launch(LaunchTarget.App(context.packageName))
        assertEquals(LaunchOutcome.PACKAGE, outcome)
    }

    @Test
    fun capturedShortcut_unresolvableComponent_fallsBackToInstalledPackage() {
        // A bogus explicit component the framework cannot resolve -> the
        // primary replay throws, and the launcher must fall back to the
        // (real, installed) fallback package without surfacing the exception.
        val bogus = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setClassName("com.example.absent", "com.example.absent.NoSuchActivity")
        val captured = LaunchTarget.CapturedShortcut(
            intentUri = LaunchTargetResolver.serialize(bogus),
            fallbackPackage = context.packageName,
        )

        val outcome = launcher.launch(captured)

        assertEquals(LaunchOutcome.FALLBACK_PACKAGE, outcome)
    }
}
