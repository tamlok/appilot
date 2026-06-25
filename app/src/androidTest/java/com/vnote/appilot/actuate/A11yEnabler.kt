package com.vnote.appilot.actuate

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.vnote.appilot.actuate.harness.TapHarnessActivity

/**
 * Shared instrumented-test support for the actuate layer: enables
 * [RegulatorAccessibilityService] past the Android 15 restricted-settings gate
 * and binds it into the already-running test process, then launches the harness.
 *
 * Two platform gotchas it encapsulates (both empirically required on Android 15):
 *  - the instrumentation [UiAutomation] suppresses ALL other accessibility
 *    services unless taken with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, and
 *    every shell call must route through that same flagged instance (the no-arg
 *    getUiAutomation() would flip suppression back on);
 *  - a sideloaded app's enabled_accessibility_services write is silently dropped
 *    until ACCESS_RESTRICTED_SETTINGS is allowed, and binding into a LIVE process
 *    needs a clean accessibility_enabled 0 -> 1 transition, sometimes re-toggled.
 */
class A11yEnabler(instrumentation: Instrumentation) {

    private val context = instrumentation.targetContext
    private val uiAutomation: UiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val component =
        "${context.packageName}/${RegulatorAccessibilityService::class.java.name}"

    fun prepareDevice() {
        shell("svc power stayon true")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow")
    }

    fun teardown() {
        shell("settings put secure accessibility_enabled 0")
        shell("settings delete secure enabled_accessibility_services")
        shell("appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS default")
        shell("input keyevent KEYCODE_HOME")
        shell("svc power stayon false")
    }

    fun forceConnect(timeoutMs: Long): RegulatorAccessibilityService? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            shell("settings put secure accessibility_enabled 0")
            SystemClock.sleep(300L)
            if (!writeEnabledServicesSticks()) continue
            shell("settings put secure accessibility_enabled 1")
            val roundEnd = SystemClock.uptimeMillis() + RETOGGLE_INTERVAL_MS
            while (SystemClock.uptimeMillis() < roundEnd) {
                RegulatorAccessibilityService.instance?.let { return it }
                SystemClock.sleep(200L)
            }
        }
        return RegulatorAccessibilityService.instance
    }

    private fun writeEnabledServicesSticks(): Boolean {
        repeat(WRITE_RETRIES) {
            shell("settings put secure enabled_accessibility_services $component")
            if (shell("settings get secure enabled_accessibility_services").trim() == component) {
                return true
            }
            SystemClock.sleep(300L)
        }
        return false
    }

    /** Launch the harness and wait until its window actually OWNS input focus. */
    fun launchHarness(timeoutMs: Long) {
        TapHarnessActivity.resumed = false
        val intent = Intent(context, TapHarnessActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (TapHarnessActivity.resumed && harnessHasInputFocus()) break
            SystemClock.sleep(150L)
        }
        SystemClock.sleep(500L)
    }

    fun enabledServices(): String =
        shell("settings get secure enabled_accessibility_services").trim()

    private fun harnessHasInputFocus(): Boolean =
        shell("dumpsys window").lineSequence()
            .filter { it.contains("mCurrentFocus") }
            .any { it.contains("TapHarnessActivity") }

    fun shell(cmd: String): String {
        val pfd = uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            return stream.readBytes().toString(Charsets.UTF_8)
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val HARNESS_TIMEOUT_MS = 5_000L
        private const val RETOGGLE_INTERVAL_MS = 5_000L
        private const val WRITE_RETRIES = 8
    }
}
