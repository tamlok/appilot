package com.vnote.appilot.actuate

import com.vnote.appilot.core.model.RatioRect

/**
 * The single seam the actuate layer taps through.
 *
 * Implemented on-device by [RegulatorAccessibilityService] (coordinate-only
 * `dispatchGesture`) and by fakes in unit tests, so [Actuator]'s
 * action -> tap-sequence mapping stays PURE / JVM-testable (this interface
 * references only [RatioRect], never `android.*`).
 */
interface Tapper {

    /**
     * Tap the device-resolved pixel center of [target].
     *
     * The implementation resolves [target] against the REAL display size and
     * orientation at tap time (resolution/orientation independent). [onDone] is
     * invoked once with `true` when the gesture completes, or `false` if it was
     * cancelled or could not be dispatched.
     */
    fun tap(target: RatioRect, onDone: (Boolean) -> Unit)
}
