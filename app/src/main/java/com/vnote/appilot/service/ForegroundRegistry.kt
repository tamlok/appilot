package com.vnote.appilot.service

/**
 * Process-scoped, in-process foreground-window signal for the orchestration
 * readiness gate.
 *
 * The cycle ([CycleOrchestrator]) must NOT screenshot or tap until the screen it
 * just launched is actually the foreground/top window. Rather than blindly
 * sleeping, it polls a readiness predicate that consults THIS registry for the
 * currently-foreground component token (an activity class name on this image).
 *
 * Who writes [foreground]:
 *  - the in-repo QA harness activities report their own class name on
 *    `onWindowFocusChanged(true)` / clear it on blur — the deterministic signal
 *    the instrumented [com.vnote.appilot.service] cycle test relies on (both
 *    harness activities share THIS app's package, so a package-level signal
 *    cannot tell `source` from `actuator`; the activity-class token can);
 *  - in production the same registry is fed from the
 *    [com.vnote.appilot.actuate.RegulatorAccessibilityService]
 *    `TYPE_WINDOW_STATE_CHANGED` event's `className` (the real-app foreground
 *    signal). That a11y bridge is the F1 on-device wiring point and is
 *    intentionally NOT added to the frozen actuate service here.
 *
 * Token semantics: [matches] accepts either an exact class-name match or a
 * suffix/contains match, so a stored fully-qualified class still matches a
 * simple class-name token and vice-versa.
 */
object ForegroundRegistry {

    /** The class name of the activity that currently owns window focus, or null. */
    @Volatile
    var foreground: String? = null
        private set

    /** Record [token] as the foreground component (called when it gains focus). */
    fun enter(token: String) {
        foreground = token
    }

    /** Clear [token] if it is still the recorded foreground (called on blur). */
    fun leave(token: String) {
        if (foreground == token) foreground = null
    }

    /** Reset the signal; used by tests between cycles. */
    fun reset() {
        foreground = null
    }

    /**
     * True when [expected] identifies the current foreground component. A blank
     * [expected] never matches (an unknown target can't gate readiness).
     */
    fun matches(expected: String?): Boolean {
        if (expected.isNullOrBlank()) return false
        val current = foreground ?: return false
        return current == expected ||
            current.endsWith(expected) ||
            expected.endsWith(current) ||
            current.contains(expected)
    }
}
