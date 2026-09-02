package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.DialParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The point of curating pairs is that they cannot be got wrong.
 *
 * Which is only true if somebody checks. Picking colours by eye, indoors, on a
 * monitor, is exactly how a pair that looks fine ships and then cannot be read
 * on a wrist in daylight.
 */
class ColourwayTest {

    /**
     * THE ONE THAT JUSTIFIES THE FEATURE.
     *
     * Free dial and ink swatches let anybody build an unreadable watch. A
     * curated list is worth having only if every entry clears a real floor, so
     * this asserts it with the same luminance maths the ambient palette uses
     * rather than a second opinion about brightness.
     */
    @Test
    fun `every colourway is readable`() {
        for (c in Colourway.entries) {
            val ratio = Colourway.contrast(c.dial, c.ink)
            assertTrue(ratio >= Colourway.MIN_CONTRAST) {
                "${c.label} has contrast ${"%.2f".format(ratio)}, under ${Colourway.MIN_CONTRAST}"
            }
        }
    }

    /** Applying one sets both colours and touches nothing else. */
    @Test
    fun `applying a colourway changes only the two colours`() {
        val before = DialParams()
        val after = Colourway.NOIR.applyTo(before)
        assertEquals(Colourway.NOIR.dial, after.dialColor)
        assertEquals(Colourway.NOIR.ink, after.inkColor)
        // Everything else survives, including the version -- a colourway is not
        // a change to the file format and must not behave like one.
        assertEquals(before.copy(dialColor = after.dialColor, inkColor = after.inkColor), after)
        assertEquals(before.generatorVersion, after.generatorVersion)
    }

    /** A face wearing one is recognised, so the picker can show what is selected. */
    @Test
    fun `a face made from a colourway is matched back to it`() {
        for (c in Colourway.entries) {
            assertEquals(c, Colourway.matching(c.applyTo(DialParams()))) {
                "${c.label} was not recognised on a face wearing it"
            }
        }
    }

    /**
     * Custom colours match NOTHING, rather than the nearest thing.
     *
     * Somebody who picked their own dial has not chosen a colourway, and showing
     * one as selected would be the app claiming a decision they did not make.
     */
    @Test
    fun `a custom pair matches no colourway`() {
        assertNull(Colourway.matching(DialParams(dialColor = "#123456", inkColor = "#FEDCBA")))
        // One half matching is still not a colourway.
        assertNull(Colourway.matching(DialParams(dialColor = Colourway.TAUPE.dial, inkColor = "#FF00FF")))
    }

    /** Two entries that look the same are one entry and a mistake. */
    @Test
    fun `no two colourways are the same pair`() {
        val pairs = Colourway.entries.map { it.dial.lowercase() to it.ink.lowercase() }
        assertEquals(pairs.distinct().size, pairs.size) { "two colourways share a pair" }
        val labels = Colourway.entries.map { it.label }
        assertEquals(labels.distinct().size, labels.size) { "two colourways share a name" }
    }

    /** Colours are #RRGGBB, the form DialParams requires; an 8-digit value is silently wrong. */
    @Test
    fun `every colour is a six digit hex`() {
        val hex = Regex("^#[0-9A-Fa-f]{6}$")
        for (c in Colourway.entries) {
            assertTrue(hex.matches(c.dial)) { "${c.label} dial ${c.dial} is not #RRGGBB" }
            assertTrue(hex.matches(c.ink)) { "${c.label} ink ${c.ink} is not #RRGGBB" }
            // Proves it against the real validator rather than just the shape.
            DialParams(dialColor = c.dial, inkColor = c.ink)
        }
    }

    /**
     * At least one pale dial.
     *
     * Everything else here is dark ink on a mid-to-dark ground, and a list where
     * every option is a variation of the same idea is not a choice. This is the
     * property that keeps the list honest as it grows.
     */
    @Test
    fun `the list is not all one idea`() {
        val pale = Colourway.entries.count {
            com.bfg.watchfaces.generator.AmbientPalette.relativeLuminance(it.dial) > 0.4
        }
        assertTrue(pale >= 1) { "every colourway is a dark dial; there is no real choice here" }
    }
}
