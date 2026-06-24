package com.vnote.appilot.launch

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vnote.appilot.core.model.LaunchTarget
import com.vnote.appilot.core.store.ConfigStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented proof that a captured `EXTRA_SHORTCUT_INTENT` round-trips
 * losslessly through [CreateShortcutPicker] -> [LaunchTargetResolver] ->
 * [ConfigStore] and back.
 *
 * The CRITICAL Wave 0 guarantee is the data-URI PATH surviving intact (dumpsys
 * redacts it; the live capture does not). We construct a shortcut payload with a
 * full path + typed extras and assert every field is preserved after a real
 * persist/reload. No vendor strings appear — `com.example.*` placeholders only.
 */
@RunWith(AndroidJUnit4::class)
class CreateShortcutPickerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ConfigStore(context)

    @Before
    fun reset() = runBlocking { store.clear() }

    @Test
    fun shortcutPayload_capturesAndRoundTripsThroughStore_lossless() = runBlocking {
        val inner = Intent(Intent.ACTION_VIEW).apply {
            setClassName("com.example.device", "com.example.device.PageActivity")
            data = Uri.parse("https://example.com/device/area/42?tab=temp")
            putExtra("url", "myscheme://pinned_shortcut")
            putExtra("dev_id", "abc123def456")
            putExtra("type", 1)
            putExtra("home_id", 205535327L)
        }
        // Emulate the launcher's CREATE_SHORTCUT result envelope.
        val result = Intent().putExtra(Intent.EXTRA_SHORTCUT_INTENT, inner)

        val captured = CreateShortcutPicker.toCapturedShortcut(result)
        assertNotNull("capture must succeed", captured)
        assertEquals("com.example.device", captured!!.fallbackPackage)

        // Persist + reload via the real DataStore.
        val config = ConfigStore.DEFAULT.copy(actuator = captured)
        store.save(config)
        val reloaded = store.load()
        assertEquals(config, reloaded)

        // Rebuild the intent and assert lossless preservation of every field.
        val restored = reloaded!!.actuator as LaunchTarget.CapturedShortcut
        val rebuilt = LaunchTargetResolver.toIntent(restored)
        assertEquals(inner.action, rebuilt.action)
        assertEquals(inner.dataString, rebuilt.dataString)
        assertEquals(inner.component, rebuilt.component)
        assertEquals("myscheme://pinned_shortcut", rebuilt.getStringExtra("url"))
        assertEquals("abc123def456", rebuilt.getStringExtra("dev_id"))
        assertEquals(1, rebuilt.getIntExtra("type", -1))
        assertEquals(205535327L, rebuilt.getLongExtra("home_id", -1L))
    }
}
