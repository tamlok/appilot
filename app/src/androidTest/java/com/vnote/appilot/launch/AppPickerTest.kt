package com.vnote.appilot.launch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vnote.appilot.core.model.LaunchTarget
import com.vnote.appilot.core.store.ConfigStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented pick-and-store roundtrip for [AppPicker] on a real device:
 * enumerate launcher apps -> pick one -> resolve to [LaunchTarget.App] ->
 * persist via [ConfigStore] -> reload equal.
 *
 * No vendor package is named: the picked app is discovered at runtime (this
 * app's own launcher entry is always present, proving the scoped `<queries>`
 * visibility works without QUERY_ALL_PACKAGES).
 */
@RunWith(AndroidJUnit4::class)
class AppPickerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ConfigStore(context)

    @Before
    fun reset() = runBlocking { store.clear() }

    @Test
    fun enumeratesLauncherApps_pickStoreReload_roundTrips() = runBlocking {
        val apps = AppPicker.from(context).installedLauncherApps()
        assertTrue("expected at least one launchable app", apps.isNotEmpty())
        // Our own app must be visible via the scoped MAIN/LAUNCHER <queries>.
        assertTrue(
            "own package should be enumerable",
            apps.any { it.target.packageName == context.packageName },
        )

        val picked = apps.first { it.target.packageName == context.packageName }.target
        val config = ConfigStore.DEFAULT.copy(source = picked)
        store.save(config)

        val reloaded = store.load()
        assertEquals(picked, reloaded?.source)
        assertEquals(config, reloaded)
    }
}
