package com.vnote.appilot.service

import android.content.Context
import com.vnote.appilot.core.model.ReadRegion
import com.vnote.appilot.read.digits.TemplateMatcher
import com.vnote.appilot.read.projection.ScreenCapture

/**
 * Reads the current temperature, returning `null` when it cannot be read this
 * cycle so the orchestrator aborts gracefully (no tap on a bad read).
 */
fun interface TemperatureReader {
    /** Read the temperature now; `null` on capture/parse failure. BLOCKS — off main thread. */
    fun read(): Double?
}

/**
 * Production temperature reader exercising the app's distinctive read pipeline
 * end-to-end: MediaProjection single frame -> crop the calibrated [ReadRegion]
 * -> fixed-font template digit match (no AI / OCR).
 *
 * Faithful to the chosen read-strategy: the harness draws the temperature by
 * compositing the SAME glyph PNGs the [matcher] is built from, so the captured
 * crop cross-correlates to a deterministic value. Consent is obtained once per
 * session and reused (the Android 14+ single-use token contract lives in the
 * frozen read layer); [read] fast-paths when consent is already cached.
 *
 * BLOCKS on capture; the cycle runs it off the main thread.
 */
class ProjectionTemperatureReader(
    private val context: Context,
    private val region: ReadRegion,
    private val matcher: TemplateMatcher,
    private val consentTimeoutMs: Long = ScreenCapture.DEFAULT_CONSENT_TIMEOUT_MS,
    private val captureTimeoutMs: Long = ScreenCapture.DEFAULT_CAPTURE_TIMEOUT_MS,
) : TemperatureReader {

    override fun read(): Double? {
        if (!ScreenCapture.requestConsent(context, consentTimeoutMs)) return null
        val bitmap = runCatching { ScreenCapture.captureFrame(context, captureTimeoutMs) }
            .getOrNull() ?: return null
        return try {
            matcher.match(bitmap.toGrayImage(), region.rect)?.value
        } finally {
            bitmap.recycle()
        }
    }
}
