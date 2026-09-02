package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.HandStyle
import com.bfg.watchfaces.generator.Hands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * The hand images, as Watch Face Format will receive them.
 *
 * Geometry is pinned in `:generator`'s HandsTest. This is about what actually
 * lands in the PNG — transparency, that the fill flag changes something, and
 * that the pivot assumption holds — which is a different set of mistakes.
 */
class HandsRenderTest {

    private val p = DialParams()

    private fun opaque(img: BufferedImage): Int {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            if ((img.getRGB(x, y) ushr 24) > 8) n++
        }
        return n
    }

    /**
     * A hand is an OVERLAY, so most of the canvas must stay transparent.
     *
     * If this ever fails it means the dial background leaked into the hand
     * image — which on a watch is an opaque square rotating over the face,
     * roughly the worst thing this pipeline could ship.
     */
    @Test
    fun `a hand image is mostly transparent`() {
        for (hand in Hands.Hand.entries) {
            val img = DialRenderer.renderHand(p, HandStyle.BATON, hand)
            val filled = opaque(img).toDouble() / (img.width * img.height)
            assertTrue(filled < 0.10) { "$hand covers ${(filled * 100).toInt()}% of the canvas" }
            assertTrue(filled > 0.0) { "$hand drew nothing at all" }
        }
    }

    /** The corners are never part of a hand; a non-empty corner means a leaked background. */
    @Test
    fun `the corners stay empty`() {
        val img = DialRenderer.renderHand(p, HandStyle.BATON, Hands.Hand.MINUTE)
        for ((x, y) in listOf(0 to 0, img.width - 1 to 0, 0 to img.height - 1, img.width - 1 to img.height - 1)) {
            assertEquals(0, img.getRGB(x, y) ushr 24) { "corner ($x,$y) is not transparent" }
        }
    }

    /**
     * THE PIVOT ASSUMPTION, checked in pixels.
     *
     * Every hand is drawn on the full dial canvas so the pivot can be 0.5/0.5
     * with no per-style data. If the drawn hand were not symmetric about the
     * canvas centre, that pivot would be wrong and the hand would wobble as it
     * sweeps — the exact failure the full-canvas decision exists to prevent,
     * and one that is very hard to see in a photograph.
     */
    @Test
    fun `every hand is symmetric about the canvas centre`() {
        for (style in listOf(HandStyle.BATON, HandStyle.SKELETON)) {
            for (hand in Hands.Hand.entries) {
                val img = DialRenderer.renderHand(p, style, hand)
                var mismatched = 0
                for (y in 0 until img.height) for (x in 0 until img.width / 2) {
                    val l = img.getRGB(x, y) ushr 24 > 8
                    val r = img.getRGB(img.width - 1 - x, y) ushr 24 > 8
                    if (l != r) mismatched++
                }
                // A few pixels differ from antialiasing on an odd-width canvas.
                assertTrue(mismatched < 200) {
                    "$style $hand is asymmetric in $mismatched pixels; the 0.5 pivot would wobble"
                }
            }
        }
    }

    /**
     * The fill flag has to CHANGE something, or it is a field nobody reads.
     *
     * Skeleton is the identical outline unfilled, so it must cover meaningfully
     * less of the canvas than the solid baton.
     */
    @Test
    fun `skeleton covers less than baton`() {
        for (hand in Hands.Hand.entries) {
            val solid = opaque(DialRenderer.renderHand(p, HandStyle.BATON, hand))
            val hollow = opaque(DialRenderer.renderHand(p, HandStyle.SKELETON, hand))
            assertTrue(hollow < solid) { "$hand: skeleton ($hollow) is not lighter than baton ($solid)" }
        }
    }

    /**
     * An unfilled shape still has to be VISIBLE.
     *
     * The first version drew unfilled shapes with the engraved passes alone,
     * which are tuned for a dial pattern. The result was a skeleton hand too
     * faint to read the time from, and every geometry test passed while it was
     * true. This asserts the edge is actually there.
     */
    @Test
    fun `an unfilled hand still draws a readable edge`() {
        val hollow = opaque(DialRenderer.renderHand(p, HandStyle.SKELETON, Hands.Hand.MINUTE))
        assertTrue(hollow > 1500) { "the skeleton minute hand is only $hollow pixels; too faint to read" }
    }

    /**
     * Indices go through the SAME code as the hands.
     *
     * They briefly did not, and diverged within minutes: unfilled hands gained a
     * readable edge and unfilled indices kept none, so a skeleton face had hands
     * you could read and a chapter ring you could not.
     */
    @Test
    fun `unfilled indices are nearly as visible as filled ones`() {
        fun indexInk(style: HandStyle): Int {
            val img = BufferedImage(DIAL_SIZE, DIAL_SIZE, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            DialRenderer.drawIndices(g, p, style)
            g.dispose()
            return opaque(img)
        }
        // A RATIO, not a pixel count. The first version of this asserted a
        // magic threshold picked by eye and failed on correct output --
        // skeleton indices are 1252 px against baton's 1704, which is exactly
        // the "outlined but readable" the fix intends. What matters is that an
        // unfilled index is in the same league as a filled one; when the two
        // draw paths had diverged, this side was a faint fraction.
        val filled = indexInk(HandStyle.BATON)
        val hollow = indexInk(HandStyle.SKELETON)
        assertTrue(hollow > filled * 0.5) {
            "skeleton indices ($hollow) are far fainter than baton's ($filled); " +
                "the unfilled path has probably stopped drawing an edge"
        }
        assertTrue(hollow < filled) { "skeleton indices should be lighter than filled ones" }
    }

    /** The composite is a real image, not a blank canvas. */
    @Test
    fun `the assembled face draws a dial and hands`() {
        for (style in listOf(HandStyle.BATON, HandStyle.SKELETON)) {
            val img = HandsSheet.assemble(p, style)
            assertEquals(DIAL_SIZE, img.width)
            val filled = opaque(img).toDouble() / (img.width * img.height)
            // A round dial fills about pi/4 of a square canvas.
            assertTrue(filled > 0.70) { "$style assembled to ${(filled * 100).toInt()}% coverage" }
        }
    }

    /** Same params, same pixels — the shipped PNG must be reproducible. */
    @Test
    fun `rendering is deterministic`() {
        val a = DialRenderer.renderHand(p, HandStyle.BATON, Hands.Hand.HOUR)
        val b = DialRenderer.renderHand(p, HandStyle.BATON, Hands.Hand.HOUR)
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a.getRGB(x, y) != b.getRGB(x, y)) {
                throw AssertionError("hand render differs at ($x,$y) between two calls")
            }
        }
    }
}
