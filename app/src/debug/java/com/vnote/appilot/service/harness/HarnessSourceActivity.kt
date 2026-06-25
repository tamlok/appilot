package com.vnote.appilot.service.harness

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import com.vnote.appilot.service.ForegroundRegistry

/**
 * Debug-only QA harness standing in for a vendor temperature screen.
 *
 * It renders the temperature as a DRAWN Canvas graphic — compositing the bundled
 * 0-9 glyph PNGs (the SAME sheet the read layer's `TemplateMatcher` is built
 * from) on a clean white field — so the orchestration cycle exercises the app's
 * distinctive pipeline end to end: MediaProjection capture -> crop the
 * configured `ReadRegion` -> fixed-font template digit match. Because the drawn
 * glyphs ARE the templates, the match is deterministic by construction.
 *
 * The temperature is SETTABLE via the [EXTRA_TEMP] intent extra (default
 * [DEFAULT_TEMP]); the cycle test sets it ABOVE the band so the decision is DOWN.
 *
 * Readiness: it reports its own class name to [ForegroundRegistry] on window
 * focus — the in-process foreground signal the bounded readiness poll waits on
 * before capturing (both harness activities share this app's package, so the
 * activity-class token is what tells `source` from `actuator`).
 *
 * Lives in `src/debug/`, so it never reaches the release surface.
 */
class HarnessSourceActivity : Activity() {

    private val token = HarnessSourceActivity::class.java.name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val temp = intent.getDoubleExtra(EXTRA_TEMP, DEFAULT_TEMP)
        setContentView(DigitView(this, formatTemp(temp)))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ForegroundRegistry.enter(token) else ForegroundRegistry.leave(token)
    }

    override fun onPause() {
        ForegroundRegistry.leave(token)
        super.onPause()
    }

    /** Render the value as the read pipeline will see it: whole -> "30", else "24.5". */
    private fun formatTemp(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format("%.1f", value)

    /**
     * Full-bleed white view that draws [text]'s glyphs (each preserving its PNG
     * aspect ratio, with white gaps between cells so column-projection
     * segmentation cleanly separates them) centered in a horizontal band.
     */
    private class DigitView(activity: Activity, private val text: String) : View(activity) {

        private val glyphs: Map<Char, Bitmap> = loadGlyphs(activity)
        private val srcPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            val w = width.toFloat()
            val h = height.toFloat()
            val cells = text.mapNotNull { glyphs[it] }
            if (cells.isEmpty()) return
            // Size each glyph from its own aspect ratio and lay them out left to
            // right with an explicit white gap, then center the whole group. The
            // gap guarantees the read layer's column-projection segmentation sees
            // separate digit slots (overlapping glyphs would merge into one blob).
            val glyphH = h * 0.14f
            val gap = glyphH * 0.45f
            val widths = cells.map { glyphH * it.width / it.height }
            val totalW = widths.sum() + gap * (cells.size - 1)
            var x = (w - totalW) / 2f
            val top = h * 0.50f - glyphH / 2f
            cells.forEachIndexed { i, bmp ->
                val gw = widths[i]
                val dst = RectF(x, top, x + gw, top + glyphH)
                canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), dst, srcPaint)
                x += gw + gap
            }
        }

        private companion object {
            fun loadGlyphs(activity: Activity): Map<Char, Bitmap> {
                val keys = ('0'..'9').associateWith { it.toString() } + mapOf('.' to "dot", '-' to "minus")
                val out = HashMap<Char, Bitmap>()
                for ((char, key) in keys) {
                    runCatching {
                        activity.assets.open("$ASSET_DIR/$key.png").use {
                            out[char] = BitmapFactory.decodeStream(it)
                        }
                    }
                }
                return out
            }

            const val ASSET_DIR = "harness_digits"
        }
    }

    companion object {
        const val EXTRA_TEMP = "harness_temp"
        const val DEFAULT_TEMP = 30.0
    }
}
