package com.vnote.appilot.ui.calibration

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import com.vnote.appilot.core.model.Ratio
import com.vnote.appilot.core.model.RatioRect
import kotlin.math.max
import kotlin.math.min

/**
 * A drag-to-draw rectangle marker over a captured [bitmap]. The image is letter-
 * boxed to fit the available space so the whole surface is on-screen; a drag
 * produces a [RatioRect] by dividing the in-element pixel offsets by the
 * element's measured size — resolution/orientation independent by construction.
 * Already-marked [rects] are stroked for reference.
 */
@Composable
fun DragRectOverlay(
    bitmap: Bitmap,
    rects: List<RatioRect>,
    onRectDrawn: (RatioRect) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "dragOverlay",
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val (wPx, hPx) = if (maxW / maxH > ratio) maxH * ratio to maxH else maxW to maxW / ratio
        val density = LocalDensity.current
        val wDp = with(density) { wPx.toDp() }
        val hDp = with(density) { hPx.toDp() }

        var sizePx by remember { mutableStateOf(IntSize.Zero) }
        var start by remember { mutableStateOf<Offset?>(null) }
        var current by remember { mutableStateOf<Offset?>(null) }

        Image(
            bitmap = image,
            contentDescription = "calibration screenshot",
            modifier = Modifier
                .size(wDp, hDp)
                .onSizeChanged { sizePx = it }
                .testTag(testTag)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start = it; current = it },
                        onDrag = { change, _ -> change.consume(); current = change.position },
                        onDragEnd = {
                            val s = start
                            val c = current
                            if (s != null && c != null && sizePx.width > 0 && sizePx.height > 0) {
                                onRectDrawn(toRatioRect(s, c, sizePx))
                            }
                            start = null; current = null
                        },
                    )
                },
        )
        Canvas(modifier = Modifier.size(wDp, hDp)) {
            rects.forEach { r ->
                drawRect(
                    color = Color(0xFF00E5FF),
                    topLeft = Offset(r.left.value.toFloat() * size.width, r.top.value.toFloat() * size.height),
                    size = Size(
                        (r.right.value - r.left.value).toFloat() * size.width,
                        (r.bottom.value - r.top.value).toFloat() * size.height,
                    ),
                    style = Stroke(width = 5f),
                )
            }
            val s = start
            val c = current
            if (s != null && c != null) {
                drawRect(
                    color = Color(0xFFFFEB3B),
                    topLeft = Offset(min(s.x, c.x), min(s.y, c.y)),
                    size = Size(kotlin.math.abs(c.x - s.x), kotlin.math.abs(c.y - s.y)),
                    style = Stroke(width = 5f),
                )
            }
        }
    }
}

/** Convert two drag endpoints (element pixels) into a normalized [RatioRect]. */
fun toRatioRect(a: Offset, b: Offset, sizePx: IntSize): RatioRect {
    fun clamp(value: Float, span: Int): Double = (value / span).coerceIn(0f, 1f).toDouble()
    val left = clamp(min(a.x, b.x), sizePx.width)
    val right = clamp(max(a.x, b.x), sizePx.width)
    val top = clamp(min(a.y, b.y), sizePx.height)
    val bottom = clamp(max(a.y, b.y), sizePx.height)
    return RatioRect(Ratio(left), Ratio(top), Ratio(right), Ratio(bottom))
}
