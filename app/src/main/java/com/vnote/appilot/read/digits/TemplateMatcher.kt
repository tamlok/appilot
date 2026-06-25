package com.vnote.appilot.read.digits

import com.vnote.appilot.core.model.RatioRect

/**
 * Deterministic, AI-free temperature reader.
 *
 * Pipeline (pure pixel math, no OCR engine, no ML):
 * 1. [binarize] the cropped region to an ink mask (auto polarity).
 * 2. [segment] it into left-to-right glyph slots via a column projection.
 * 3. Classify each slot: punctuation (`.`/`-`) by geometry + template confirm,
 *    or a digit by best [ncc] against the stored `0`-`9` templates.
 * 4. [parseReading] the glyph sequence into a signed decimal [Reading].
 *
 * The matcher holds NO knowledge of any expected value: every result emerges
 * from cross-correlating the input against the template sheet, so the same code
 * reads any fixed-font numeric display.
 *
 * @param templates glyph image per char: `'0'..'9'`, `'.'`, `'-'`.
 */
class TemplateMatcher(
    templates: Map<Char, GrayImage>,
    private val config: MatchConfig = MatchConfig(),
) {
    private val cw = config.canonicalWidth
    private val ch = config.canonicalHeight
    private val digits: List<Pair<Char, DoubleArray>> = ('0'..'9').mapNotNull { d ->
        templates[d]?.let { d to templateCoverage(it, cw, ch) }
    }
    private val dot: DoubleArray? = templates['.']?.let { templateCoverage(it, cw, ch) }
    private val minus: DoubleArray? = templates['-']?.let { templateCoverage(it, cw, ch) }

    /** Crop [region] out of [image] (Wave 1 [RatioRect]) then [match]. */
    fun match(image: GrayImage, region: RatioRect): Reading? =
        match(cropRegion(image, region))

    /** Read the digits already cropped into [region]. Returns `null` if blank. */
    fun match(region: GrayImage): Reading? {
        val ink = binarize(region)
        val slots = segment(region, ink)
        if (slots.isEmpty()) return null

        val maxH = slots.maxOf { it.height }
        val digitSlots = slots.filter { it.height > config.smallHeightRatio * maxH }
        val baseSlots = digitSlots.ifEmpty { slots }
        val baseline = baseSlots.maxOf { it.y1 }
        val capTop = baseSlots.minOf { it.y0 }
        val midY = (capTop + baseline) / 2.0

        val glyphs = ArrayList<Char?>(slots.size)
        val scores = ArrayList<Double>(slots.size)
        for (s in slots) {
            val cov = coverage(region, ink, s, cw, ch)
            if (s.height <= config.smallHeightRatio * maxH) {
                classifyPunctuation(cov, s, midY)?.let { (c, sc) ->
                    glyphs.add(c); scores.add(sc)
                } ?: glyphs.add(null)
            } else {
                val best = digits.maxByOrNull { ncc(cov, it.second) }
                val score = best?.let { ncc(cov, it.second) } ?: 0.0
                if (best != null && score >= config.digitThreshold) {
                    glyphs.add(best.first); scores.add(score)
                } else {
                    glyphs.add(null)
                }
            }
        }
        return parseReading(glyphs, scores)
    }

    /** Geometry decides `.` vs `-`; the template confirms it (or rejects as junk). */
    private fun classifyPunctuation(cov: DoubleArray, s: Slot, midY: Double): Pair<Char, Double>? {
        val isDot = s.centerY > midY
        val tmpl = if (isDot) dot else minus
        val score = if (tmpl == null) 0.0 else ncc(cov, tmpl)
        return if (tmpl != null && score >= config.punctThreshold) {
            (if (isDot) '.' else '-') to score
        } else {
            null
        }
    }

    /**
     * Fold recognised glyphs (left to right) into a signed decimal. A `null`
     * glyph or a misplaced token ends parsing, so an occluded/clipped reading
     * degrades gracefully to whatever prefix is legible (e.g. `"24."` -> `24.0`).
     * Returns `null` when no digit survives.
     */
    private fun parseReading(glyphs: List<Char?>, scores: List<Double>): Reading? {
        val sb = StringBuilder()
        var seenDot = false
        var hasDigit = false
        for (g in glyphs) {
            if (g == null) break
            when {
                g == '-' && sb.isEmpty() -> sb.append('-')
                g == '.' && !seenDot && hasDigit -> { sb.append('.'); seenDot = true }
                g in '0'..'9' -> { sb.append(g); hasDigit = true }
                else -> break
            }
        }
        if (!hasDigit) return null
        val text = sb.toString()
        val value = text.toDoubleOrNull() ?: text.trimEnd('.').toDoubleOrNull() ?: return null
        val confidence = scores.minOrNull() ?: 0.0
        return Reading(value, text, confidence)
    }
}
