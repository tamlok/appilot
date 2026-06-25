package com.vnote.appilot.read

import com.vnote.appilot.core.model.RatioRect
import com.vnote.appilot.core.model.ReadRegion

/**
 * Accessibility node-text fast path for the Read layer.
 *
 * Some source screens draw the temperature as a real text node (unlike a
 * WebView-drawn graphic). When the marked [ReadRegion] overlaps such a node and its
 * `text` parses as a number, we can read the value directly — no MediaProjection
 * screenshot and no template matching required. Otherwise we SIGNAL a fallback so
 * the caller runs the (heavier) `TemplateMatcher` pipeline.
 *
 * PURE-JVM by design: this file never touches `android.*`. It works over the
 * [UiNode] abstraction below. A thin `AccessibilityNodeInfo -> UiNode` adapter
 * (reading `getText()` + `getBoundsInScreen()` normalised by display size into a
 * [RatioRect]) is DEFERRED to Wave 6 orchestration and intentionally NOT added
 * here, so the read logic stays unit-testable on the JVM.
 *
 * Wave 6 consumption: the cycle calls [read] first; on [ReadResult.Value] it uses
 * the temperature immediately, on [ReadResult.FallbackToTemplate] it captures a
 * frame and delegates to `read.digits.TemplateMatcher`.
 */
class NodeTextFastPath {
    /**
     * Inspect [nodes] for a numeric node overlapping [region].
     *
     * @return [ReadResult.Value] for the first overlapping node whose text parses
     *   as a number; otherwise [ReadResult.FallbackToTemplate].
     */
    fun read(region: ReadRegion, nodes: List<UiNode>): ReadResult {
        val target = region.rect
        for (node in nodes) {
            if (!overlaps(target, node.bounds)) continue
            val value = parseNumeric(node.text) ?: continue
            return ReadResult.Value(value)
        }
        return ReadResult.FallbackToTemplate
    }

    /** Lambda-provider overload: defers node materialisation to the caller. */
    fun read(region: ReadRegion, nodeProvider: () -> List<UiNode>): ReadResult =
        read(region, nodeProvider())

    private companion object {
        /**
         * Overlap rule: STRICT rectangle intersection (positive overlap area).
         * Two rects overlap when each axis' open intervals intersect, i.e. one's
         * far edge is strictly past the other's near edge on both axes. A
         * shared-edge-only touch (zero-area) does NOT qualify, since the digits
         * are not actually inside the marked region in that case.
         */
        fun overlaps(a: RatioRect, b: RatioRect): Boolean =
            a.left.value < b.right.value &&
                b.left.value < a.right.value &&
                a.top.value < b.bottom.value &&
                b.top.value < a.bottom.value

        /**
         * Numeric grammar: optional leading sign, one or more digits, and an
         * optional single decimal point with a fractional part (e.g. `24.5`,
         * `26`, `-3.0`). Surrounding whitespace is trimmed. Anything else —
         * `"--"`, `"Living Room"`, `""`, `"24.5.6"`, a bare sign — is rejected so
         * the caller falls back to the template matcher.
         */
        private val NUMERIC = Regex("""[+-]?\d+(\.\d+)?""")

        fun parseNumeric(text: String?): Double? {
            val trimmed = text?.trim() ?: return null
            if (!NUMERIC.matches(trimmed)) return null
            return trimmed.toDoubleOrNull()
        }
    }
}

/**
 * Resolution-independent view of an accessibility node, decoupled from
 * `android.view.accessibility.AccessibilityNodeInfo` so the fast path is
 * pure-JVM testable. The Wave 6 adapter normalises on-screen pixel bounds into
 * [bounds].
 */
interface UiNode {
    /** Visible text on the node, or `null`/blank when it carries none. */
    val text: String?

    /** Node bounds as screen ratios. */
    val bounds: RatioRect
}

/**
 * Outcome of the node-text fast path.
 *
 * - [Value] — a numeric node overlapped the region; [temp] is its parsed value.
 * - [FallbackToTemplate] — no usable node; the caller must run the template
 *   matcher (screenshot + digit templates) instead.
 */
sealed interface ReadResult {
    data class Value(val temp: Double) : ReadResult

    data object FallbackToTemplate : ReadResult
}
