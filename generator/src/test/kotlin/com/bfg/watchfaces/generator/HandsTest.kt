package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The hands, before any of this reaches a renderer or a watch.
 *
 * These are the properties a person reads without knowing they are reading
 * them — which hand is which, that the thing is balanced on its pivot, that it
 * points at twelve — and every one of them is cheaper to pin here than to spot
 * in a photograph of a wrist.
 */
class HandsTest {

    private val drawn = listOf(HandStyle.BATON, HandStyle.SKELETON)

    /**
     * THE ONE THAT MAKES IT A WATCH.
     *
     * Hand length is how the hour and minute are told apart at a glance, far
     * more than width. Get this backwards and the face is unreadable in a way
     * no test of colours or boxes would ever catch.
     */
    @Test
    fun `the hour hand is shorter than the minute hand`() {
        for (style in drawn) {
            val hour = reachOf(style, Hands.Hand.HOUR)
            val minute = reachOf(style, Hands.Hand.MINUTE)
            val second = reachOf(style, Hands.Hand.SECOND)
            assertTrue(hour < minute) { "$style: hour $hour is not shorter than minute $minute" }
            assertTrue(minute < second) { "$style: minute $minute is not shorter than second $second" }
        }
    }

    /**
     * Every hand points at TWELVE and nothing here knows the time.
     *
     * Watch Face Format rotates the images itself. A hand emitted already
     * rotated would be rotated twice, and the failure looks like a watch that
     * is simply wrong rather than like a bug in this file.
     */
    @Test
    fun `every hand points straight up`() {
        for (style in drawn) {
            for (hand in Hands.Hand.entries) {
                val pts = Hands.shapes(style, hand).flatMap { it.outline }
                // SYMMETRY about the vertical, not "the topmost point is
                // centred" -- a baton's tip is an EDGE, so its highest points
                // sit at plus and minus the half-width and never on the centre
                // line. The first version of this test asserted the wrong
                // property and failed on correct geometry.
                val mirrored = pts.map { Pt(2 * DIAL_CENTER - it.x, it.y) }
                for (p in pts) {
                    assertTrue(mirrored.any { abs(it.x - p.x) < 0.001 && abs(it.y - p.y) < 0.001 }) {
                        "$style $hand is not symmetric about the vertical: $p has no mirror"
                    }
                }
                assertTrue(pts.minOf { it.y } < DIAL_CENTER) { "$style $hand does not point up" }
            }
        }
    }

    /** A hand has a tail, or it looks stuck to the pivot rather than balanced on it. */
    @Test
    fun `every hand carries a tail behind the pivot`() {
        for (style in drawn) {
            for (hand in Hands.Hand.entries) {
                val lowest = Hands.shapes(style, hand).flatMap { it.outline }.maxOf { it.y }
                assertTrue(lowest > DIAL_CENTER) { "$style $hand has no tail" }
            }
        }
    }

    /** Nothing may leave the dial; a hand clipped by the rim reads as broken. */
    @Test
    fun `hands, indices and hub all stay inside the dial`() {
        for (style in drawn) {
            val all = Hands.Hand.entries.flatMap { Hands.shapes(style, it) } +
                Hands.indices(style) + Hands.hub(style)
            for (shape in all) {
                for (pt in shape.outline) {
                    val r = hypot(pt.x - DIAL_CENTER, pt.y - DIAL_CENTER)
                    assertTrue(r <= DIAL_RADIUS) { "$style: point at radius $r leaves the dial" }
                }
            }
        }
    }

    /**
     * The chapter ring stays INSIDE the data ring.
     *
     * `RingSource` keeps the rim on an analog face — steps, battery and rain do
     * not stop mattering because somebody chose hands. If an index reached the
     * rim it would draw straight through the progress arc.
     */
    @Test
    fun `indices stay clear of the rim where the data ring lives`() {
        for (style in drawn) {
            val outermost = Hands.indices(style).flatMap { it.outline }
                .maxOf { hypot(it.x - DIAL_CENTER, it.y - DIAL_CENTER) }
            assertTrue(outermost < DIAL_RADIUS * 0.93) {
                "$style indices reach $outermost, into the data ring's track"
            }
        }
    }

