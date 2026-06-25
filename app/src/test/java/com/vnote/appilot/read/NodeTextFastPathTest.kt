package com.vnote.appilot.read

import com.vnote.appilot.core.model.Ratio
import com.vnote.appilot.core.model.RatioRect
import com.vnote.appilot.core.model.ReadRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [NodeTextFastPath]: present-numeric -> [ReadResult.Value],
 * empty / non-numeric / non-overlapping -> [ReadResult.FallbackToTemplate].
 */
class NodeTextFastPathTest {
    /** Region covering the centre quarter of the screen. */
    private val region = ReadRegion(
        RatioRect(Ratio(0.25), Ratio(0.25), Ratio(0.75), Ratio(0.75)),
    )

    private fun rect(l: Double, t: Double, r: Double, b: Double) =
        RatioRect(Ratio(l), Ratio(t), Ratio(r), Ratio(b))

    private fun node(text: String?, bounds: RatioRect) = TestNode(text, bounds)

    private data class TestNode(
        override val text: String?,
        override val bounds: RatioRect,
    ) : UiNode

    private val fastPath = NodeTextFastPath()

    @Test
    fun numericOverlappingNode_returnsParsedValue() {
        val nodes = listOf(node("24.5", rect(0.4, 0.4, 0.6, 0.6)))
        val result = fastPath.read(region, nodes)
        assertEquals(ReadResult.Value(24.5), result)
    }

    @Test
    fun integerNode_returnsValueAsDouble() {
        val nodes = listOf(node("26", rect(0.3, 0.3, 0.5, 0.5)))
        assertEquals(ReadResult.Value(26.0), fastPath.read(region, nodes))
    }

    @Test
    fun signedDecimalNode_returnsNegativeValue() {
        val nodes = listOf(node("-3.0", rect(0.45, 0.45, 0.55, 0.55)))
        assertEquals(ReadResult.Value(-3.0), fastPath.read(region, nodes))
    }

    @Test
    fun emptyNodeTree_fallsBack() {
        assertEquals(ReadResult.FallbackToTemplate, fastPath.read(region, emptyList()))
    }

    @Test
    fun nonNumericOverlappingNode_fallsBack() {
        val nodes = listOf(node("Living Room", rect(0.4, 0.4, 0.6, 0.6)))
        assertEquals(ReadResult.FallbackToTemplate, fastPath.read(region, nodes))
    }

    @Test
    fun junkSignNode_fallsBack() {
        val nodes = listOf(node("--", rect(0.4, 0.4, 0.6, 0.6)))
        assertEquals(ReadResult.FallbackToTemplate, fastPath.read(region, nodes))
    }

    @Test
    fun blankTextNode_fallsBack() {
        val nodes = listOf(node("   ", rect(0.4, 0.4, 0.6, 0.6)))
        assertEquals(ReadResult.FallbackToTemplate, fastPath.read(region, nodes))
    }

    @Test
    fun nullTextNode_fallsBack() {
        val nodes = listOf(node(null, rect(0.4, 0.4, 0.6, 0.6)))
        assertEquals(ReadResult.FallbackToTemplate, fastPath.read(region, nodes))
    }

    @Test
    fun numericNodeOutsideRegion_fallsBack() {
        // Sits in the top-left corner, fully clear of the centre region.
        val nodes = listOf(node("24.5", rect(0.0, 0.0, 0.1, 0.1)))
        assertEquals(ReadResult.FallbackToTemplate, fastPath.read(region, nodes))
    }

    @Test
    fun numericNodeTouchingEdgeOnly_fallsBack() {
        // Shares exactly the left edge (x == 0.25); a zero-area touch is not an overlap.
        val nodes = listOf(node("24.5", rect(0.1, 0.4, 0.25, 0.6)))
        assertEquals(ReadResult.FallbackToTemplate, fastPath.read(region, nodes))
    }

    @Test
    fun picksFirstNumericOverlappingNode_ignoringNonNumeric() {
        val nodes = listOf(
            node("Living Room", rect(0.3, 0.3, 0.7, 0.4)),
            node("24.5", rect(0.4, 0.5, 0.6, 0.6)),
        )
        val result = fastPath.read(region, nodes)
        assertTrue(result is ReadResult.Value)
        assertEquals(24.5, (result as ReadResult.Value).temp, 1e-9)
    }
}
