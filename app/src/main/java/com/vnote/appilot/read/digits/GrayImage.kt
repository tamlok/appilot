package com.vnote.appilot.read.digits

/**
 * A tiny, resolution-independent grayscale image: per-pixel luminance in
 * `0..255`, stored row-major (`pixels[y * width + x]`).
 *
 * This is the matcher's ONLY input abstraction. It deliberately pulls in no
 * `android.*` types so the read logic is a pure JVM function unit-testable with
 * plain JUnit (no Robolectric, no emulator). A thin `Bitmap -> GrayImage`
 * adapter belongs in the Android layer, not here.
 */
data class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "width/height must be positive" }
        require(pixels.size == width * height) {
            "pixels.size (${pixels.size}) must equal width*height (${width * height})"
        }
    }

    fun luminance(x: Int, y: Int): Int = pixels[y * width + x]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GrayImage) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int =
        31 * (31 * width + height) + pixels.contentHashCode()
}

/**
 * The decoded reading. [value] is the parsed decimal (e.g. `24.5`), [text] the
 * raw recognised glyph string (e.g. `"24.5"`), and [confidence] the lowest
 * per-glyph match score in `0.0..1.0` — useful for callers that want to reject
 * weak reads.
 */
data class Reading(val value: Double, val text: String, val confidence: Double)

/**
 * Tunables for [TemplateMatcher]. Defaults are calibrated against the bundled
 * fixtures; callers normally keep them.
 */
data class MatchConfig(
    val canonicalWidth: Int = 16,
    val canonicalHeight: Int = 24,
    /** Min normalized-cross-correlation for a digit slot to be accepted. */
    val digitThreshold: Double = 0.45,
    /** Min NCC for a punctuation slot (`.`/`-`) to be confirmed. */
    val punctThreshold: Double = 0.25,
    /** A slot shorter than this fraction of the tallest slot is punctuation. */
    val smallHeightRatio: Double = 0.55,
)
