package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shading now decides what two renderers draw, so the numbers are pinned
 * here the same way `EngravedStrokeTest` pins the stroke passes.
 *
 * These were extracted from the workbench renderer on the promise that nothing
 * changed, and every sheen/vignette combination rendered byte-identically
 * afterwards. The promise only survives if a test fails when someone improves
 * the arithmetic.
 */
class DialShadingTest {

    @Test
    fun `sheen is absent rather than transparent when switched off`() {
        // A renderer that draws a fully transparent gradient is doing work for
        // nothing on every frame; null lets it skip the pass entirely.
        assertNull(DialShading.sheen(DialParams(sheen = 0.0)))
        assertNull(DialShading.vignette(DialParams(vignette = 0.0)))
    }

    @Test
    fun `sheen runs upper-left to lower-right`() {
        val s = DialShading.sheen(DialParams(sheen = 30.0))!!
        assertTrue(s.fromX < s.toX && s.fromY < s.toY) {
            "the light should come from one corner, got (${s.fromX},${s.fromY})->(${s.toX},${s.toY})"
        }
        assertEquals(3, s.stops.size)
        assertEquals(listOf(0.0f, 0.5f, 1.0f), s.stops.map { it.at })
    }

    @Test
    fun `sheen goes light, clear, dark`() {
        val s = DialShading.sheen(DialParams(sheen = 60.0, dialColor = "#7D7369"))!!
        val dial = EngravedStroke.rgb("#7D7369")
        fun luma(c: Int) = ((c shr 16) and 0xFF) * 0.299 + ((c shr 8) and 0xFF) * 0.587 + (c and 0xFF) * 0.114

        assertTrue(luma(s.stops[0].argb and 0xFFFFFF) > luma(dial)) { "the first stop is not a highlight" }
        assertEquals(0, s.stops[1].argb ushr 24) { "the middle stop must be fully transparent" }
        assertTrue(luma(s.stops[2].argb and 0xFFFFFF) < luma(dial)) { "the last stop is not a shadow" }
    }

    @Test
    fun `the clear middle stop carries the dial colour, not black`() {
        // Blending toward transparent BLACK dirties the midtones on a light
        // dial. The RGB has to be the dial's own even at zero alpha.
        val s = DialShading.sheen(DialParams(sheen = 50.0, dialColor = "#C9C3B6"))!!
        assertEquals(EngravedStroke.rgb("#C9C3B6"), s.stops[1].argb and 0xFFFFFF)
    }

    @Test
    fun `the vignette stays clear in the middle and only turns down near the rim`() {
        val v = DialShading.vignette(DialParams(vignette = 100.0))!!
        assertEquals(0, v.stops[0].argb ushr 24) { "the centre must be untouched" }
        val mid = v.stops[1].argb ushr 24
        val edge = v.stops[2].argb ushr 24
        assertTrue(mid < edge / 4) {
            "the midpoint ($mid) is too dark against the rim ($edge); a linear fade " +
                "reads as a grey wash over the whole face"
        }
        assertEquals(DIAL_RADIUS.toDouble(), v.radius)
        assertEquals(DIAL_CENTER, v.centerX)
    }

    @Test
    fun `both scale with their slider and nothing else`() {
        val low = DialShading.sheen(DialParams(sheen = 10.0))!!
        val high = DialShading.sheen(DialParams(sheen = 90.0))!!
        for (i in low.stops.indices) {
            assertTrue((high.stops[i].argb ushr 24) >= (low.stops[i].argb ushr 24)) {
                "sheen stop $i did not get stronger"
            }
            assertEquals(low.stops[i].argb and 0xFFFFFF, high.stops[i].argb and 0xFFFFFF) {
                "sheen changed the colour of stop $i, not just its alpha"
            }
        }
    }

    /**
     * The regression guard, matching `EngravedStrokeTest`'s.
     *
     * Produced by the workbench renderer before the extraction; every sheen and
     * vignette combination rendered byte-identically afterwards. A failure here
     * means the dial's appearance changed, which is a `generatorVersion` matter.
     */
    @Test
    fun `the default face's shading is exactly what the workbench always drew`() {
        val p = DialParams(dialColor = "#7D7369", sheen = 30.0, vignette = 18.0)
        val sheen = DialShading.sheen(p)!!
        val vignette = DialShading.vignette(p)!!
        assertEquals(
            // Derived independently from the renderer's arithmetic, then
            // confirmed against what sheen() produces.
            listOf("1bdedcd9", "007d7369", "1538332f"),
            sheen.stops.map { "%08x".format(it.argb) }
        ) { "the sheen colours changed; every saved face is affected" }
        assertEquals(
            listOf("00000000", "07000000", "2a000000"),
            vignette.stops.map { "%08x".format(it.argb) }
        ) { "the vignette colours changed; every saved face is affected" }
    }

    @Test
    fun `a sheen at full strength is still a sheen, not a wash`() {
        // The alphas are deliberately modest: this is a lighting hint on a disc,
        // not a gradient overlay. Above roughly a third opaque it stops reading
        // as a surface and starts reading as a filter.
        val s = DialShading.sheen(DialParams(sheen = 100.0))!!
        assertNotNull(s)
        for (stop in s.stops) {
            assertTrue((stop.argb ushr 24) <= 96) { "sheen stop is ${stop.argb ushr 24}/255 opaque" }
        }
    }
}
