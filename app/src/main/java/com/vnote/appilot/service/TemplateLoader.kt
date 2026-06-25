package com.vnote.appilot.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vnote.appilot.core.store.TemplateStore
import com.vnote.appilot.read.digits.GrayImage

/**
 * Decodes persisted digit-template PNG blobs (held byte-identical by
 * [TemplateStore]) into the `Map<Char, GrayImage>` the read layer's
 * `TemplateMatcher` consumes.
 *
 * This is the Android-side adapter the read layer intentionally left out (its
 * `GrayImage` is pure-JVM). Production loads the user's calibrated glyphs; the
 * instrumented cycle test seeds the SAME store from the bundled debug glyph
 * assets, so the matcher reads the harness's drawn digits against the exact
 * templates that produced them — a deterministic match by construction.
 *
 * Storage keys map to glyph chars: `"0".."9"` verbatim, `"dot" -> '.'`,
 * `"minus" -> '-'`.
 */
object TemplateLoader {

    /** Decode every template in [store] into a char-keyed [GrayImage] map. */
    fun load(store: TemplateStore): Map<Char, GrayImage> {
        val out = HashMap<Char, GrayImage>()
        for (key in store.templateKeys()) {
            val char = charForKey(key) ?: continue
            val bytes = store.loadTemplate(key) ?: continue
            out[char] = decodeGray(bytes) ?: continue
        }
        return out
    }

    private fun charForKey(key: String): Char? = when (key) {
        "dot" -> '.'
        "minus" -> '-'
        else -> key.singleOrNull()?.takeIf { it in '0'..'9' }
    }

    /** Decode PNG [bytes] into a luminance [GrayImage]. */
    fun decodeGray(bytes: ByteArray): GrayImage? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            bitmap.toGrayImage()
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * Convert an [Bitmap] to a pure-JVM [GrayImage] of Rec.601 luma in `0..255`.
 * Shared by [TemplateLoader] and the projection read path.
 */
fun Bitmap.toGrayImage(): GrayImage {
    val w = width
    val h = height
    val argb = IntArray(w * h)
    getPixels(argb, 0, w, 0, 0, w, h)
    val lum = IntArray(w * h)
    for (i in argb.indices) {
        val p = argb[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        lum[i] = (r * 77 + g * 150 + b * 29) shr 8
    }
    return GrayImage(w, h, lum)
}
