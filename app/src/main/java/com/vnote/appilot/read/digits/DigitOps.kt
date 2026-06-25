package com.vnote.appilot.read.digits

import com.vnote.appilot.core.model.RatioRect

/**
 * Low-level, deterministic pixel operations shared by [TemplateMatcher] and its
 * fixture generator: luminance thresholding, column-projection segmentation,
 * canonical coverage resampling and normalized cross-correlation. No `android.*`.
 */

/** A segmented glyph bounding box; `x1`/`y1` are exclusive. */
internal data class Slot(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0
    val centerY: Double get() = (y0 + y1) / 2.0
}

/**
 * Threshold to a boolean ink mask. Background is estimated from the border;
 * polarity (dark-on-light vs light-on-dark) is auto-detected so the same code
 * reads dark digits on a light background and light digits on a dark background.
 */
internal fun binarize(img: GrayImage): BooleanArray {
    val w = img.width
    val h = img.height
    val l = img.pixels
    var sum = 0L
    for (v in l) sum += v
    val mean = (sum / l.size).toInt()
    var bsum = 0L
    var bcnt = 0
    for (x in 0 until w) {
        bsum += (l[x] + l[(h - 1) * w + x]).toLong()
        bcnt += 2
    }
    for (y in 0 until h) {
        bsum += (l[y * w] + l[y * w + w - 1]).toLong()
        bcnt += 2
    }
    val border = (bsum / bcnt).toInt()
    val darkInk = border >= mean
    val thr = (border + mean) / 2
    return BooleanArray(l.size) { if (darkInk) l[it] < thr else l[it] > thr }
}

/**
 * Split the ink mask into left-to-right glyph slots using a column projection:
 * runs of ink-bearing columns separated by empty columns. Tiny specks (anti-alias
 * noise, the trailing `℃` sliver) are dropped via a [minInk] area floor.
 */
internal fun segment(img: GrayImage, ink: BooleanArray): List<Slot> {
    val w = img.width
    val h = img.height
    val col = IntArray(w)
    for (x in 0 until w) {
        var c = 0
        for (y in 0 until h) if (ink[y * w + x]) c++
        col[x] = c
    }
    val minInk = maxOf(4, (w * h) / 800)
    val slots = ArrayList<Slot>()
    var x = 0
    while (x < w) {
        while (x < w && col[x] == 0) x++
        if (x >= w) break
        val x0 = x
        while (x < w && col[x] > 0) x++
        val x1 = x
        var y0 = h
        var y1 = -1
        var area = 0
        for (yy in 0 until h) {
            for (xx in x0 until x1) {
                if (ink[yy * w + xx]) {
                    area++
                    if (yy < y0) y0 = yy
                    if (yy > y1) y1 = yy
                }
            }
        }
        if (area >= minInk && (x1 - x0) >= 2) slots.add(Slot(x0, y0, x1, y1 + 1))
    }
    return slots
}

/**
 * Resample a slot's ink to a fixed [cw]×[ch] coverage grid in `0.0..1.0` (the
 * fraction of ink under each canonical cell). Aspect is intentionally stretched
 * to fill the grid so a glyph and its template — cut from the same font — yield
 * the same vector regardless of capture scale.
 */
internal fun coverage(img: GrayImage, ink: BooleanArray, s: Slot, cw: Int, ch: Int): DoubleArray {
    val w = img.width
    val out = DoubleArray(cw * ch)
    for (cy in 0 until ch) {
        val sy0 = s.y0 + cy * s.height / ch
        val sy1 = maxOf(sy0 + 1, s.y0 + (cy + 1) * s.height / ch)
        for (cx in 0 until cw) {
            val sx0 = s.x0 + cx * s.width / cw
            val sx1 = maxOf(sx0 + 1, s.x0 + (cx + 1) * s.width / cw)
            var on = 0
            var tot = 0
            for (yy in sy0 until sy1) {
                for (xx in sx0 until sx1) {
                    tot++
                    if (ink[yy * w + xx]) on++
                }
            }
            out[cy * cw + cx] = if (tot == 0) 0.0 else on.toDouble() / tot
        }
    }
    return out
}

/** Convert a whole [GrayImage] template into its canonical coverage vector. */
internal fun templateCoverage(img: GrayImage, cw: Int, ch: Int): DoubleArray {
    val ink = binarize(img)
    val slot = segment(img, ink).maxByOrNull { it.width * it.height }
        ?: Slot(0, 0, img.width, img.height)
    return coverage(img, ink, slot, cw, ch)
}

/** Zero-mean normalized cross-correlation; `1.0` for identical vectors. */
internal fun ncc(a: DoubleArray, b: DoubleArray): Double {
    val n = a.size
    var ma = 0.0
    var mb = 0.0
    for (i in 0 until n) {
        ma += a[i]
        mb += b[i]
    }
    ma /= n
    mb /= n
    var num = 0.0
    var da = 0.0
    var db = 0.0
    for (i in 0 until n) {
        val xa = a[i] - ma
        val xb = b[i] - mb
        num += xa * xb
        da += xa * xa
        db += xb * xb
    }
    val den = Math.sqrt(da * db)
    return if (den == 0.0) 0.0 else num / den
}

/** Crop a [RatioRect] out of [img] into a new [GrayImage]; reuses the Wave 1 model. */
internal fun cropRegion(img: GrayImage, region: RatioRect): GrayImage {
    val x0 = (region.left.value * img.width).toInt().coerceIn(0, img.width - 1)
    val x1 = (region.right.value * img.width).toInt().coerceIn(x0 + 1, img.width)
    val y0 = (region.top.value * img.height).toInt().coerceIn(0, img.height - 1)
    val y1 = (region.bottom.value * img.height).toInt().coerceIn(y0 + 1, img.height)
    val nw = x1 - x0
    val nh = y1 - y0
    val px = IntArray(nw * nh)
    for (y in 0 until nh) {
        for (x in 0 until nw) {
            px[y * nw + x] = img.pixels[(y0 + y) * img.width + (x0 + x)]
        }
    }
    return GrayImage(nw, nh, px)
}
