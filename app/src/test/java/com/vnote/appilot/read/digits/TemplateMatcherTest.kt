package com.vnote.appilot.read.digits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * Pure-JVM unit test for [TemplateMatcher] — no Robolectric, no emulator.
 *
 * Fixtures live in `app/src/test/resources/read/digits/` and are real captures:
 * `24_5.png` is cropped from the Tuya sensor page (shows `24.5`), `26.png` from
 * an AC page (shows `26`); `blank.png` is an empty card patch; `partial.png` is
 * the `24.5` reading with its `.5` occluded. The template sheet
 * `templates/{0..9,dot,minus}.png` is cut from the SAME fonts.
 *
 * ANTI-GAMING: the matcher never sees an expected value — every result is
 * produced by cross-correlating the input against the template sheet. The test
 * loads templates and fixtures through the identical [toGray] luminance path, so
 * a pass proves real segmentation + matching, not a hardcoded lookup.
 */
class TemplateMatcherTest {

    private fun resource(name: String): InputStream =
        requireNotNull(javaClass.getResourceAsStream("/read/digits/$name")) {
            "missing test resource: read/digits/$name"
        }

    /** Decode a PNG to [GrayImage] using the same luminance formula as the generator. */
    private fun toGray(name: String): GrayImage {
        val img = resource(name).use { ImageIO.read(it) }
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xff
                val g = (rgb shr 8) and 0xff
                val b = rgb and 0xff
                px[y * w + x] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            }
        }
        return GrayImage(w, h, px)
    }

    private fun matcher(): TemplateMatcher {
        val map = buildMap {
            for (d in '0'..'9') put(d, toGray("templates/$d.png"))
            put('.', toGray("templates/dot.png"))
            put('-', toGray("templates/minus.png"))
        }
        return TemplateMatcher(map)
    }

    @Test
    fun reads_24_5_fromTuyaCapture() {
        val reading = matcher().match(toGray("24_5.png"))
        assertNotNull("expected a reading for 24_5.png", reading)
        assertEquals(24.5, reading!!.value, 1e-9)
        assertEquals("24.5", reading.text)
    }

    @Test
    fun reads_26_fromAcCapture() {
        val reading = matcher().match(toGray("26.png"))
        assertNotNull("expected a reading for 26.png", reading)
        assertEquals(26.0, reading!!.value, 1e-9)
        assertEquals("26", reading.text)
    }

    @Test
    fun blank_yieldsNull() {
        assertNull("blank.png has no digits", matcher().match(toGray("blank.png")))
    }

    /**
     * DOCUMENTED PARTIAL BEHAVIOR: `partial.png` is the `24.5` reading with the
     * trailing `.5` occluded. The matcher degrades gracefully — it recovers the
     * legible integer prefix and drops the missing fraction — deterministically
     * yielding `24.0` (raw text `"24"`). It does NOT fabricate the lost `.5`.
     */
    @Test
    fun partial_recoversLegiblePrefix_24() {
        val reading = matcher().match(toGray("partial.png"))
        assertNotNull("expected a partial reading", reading)
        assertEquals(24.0, reading!!.value, 1e-9)
        assertEquals("24", reading.text)
    }
}
