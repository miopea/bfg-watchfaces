package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every glyph the face draws must be fully expressible in Watch Face Format.
 *
 * `GlyphWff` maps shapes with `mapNotNull`, so a shape it cannot express is
 * DROPPED SILENTLY. That is not hypothetical and it is not cheap:
 *
 * - Steps and heart rate were a `Rotated` pair and a cubic `Curve`. They
 *   produced an EMPTY `PartDraw`, which is schema-invalid, and the only reason
 *   nobody noticed for months is that those two slots fell back to the
 *   provider's own icon — the icon whose colour started all this.
 * - The notification bell contained a `Curve` for its dome. That one is worse,
 *   because the glyph still rendered: it would have shipped a bell with no bell
 *   in it, and nothing would have failed.
 *
 * So this counts. A glyph that loses a stroke on the way to the watch is a
 * glyph that looks fine everywhere it is checked and wrong where it is worn.
 */
class GlyphDrawableTest {

    private fun elementCount(shapes: List<ComplicationGlyphs.Shape>): Int {
        val drawn = GlyphWff.elements(shapes, 32, "#ffffffff")
        return if (drawn.isBlank()) 0 else drawn.split("\n").count { it.isNotBlank() }
    }

    @Test
    fun `every source's glyph survives the trip into Watch Face Format`() {
        for (source in ComplicationSource.entries.filter { it.enabled }) {
            val shapes = ComplicationGlyphs.shapes(source)
            if (shapes.isEmpty()) continue          // weather draws its own text, no glyph
            assertEquals(shapes.size, elementCount(shapes)) {
                "${source.name}: ${shapes.size} shapes went in and ${elementCount(shapes)} came out. " +
                    "GlyphWff drops what it cannot express, so this glyph reaches the watch " +
                    "missing a stroke — or, at zero, as an empty PartDraw that fails the schema."
            }
        }
    }

    @Test
    fun `the two glyphs that used to be undrawable now draw`() {
        // Named individually because they are the reason this test exists: both
        // were falling back to the provider's icon, and the provider ships them
        // in green and red.
        for (source in listOf(ComplicationSource.STEP_COUNT, ComplicationSource.HEART_RATE)) {
            val shapes = ComplicationGlyphs.shapes(source)
            assertTrue(shapes.isNotEmpty()) { "${source.name} has no glyph at all" }
            assertEquals(shapes.size, elementCount(shapes)) { "${source.name} is still not drawable" }
        }
    }

    @Test
    fun `no glyph uses a shape the format cannot express`() {
        // The direct version of the same rule, so a NEW glyph authored with a
        // cubic fails here rather than in a count that a reader has to decode.
        for (source in ComplicationSource.entries) {
            for (shape in ComplicationGlyphs.shapes(source)) {
                assertTrue(
                    shape !is ComplicationGlyphs.Shape.Curve && shape !is ComplicationGlyphs.Shape.Rotated
                ) {
                    "${source.name} uses ${shape.javaClass.simpleName}, which Watch Face Format " +
                        "has no equivalent for. Draw it with Line, Oval, RoundRectangle or Arc."
                }
            }
        }
    }
}
