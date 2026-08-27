package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.DIAL_CENTER
import com.bfg.watchfaces.generator.DIAL_RADIUS
import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.PatternEngines
import com.bfg.watchfaces.generator.Polyline
import java.awt.BasicStroke
import java.awt.Color
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage

/**
 * Rasterizes [DialParams] to the dial background PNG.
 *
 * This is the ONLY rasterizer in the repo, and it is deliberately the same code
 * path for both the workbench's live preview and the baked `dial_bg.png`. The
 * browser never draws the pattern itself -- it requests a PNG from here. That
 * keeps the SPEC's "what you see is what ships, by construction" property
 * intact: there is one renderer, not a preview renderer and a shipping renderer
 * that drift apart.
 *
 * Geometry comes from [PatternEngines] untouched. This class only strokes it.
 * Do not put geometry decisions here -- they belong in the engines, where
 * `generatorVersion` guards them.
 */
object DialRenderer {

    /** Antialiased, high-quality hints. Bake and preview must agree exactly. */
    private fun hints(g: java.awt.Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    }

    fun hex(s: String): Color {
        val v = s.removePrefix("#")
        return Color(v.substring(0, 2).toInt(16), v.substring(2, 4).toInt(16), v.substring(4, 6).toInt(16))
    }

    private fun mix(a: Color, b: Color, t: Double): Color {
        val u = t.coerceIn(0.0, 1.0)
        return Color(
            (a.red + (b.red - a.red) * u).toInt().coerceIn(0, 255),
            (a.green + (b.green - a.green) * u).toInt().coerceIn(0, 255),
            (a.blue + (b.blue - a.blue) * u).toInt().coerceIn(0, 255)
        )
    }

