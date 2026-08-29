package com.bfg.watchfaces.workbench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot

/**
 * The app icon is generated, so these are the checks a designer would otherwise
 * make by opening the file on a phone.
 *
 * Two of them exist because the failure is invisible: an adaptive icon whose
 * artwork strays outside the safe zone looks fine in Android Studio's preview
 * and loses its crown on a launcher with a circular mask; and a VectorDrawable
 * with a `<circle>` in it, or a number written `2,60` by a comma-decimal locale,
 * fails at aapt2 link time with a message about neither.
 */
class BrandMarkTest {

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))

    @Test
    fun `the enclosing circle actually encloses the mark`() {
        val c = BrandMark.enclosingCircle()
        for (s in BrandMark.samples()) {
            val reach = hypot(s.x - c.cx, s.y - c.cy) + s.pad
            assertTrue(reach <= c.radius + 1e-6) {
                "(${s.x}, ${s.y}) reaches $reach, outside r=${c.radius}"
            }
        }
    }

    @Test
    fun `the circle is centred on the artwork, not on the canvas`() {
        // The crown hangs off the right, so the artwork's middle is right of and
        // slightly above the dial centre. If this ever equals (48, 48) somebody
        // has replaced the fit with an assumption about the canvas.
        val c = BrandMark.enclosingCircle()
        assertTrue(c.cx > BrandMark.CX) { "the crown should pull the centre right, got cx=${c.cx}" }
        assertTrue(c.cy < BrandMark.CY) { "the open bottom should pull the centre up, got cy=${c.cy}" }
    }

    @Test
    fun `every stroke lands inside the adaptive icon keyline`() {
        // The mask is applied to the middle 72dp of the 108dp layer, and
        // Material's keyline for a round motif is 60dp inside that. Anything
        // outside the keyline is cropped by SOME launcher.
        val fit = BrandMark.fitInto(Brand.ADAPTIVE_CANVAS, Brand.ADAPTIVE_KEYLINE)
        val centre = Brand.ADAPTIVE_CANVAS / 2
        val safe = Brand.ADAPTIVE_KEYLINE / 2
        for (s in BrandMark.samples()) {
            val reach = hypot(fit.x(s.x) - centre, fit.y(s.y) - centre) + fit.len(s.pad)
            assertTrue(reach <= safe + 1e-6) { "a stroke reaches ${reach}dp of the ${safe}dp keyline radius" }
        }
    }

    @Test
    fun `the mark fills the keyline rather than floating in it`() {
        // The other half of the previous test. Fitting to a circle of zero
        // radius would pass that one perfectly.
        val fit = BrandMark.fitInto(Brand.ADAPTIVE_CANVAS, Brand.ADAPTIVE_KEYLINE)
        val centre = Brand.ADAPTIVE_CANVAS / 2
        val reach = BrandMark.samples().maxOf {
            hypot(fit.x(it.x) - centre, fit.y(it.y) - centre) + fit.len(it.pad)
        }
        assertEquals(Brand.ADAPTIVE_KEYLINE / 2, reach, 0.01) { "the mark does not reach its keyline" }
    }

    @Test
    fun `the crown is on the right, above the middle, and outside the dial`() {
        // The whole point of the chosen direction. A refactor that drops it
        // leaves a circle with hands, which is every clock icon ever drawn.
        val outer = BrandMark.parts.filterIsInstance<BrandMark.Part.Arc>().maxOf { it.radius }
        val crown = BrandMark.parts.filterIsInstance<BrandMark.Part.Dot>()
            .maxByOrNull { hypot(it.cx - BrandMark.CX, it.cy - BrandMark.CY) }!!
        assertTrue(crown.cx > BrandMark.CX) { "the crown is not to the right of the dial" }
        assertTrue(crown.cy < BrandMark.CY) { "the crown is not above the middle" }
        assertTrue(hypot(crown.cx - BrandMark.CX, crown.cy - BrandMark.CY) > outer) {
            "the crown sits on the dial instead of outside it"
        }
    }

    @Test
    fun `the crown clears the minute hand`() {
        val hands = BrandMark.parts.filterIsInstance<BrandMark.Part.Line>()
            .filter { it.ink == BrandMark.Ink.HAND }
        val crown = BrandMark.parts.filterIsInstance<BrandMark.Part.Dot>()
            .maxByOrNull { hypot(it.cx - BrandMark.CX, it.cy - BrandMark.CY) }!!
        for (hand in hands) {
            val gap = hypot(crown.cx - hand.x2, crown.cy - hand.y2) - crown.radius - hand.width / 2
            assertTrue(gap > 4.0) { "the crown is ${gap} from a hand tip; they read as one shape" }
        }
    }

    @Test
    fun `the vector drawable is all paths, because VectorDrawable has no circle`() {
        val fit = BrandMark.fitInto(Brand.ADAPTIVE_CANVAS, Brand.ADAPTIVE_KEYLINE)
        val doc = parse(Brand.vectorDrawable(fit, BrandMark.Palette.LIGHT, monochrome = false))
        assertEquals("vector", doc.documentElement.tagName)

        val children = doc.documentElement.childNodes
        var paths = 0
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            assertEquals("path", node.tagName) { "<${node.tagName}> is not a VectorDrawable element" }
            assertTrue(node.getAttribute("android:pathData").isNotBlank()) { "a path has no pathData" }
            paths++
        }
        assertEquals(BrandMark.parts.size, paths)
    }

    @Test
    fun `no number is written with a comma for a decimal point`() {
        // aapt2 rejects `android:strokeWidth="1,73"`, and the default locale of
        // whoever regenerates the icon decides whether it appears.
        val fit = BrandMark.fitInto(Brand.ADAPTIVE_CANVAS, Brand.ADAPTIVE_KEYLINE)
        val xml = Brand.vectorDrawable(fit, BrandMark.Palette.LIGHT, monochrome = false)
        for (m in Regex("""android:[A-Za-z]+="([^"]*)"""").findAll(xml)) {
            assertTrue(',' !in m.groupValues[1]) { "${m.value} was written in a comma-decimal locale" }
        }
    }

    @Test
    fun `a whole number keeps its magnitude`() {
        // "10.00" trimmed of trailing zeros without checking for a decimal point
        // becomes "1", which moves a stroke by an order of magnitude.
        assertEquals("10", BrandMark.n(10.0))
        assertEquals("100", BrandMark.n(100.0))
        assertEquals("2.6", BrandMark.n(2.6))
        assertEquals("0", BrandMark.n(-0.001))
    }

    @Test
    fun `the themed layer carries the shape and none of the colour`() {
        val fit = BrandMark.fitInto(Brand.ADAPTIVE_CANVAS, Brand.ADAPTIVE_KEYLINE)
        val mono = Brand.vectorDrawable(fit, BrandMark.Palette.LIGHT, monochrome = true)
        assertTrue(BrandMark.Palette.LIGHT.mark !in mono) { "the monochrome layer is not monochrome" }
        assertTrue(BrandMark.Palette.LIGHT.hand !in mono) { "the monochrome layer is not monochrome" }

        val colour = Brand.vectorDrawable(fit, BrandMark.Palette.LIGHT, monochrome = false)
        val strip = Regex("""android:(stroke|fill)Color="#[0-9A-Fa-f]+"""")
        assertEquals(strip.replace(colour, ""), strip.replace(mono, "")) {
            "the two layers draw different shapes"
        }
    }

    @Test
    fun `the adaptive icon declares all three layers`() {
        val doc = parse(Brand.adaptiveIcon())
        val tags = (0 until doc.documentElement.childNodes.length)
            .mapNotNull { doc.documentElement.childNodes.item(it) as? Element }
            .map { it.tagName }
        assertEquals(listOf("background", "foreground", "monochrome"), tags)
    }

    @Test
    fun `the standalone tile is drawn at the size it was designed at`() {
        val svg = Brand.tileSvg(BrandMark.Palette.DARK)
        val doc = parse(svg)
        assertEquals("0 0 96 96", doc.documentElement.getAttribute("viewBox"))
        assertTrue(BrandMark.Palette.DARK.ground in svg)
        // The dark hands are lighter than the dark dial: it is not one ink.
        assertTrue(BrandMark.Palette.DARK.hand in svg)
    }

    @Test
    fun `the Play icon is opaque, square and the right size`() {
        val img = Brand.storeIcon()
        assertEquals(Brand.STORE_PX, img.width)
        assertEquals(Brand.STORE_PX, img.height)
        assertTrue(img.colorModel.hasAlpha()) { "Play asks for a 32-bit PNG" }
        for (x in 0 until img.width step 7) {
            for (y in 0 until img.height step 7) {
                assertEquals(0xFF, img.getRGB(x, y) ushr 24) {
                    "($x, $y) is not opaque; Play composites transparency against white"
                }
            }
        }
        // A corner is ground colour: the mark is inset, not bleeding to the edge
        // where Play's own rounding would cut it.
        assertEquals(Color.decodeRgb(BrandMark.Palette.LIGHT.ground), img.getRGB(2, 2) and 0xFFFFFF)
    }

    @Test
    fun `the feature graphic is the size Play demands, and opaque`() {
        val img = Brand.featureGraphic()
        assertEquals(Brand.FEATURE_W, img.width)
        assertEquals(Brand.FEATURE_H, img.height)
        for (x in 0 until img.width step 11) {
            for (y in 0 until img.height step 11) {
                assertEquals(0xFF, img.getRGB(x, y) ushr 24) { "($x, $y) is not opaque" }
            }
        }
    }

    @Test
    fun `the feature graphic keeps its left clear and runs off its right`() {
        // Play overlays the icon and name over the left of this image in some
        // placements and crops the edges in others. A composition that drifted
        // to the middle would satisfy neither, and looks like a diagram.
        val img = Brand.featureGraphic()
        val ground = Color.decodeRgb(BrandMark.Palette.LIGHT.ground)
        for (y in 0 until img.height step 7) {
            assertEquals(ground, img.getRGB(2, y) and 0xFFFFFF) {
                "something reaches the left edge at y=$y"
            }
        }
        val bleeding = (0 until img.height step 7).count {
            (img.getRGB(img.width - 2, it) and 0xFFFFFF) != ground
        }
        assertTrue(bleeding > 20) { "nothing runs off the right edge; only $bleeding rows differ" }
    }

    @Test
    fun `the feature graphic shows real dials, not a drawing of them`() {
        // Every dial is DialRenderer output from DialParams -- the same call the
        // workbench preview and the shipped dial_bg.png make. If someone
        // replaces them with static art, this stops being true.
        assertTrue(Brand.FEATURE_DIALS.size >= 3)
        assertEquals(Brand.FEATURE_DIALS.size, Brand.FEATURE_DIALS.map { it.params.engine }.toSet().size) {
            "two dials use the same engine; the point is to show the range"
        }
        for (placed in Brand.FEATURE_DIALS) {
            assertTrue(!placed.params.lens) { "the lens is a preview-only effect and never reaches a face" }
        }
    }

    private object Color {
        fun decodeRgb(hex: String) = hex.removePrefix("#").toInt(16)
    }
}
