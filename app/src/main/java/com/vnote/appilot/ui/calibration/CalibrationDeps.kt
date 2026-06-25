package com.vnote.appilot.ui.calibration

import android.content.Context
import android.graphics.Bitmap
import com.vnote.appilot.read.projection.ScreenCapture

/**
 * Test seam for the live screenshot step.
 *
 * The real path goes through [ScreenCapture] (MediaProjection consent + a single
 * frame grab) which needs a system dialog and is non-deterministic on a device,
 * so it cannot run inside a hermetic Compose UI test. The instrumented
 * `CalibrationFlowTest` sets [screenshotOverride] to a fixture [Bitmap] before
 * driving the flow; production leaves it `null` and uses the real capture.
 *
 * [capture] BLOCKS (consent + frame wait) and MUST be invoked off the main
 * thread — the view model wraps it in `Dispatchers.IO`.
 */
object CalibrationDeps {

    /** When non-null, replaces the real capture (set by tests, cleared after). */
    @Volatile
    var screenshotOverride: ((Context) -> Bitmap?)? = null

    /** Grab one frame: fixture when overridden, else MediaProjection. */
    fun capture(context: Context): Bitmap? {
        screenshotOverride?.let { return it(context) }
        if (!ScreenCapture.requestConsent(context)) return null
        return ScreenCapture.captureFrame(context)
    }
}