    private fun alpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a.coerceIn(0, 255))

    private fun path(pl: Polyline): Path2D.Double {
        val p = Path2D.Double()
        if (pl.isEmpty()) return p
        p.moveTo(pl[0].x, pl[0].y)
        for (i in 1 until pl.size) p.lineTo(pl[i].x, pl[i].y)
        return p
    }

    /**
     * The dial texture exactly as it ships. [size] is the raster size; geometry
     * is always authored in 456x456 dial space and scaled here, so a 912px
     * render for a hi-dpi browser preview is the same image, not a different one.
     */
    fun render(p: DialParams, size: Int = DIAL_SIZE): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        hints(g)
        val s = size.toDouble() / DIAL_SIZE
        g.scale(s, s)

        val dial = hex(p.dialColor)
        val ink = hex(p.inkColor)

        // Clip everything to the dial circle. The watch is round; square corners
        // waste bytes over Bluetooth and are never seen.
        val circle = Ellipse2D.Double(0.0, 0.0, DIAL_SIZE.toDouble(), DIAL_SIZE.toDouble())
        g.clip = circle

        g.color = dial
        g.fill(circle)

        drawSheen(g, dial, p.sheen)
        drawPattern(g, p, dial, ink)
        drawVignette(g, p.vignette)

        g.dispose()
        return img
    }

    /** Soft directional lustre, as if light falls across brushed metal. */
    private fun drawSheen(g: java.awt.Graphics2D, dial: Color, sheen: Double) {
        if (sheen <= 0.0) return
        val k = (sheen / 100.0).coerceIn(0.0, 1.0)
        val light = alpha(mix(dial, Color.WHITE, 0.75), (k * 90).toInt())
        val dark = alpha(mix(dial, Color.BLACK, 0.55), (k * 70).toInt())
        g.paint = java.awt.LinearGradientPaint(
            Point2D.Double(DIAL_SIZE * 0.12, DIAL_SIZE * 0.05),
            Point2D.Double(DIAL_SIZE * 0.88, DIAL_SIZE * 0.95),
            floatArrayOf(0.0f, 0.5f, 1.0f),
            arrayOf(light, alpha(dial, 0), dark)
        )
        g.fillRect(0, 0, DIAL_SIZE, DIAL_SIZE)
    }

    /**
     * The engraved look: three passes per polyline. Light pass offset by
     * -relief, dark pass by +relief, thin mid pass at zero. This is a RENDERER
     * concern and is deliberately not baked into the engines -- see CLAUDE.md.
     */
    private fun drawPattern(g: java.awt.Graphics2D, p: DialParams, dial: Color, ink: Color) {
        val paths = PatternEngines.paths(p).map { path(it) }
        if (paths.isEmpty()) return

        val k = (p.contrast / 100.0).coerceIn(0.0, 1.0)
        val light = alpha(mix(dial, Color.WHITE, 0.80), (k * 205).toInt())
        val dark = alpha(mix(dial, Color.BLACK, 0.62), (k * 185).toInt())
        val mid = alpha(ink, (k * 42).toInt())

        val d = p.relief * 0.7071  // diagonal component, so relief reads as a distance
        val cap = BasicStroke.CAP_ROUND
        val join = BasicStroke.JOIN_ROUND

        fun pass(dx: Double, dy: Double, color: Color, width: Double) {
            val old = g.transform
            g.translate(dx, dy)
            g.color = color
            g.stroke = BasicStroke(width.toFloat(), cap, join)
            for (pa in paths) g.draw(pa)
            g.transform = old
        }

        pass(-d, -d, light, p.stroke)          // highlight, up-left
        pass(d, d, dark, p.stroke)             // shadow, down-right
        pass(0.0, 0.0, mid, p.stroke * 0.5)    // thin mid pass holds the line together

        if (p.lens) drawLens(g, p, paths, light, dark)
    }

    /**
     * The "lens": a magnifier over the centre of the dial, where the time sits.
     *
     * NOTE -- this is a PREVIEW-ONLY effect on the shipped dial today. In WFF the
     * dial <PartImage> is always below the <DigitalClock> in the scene, so a
     * texture baked into dial_bg.png cannot appear OVER the numerals, which is
     * what DialParams.lens documents it as doing. Expressing it properly needs a
     * second transparent PartImage emitted AFTER the clock, which is a change to
     * the stored file format. See DECISIONS.md.
     *
     * What this does here is the part that IS shippable: a localised lift in
     * relief and brightness under the time, which reads as a lens.
     */
    private fun drawLens(
        g: java.awt.Graphics2D,
        p: DialParams,
        paths: List<Path2D.Double>,
        light: Color,
        dark: Color
    ) {
        val amt = (p.lensAmount / 100.0).coerceIn(0.0, 1.0)
        if (amt <= 0.0) return
        val r = DIAL_RADIUS * 0.46
        val old = g.clip
        g.clip(Ellipse2D.Double(DIAL_CENTER - r, DIAL_CENTER - r, r * 2, r * 2))

        val d = p.relief * 0.7071 * (1.0 + amt * 0.9)
        g.stroke = BasicStroke((p.stroke * (1.0 + amt * 0.35)).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = alpha(light, (light.alpha * amt * 0.75).toInt())
        val t = g.transform
        g.translate(-d, -d); for (pa in paths) g.draw(pa); g.transform = t
        g.color = alpha(dark, (dark.alpha * amt * 0.75).toInt())
        g.translate(d, d); for (pa in paths) g.draw(pa); g.transform = t

        g.clip = old
    }

    /** Darkened rim. Keeps the eye on the time and hides engine edge artifacts. */
    private fun drawVignette(g: java.awt.Graphics2D, vignette: Double) {
        if (vignette <= 0.0) return
        val k = (vignette / 100.0).coerceIn(0.0, 1.0)
        g.paint = RadialGradientPaint(
            Point2D.Double(DIAL_CENTER, DIAL_CENTER),
            DIAL_RADIUS.toFloat(),
            floatArrayOf(0.0f, 0.55f, 1.0f),
            arrayOf(Color(0, 0, 0, 0), Color(0, 0, 0, (k * 40).toInt()), Color(0, 0, 0, (k * 235).toInt())),
            MultipleGradientPaint.CycleMethod.NO_CYCLE
        )
        g.fillRect(0, 0, DIAL_SIZE, DIAL_SIZE)
    }
}
