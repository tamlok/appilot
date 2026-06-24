package com.vnote.appilot.launch

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric JVM proof that an Android [Intent] survives the
 * stored-string round-trip via [LaunchTargetResolver] with zero loss.
 *
 * The two vendor evidence intents below are TEST-ONLY fixtures (allowed by the
 * plan's Must-NOT-have: "evidence strings live only in the spike + tests").
 * They never appear in `app/src/main` product code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTargetResolverTest {

    private val resolver = LaunchTargetResolver

    /** Tuya pinned-shortcut: VIEW + explicit component + 4 typed extras. */
    private fun tuyaIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        setClassName(
            "com.tuya.smartiot",
            "com.thingclips.smart.hometab.activity.shortcut",
        )
        putExtra("url", "thingSmart://pinned_shortcut")
        putExtra("shortcut_dev_id", "6cd07555416b69c0e3zu8u")
        putExtra("type", 1)
        putExtra("shortcut_home_id", 205535327L)
    }

    /** Haier pinned-shortcut: VIEW + data URI + explicit component. */
    private fun haierIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        data = android.net.Uri.parse("http://uplus.haier.com/x")
        setClassName(
            "com.haier.uhome.uplus",
            "com.haier.uhome.uplus.launcher.ShortCutLauncherActivity",
        )
    }

    @Test
    fun tuyaIntent_roundTripsLossless() {
        assertIntentRoundTrips(tuyaIntent())
    }

    @Test
    fun haierIntent_roundTripsLossless() {
        assertIntentRoundTrips(haierIntent())
    }

    @Test
    fun capturedShortcut_mapsToIntentAndBack_tuya() {
        val original = tuyaIntent()
        val captured = resolver.toCapturedShortcut(original, fallbackPackage = "com.tuya.smartiot")
        assertEquals("com.tuya.smartiot", captured.fallbackPackage)

        val rebuilt = resolver.toIntent(captured)
        assertIntentsEqual(original, rebuilt)
    }

    @Test
    fun capturedShortcut_mapsToIntentAndBack_haier() {
        val original = haierIntent()
        val captured =
            resolver.toCapturedShortcut(original, fallbackPackage = "com.haier.uhome.uplus")
        val rebuilt = resolver.toIntent(captured)
        assertIntentsEqual(original, rebuilt)
    }

    @Test
    fun serializedForm_usesIntentUriScheme() {
        val uri = resolver.serialize(tuyaIntent())
        // URI_INTENT_SCHEME serialization is parseable back to an equal intent.
        val parsed = resolver.deserialize(uri)
        assertNotNull(parsed)
        assertIntentsEqual(tuyaIntent(), parsed)
    }

    private fun assertIntentRoundTrips(original: Intent) {
        val uri = resolver.serialize(original)
        val parsed = resolver.deserialize(uri)
        assertIntentsEqual(original, parsed)
    }

    /** Asserts component + action + data + EVERY extra match exactly. */
    private fun assertIntentsEqual(expected: Intent, actual: Intent) {
        assertEquals("action", expected.action, actual.action)
        assertEquals("data", expected.dataString, actual.dataString)
        assertEquals("component", expected.component, actual.component)

        val expectedExtras = expected.extras
        val actualExtras = actual.extras
        if (expectedExtras == null) {
            // No extras expected; actual must also be empty/null.
            assertEquals("extras keys", emptySet<String>(), actualExtras?.keySet() ?: emptySet<String>())
            return
        }
        assertNotNull("actual extras missing", actualExtras)
        assertEquals("extras keys", expectedExtras.keySet(), actualExtras!!.keySet())
        for (key in expectedExtras.keySet()) {
            @Suppress("DEPRECATION")
            assertEquals(
                "extra[$key]",
                expectedExtras.get(key),
                actualExtras.get(key),
            )
        }
    }
}