    /** Twelve hours, twelve indices, and the quarters are the long ones. */
    @Test
    fun `there are twelve indices and the quarters are longer`() {
        for (style in drawn) {
            val idx = Hands.indices(style)
            assertEquals(12, idx.size) { "$style drew ${idx.size} indices" }
            val lengths = idx.map { s ->
                val rs = s.outline.map { hypot(it.x - DIAL_CENTER, it.y - DIAL_CENTER) }
                rs.max() - rs.min()
            }
            val quarters = listOf(0, 3, 6, 9).map { lengths[it] }
            val rest = lengths.filterIndexed { i, _ -> i % 3 != 0 }
            assertTrue(quarters.min() > rest.max()) {
                "$style: quarter indices are not longer than the rest"
            }
        }
    }

    /** Twelve o'clock is straight up, not at three where angle zero would put it. */
    @Test
    fun `the twelve index is at the top of the dial`() {
        val first = Hands.indices(HandStyle.BATON).first().outline
        val cx = first.map { it.x }.average()
        val cy = first.map { it.y }.average()
        assertTrue(abs(cx - DIAL_CENTER) < 2.0) { "the 12 index sits at x=$cx, not the centre" }
        assertTrue(cy < DIAL_CENTER) { "the 12 index is not at the top" }
    }

    /**
     * SKELETON is the same geometry, unfilled.
     *
     * If these ever diverge, the flag has stopped being a rendering choice and
     * become a second definition of the shape.
     */
    @Test
    fun `skeleton is baton's outline with the fill turned off`() {
        for (hand in Hands.Hand.entries) {
            val solid = Hands.shapes(HandStyle.BATON, hand)
            val hollow = Hands.shapes(HandStyle.SKELETON, hand)
            assertEquals(solid.map { it.outline }, hollow.map { it.outline })
            assertTrue(solid.all { it.filled }) { "baton should be filled" }
            assertTrue(hollow.none { it.filled }) { "skeleton should not be filled" }
        }
    }

    /** Same input, same geometry — the shipped PNG must be reproducible. */
    @Test
    fun `hand geometry is deterministic`() {
        for (style in drawn) {
            for (hand in Hands.Hand.entries) {
                assertEquals(Hands.shapes(style, hand), Hands.shapes(style, hand))
            }
            assertEquals(Hands.indices(style), Hands.indices(style))
        }
    }

    /** Every outline closes, so a renderer never has to special-case the join. */
    @Test
    fun `every outline is closed`() {
        for (style in drawn) {
            val all = Hands.Hand.entries.flatMap { Hands.shapes(style, it) } +
                Hands.indices(style) + Hands.hub(style)
            for (shape in all) {
                assertEquals(shape.outline.first(), shape.outline.last())
            }
        }
    }

    /**
     * A style with no geometry FAILS rather than quietly drawing a baton.
     *
     * `Presentation.UNOFFERED` exists because adding an engine without adding it
     * to the picker shipped a hidden feature. This is the same trap one layer
     * down: a style in the enum with no shapes would render as something else
     * entirely, and look like a drawing mistake rather than a missing branch.
     */
    @Test
    fun `an undrawn style fails loudly instead of substituting`() {
        for (style in listOf(HandStyle.DAUPHINE, HandStyle.SYRINGE)) {
            assertThrows(IllegalStateException::class.java) {
                Hands.shapes(style, Hands.Hand.HOUR)
            }
        }
    }

    /** Every style in the enum is either drawn or explicitly not yet drawn. */
    @Test
    fun `no style is silently unaccounted for`() {
        for (style in HandStyle.entries) {
            val result = runCatching { Hands.shapes(style, Hands.Hand.HOUR) }
            assertTrue(result.isSuccess || result.exceptionOrNull() is IllegalStateException) {
                "$style failed in an unexpected way: ${result.exceptionOrNull()}"
            }
            assertTrue(style.label.isNotBlank()) { "$style has no label for the picker" }
        }
    }

    private fun reachOf(style: HandStyle, hand: Hands.Hand): Double =
        DIAL_CENTER - Hands.shapes(style, hand).flatMap { it.outline }.minOf { it.y }
}
