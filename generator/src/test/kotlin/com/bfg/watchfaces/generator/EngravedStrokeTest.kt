package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The engraved look is now described here and executed by each platform, so this
 * arithmetic decides what every dial in the catalog looks like on every device.
 *
 * The exact-colour test below is the important one. It is not testing that a
 * blend works — it is pinning the numbers, because this was extracted out of the
 * workbench renderer on the promise that it changed nothing, and the only way
 * that promise survives a year is if a test fails when someone improves the
 * arithmetic.
 */
class EngravedStrokeTest {

    @Test
    fun `three passes, in drawing order`() {
        val passes = EngravedStroke.passes(DialParams())
        assertEquals(3, passes.size)

        val (light, dark, mid) = passes
        assertTrue(light.dx < 0 && light.dy < 0) { "the highlight goes up-left, got $light" }
        assertTrue(dark.dx > 0 && dark.dy > 0) { "the shadow goes down-right, got $dark" }
        assertEquals(0.0, mid.dx)
        assertEquals(0.0, mid.dy)
    }

    @Test
    fun `the mid pass is last and thin`() {
        // Last so it sits over both offsets: that is what stops a thin line
        // reading as two parallel ghosts at high relief.
        val p = DialParams()
        val passes = EngravedStroke.passes(p)
        val mid = passes.last()
        assertEquals(p.stroke * 0.5, mid.width, 1e-9) { "the mid pass holds the line together and is half width" }
        assertEquals(p.stroke, passes[0].width, 1e-9)
        assertEquals(p.stroke, passes[1].width, 1e-9)
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.0, 0.5, 1.5, 3.0, 6.0])
    fun `relief reads as a distance, not a per-axis offset`(relief: Double) {
        // The offset is relief * 0.7071 on each axis, so the diagonal distance
        // is relief itself. Using relief per-axis would put the passes sqrt(2)
        // too far apart and the emboss would read heavier than the slider says.
        val passes = EngravedStroke.passes(DialParams(relief = relief))
        val light = passes[0]
        val distance = kotlin.math.hypot(light.dx, light.dy)
        assertEquals(relief, distance, 1e-3) { "relief $relief produced a diagonal of $distance" }
    }

    @Test
    fun `contrast at zero makes every pass invisible rather than black`() {
        // A face at zero contrast should be a plain dial, not a dark one.
        for (pass in EngravedStroke.passes(DialParams(contrast = 0.0))) {
            assertEquals(0, pass.argb ushr 24) { "pass is not fully transparent: $pass" }
        }
    }

    @Test
    fun `contrast raises alpha and nothing else`() {
        val low = EngravedStroke.passes(DialParams(contrast = 20.0))
        val high = EngravedStroke.passes(DialParams(contrast = 90.0))
        for (i in low.indices) {
            assertTrue((high[i].argb ushr 24) > (low[i].argb ushr 24)) { "pass $i did not get more opaque" }
            assertEquals(low[i].argb and 0xFFFFFF, high[i].argb and 0xFFFFFF) {
                "contrast changed the colour of pass $i, not just its alpha"
            }
        }
    }

    @Test
    fun `the highlight is lighter than the dial and the shadow darker`() {
        val p = DialParams(dialColor = "#7D7369")
        val dial = EngravedStroke.rgb(p.dialColor)
        val passes = EngravedStroke.passes(p)
        fun luma(c: Int) = ((c shr 16) and 0xFF) * 0.299 + ((c shr 8) and 0xFF) * 0.587 + (c and 0xFF) * 0.114

        assertTrue(luma(passes[0].argb and 0xFFFFFF) > luma(dial)) { "the highlight is not lighter than the dial" }
        assertTrue(luma(passes[1].argb and 0xFFFFFF) < luma(dial)) { "the shadow is not darker than the dial" }
    }

    /**
     * The regression guard.
     *
     * These numbers were produced by the workbench renderer before this was
     * extracted, and every engine rendered byte-identically afterwards. If this
     * test fails, the dial's appearance has changed — which is a
     * `generatorVersion` matter, not a tidy-up.
     */
    @Test
    fun `the default face's passes are exactly what the workbench always drew`() {
        val passes = EngravedStroke.passes(DialParams(dialColor = "#7D7369", inkColor = "#FCF9F1"))
        assertEquals(
            // Derived independently from DialParams' defaults and the
            // renderer's arithmetic, then confirmed against what passes()
            // produces. contrast defaults to 30, not the 36 the app opens on.
            listOf("3de5e3e1", "372f2b27", "0cfcf9f1"),
            passes.map { "%08x".format(it.argb) }
        ) {
            "the engraved colours changed. Every face in the catalog is stored as " +
                "parameters, so this restyles all of them. See generatorVersion."
        }
    }

    @Test
    fun `mix truncates, as the workbench always did`() {
        // Pinned deliberately. Rounding would be defensible and is NOT what this
        // does, because changing it shifts every dial already saved.
        assertEquals(0x7F, EngravedStroke.mix(0x000000, 0xFFFFFF, 0.5) and 0xFF)
        assertEquals(0xFFFFFF, EngravedStroke.mix(0x000000, 0xFFFFFF, 1.0))
        assertEquals(0x000000, EngravedStroke.mix(0x000000, 0xFFFFFF, 0.0))
    }

    @Test
    fun `colours are six digits here, eight only in emitted WFF`() {
        assertEquals(0x7D7369, EngravedStroke.rgb("#7D7369"))
        assertEquals(0x7D7369, EngravedStroke.rgb("7D7369"))
        assertTrue(
            runCatching { EngravedStroke.rgb("#FF7D7369") }.isFailure
        ) { "an 8-digit colour must be refused: DialParams stores #RRGGBB" }
    }
}
