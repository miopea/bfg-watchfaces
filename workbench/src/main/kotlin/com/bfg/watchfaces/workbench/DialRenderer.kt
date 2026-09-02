package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.DIAL_CENTER
import com.bfg.watchfaces.generator.DIAL_RADIUS
import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.HandStyle
import com.bfg.watchfaces.generator.Hands
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.DialShading
import com.bfg.watchfaces.generator.EngravedStroke
import com.bfg.watchfaces.generator.PatternEngines
import com.bfg.watchfaces.generator.ProceduralDial
import com.bfg.watchfaces.generator.TextureField
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
    fun render(p: DialParams, size: Int = DIAL_SIZE, texture: BufferedImage? = null): BufferedImage {
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

        // An imported image replaces the generated pattern entirely. Sheen and
        // vignette still apply on top, so a photo sits on the same dial as the
        // engines rather than looking pasted on.
        val procedural = TextureField.kindFor(p.engine)
        if (texture != null) drawTexture(g, texture, p)
        else if (procedural != null) drawProcedural(g, procedural, p, dial, size)
        else drawSheen(g, p)

        if (texture == null && procedural == null) drawPattern(g, p, dial, ink)
        drawVignette(g, p)

        g.dispose()
        return img
    }

    /**
     * One hand, alone on a transparent 456x456 canvas, pointing at twelve.
     *
     * ## Why the whole canvas for one hand
     *
     * Watch Face Format rotates a hand image about a pivot given as a fraction
     * of that image. Drawing every hand on the full dial canvas makes the pivot
     * 0.5/0.5 for all of them, always, with no per-style data to get wrong.
     * A wrong pivot is a hand that WOBBLES as it sweeps -- subtle enough to
     * ship, and miserable to diagnose from a photograph of a wrist. The empty
     * space costs almost nothing once quantized.
     *
     * ## Filled, then stroked
     *
     * [drawPattern] only strokes, because a guilloche pattern is lines. A hand
     * is a solid object with an engraved edge, so this fills the outline first
     * and then runs the SAME [EngravedStroke] passes over it. That is what makes
     * a hand look like it was cut from the same metal as the dial rather than
     * printed on top of it.
     *
     * [Hands.HandShape.filled] decides, not this method -- the style owns that,
     * so the Android renderer cannot disagree. SKELETON is the identical outline
     * with the fill skipped, which is how the dial pattern shows through.
     *
     * No dial background and no clip circle: this is a transparent overlay that
     * WFF composites over the dial.
     */
    fun renderHand(
        p: DialParams,
        style: HandStyle,
        hand: Hands.Hand,
        size: Int = DIAL_SIZE,
        /** The hand's own colour, for a second hand that differs from the ink. */
        color: String = p.inkColor,
        /** Ambient draws the same hand as an outline; see [Hands.ambientShapes]. */
        ambient: Boolean = false
    ): BufferedImage = renderShapes(
        p,
        if (ambient) Hands.ambientShapes(style, hand) else Hands.shapes(style, hand),
        size, color
    )

    /**
     * How heavy an unfilled hand's edge is drawn, in dial units.
     *
     * Wide enough to read at a glance on a 456px dial and no wider: the whole
     * point of an outline hand is that the pattern shows through it.
     */
    private const val OUTLINE_WIDTH = 3.0f

    /** The hub the hands turn on. Static: it does not rotate with anything. */
    fun renderHub(p: DialParams, style: HandStyle, size: Int = DIAL_SIZE, ambient: Boolean = false): BufferedImage =
        renderShapes(
            p,
            listOf(Hands.hub(style).let { if (ambient) it.copy(filled = false) else it }),
            size, p.inkColor
        )

    /**
     * Shared by every hand, the hub and the indices, so all four are cut the
     * same way. A second copy of this loop is exactly how two renderers end up
     * drawing one definition differently.
     */
    private fun renderShapes(
        p: DialParams,
        shapes: List<Hands.HandShape>,
        size: Int,
        color: String
    ): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        hints(g)
        val s = size.toDouble() / DIAL_SIZE
        g.scale(s, s)
        drawShapes(g, p, shapes, color)
        g.dispose()
        return img
    }

    /**
     * ONE way to cut a hand, an index or a hub.
     *
     * There were briefly two copies of this — one for the rotating hands and
     * one for the indices — and within minutes they had diverged: unfilled
     * hands gained a readable edge and unfilled INDICES did not, so a skeleton
     * face rendered with hands you could read and a chapter ring you could not.
     * Caught by looking at the sheet, by nothing else.
     *
     * That is the [SlotGeometry] lesson in miniature and it took under an hour
     * to repeat, in a file whose own comment warned about it.
     */
    private fun drawShapes(
        g: java.awt.Graphics2D,
        p: DialParams,
        shapes: List<Hands.HandShape>,
        color: String
    ) {
        val paths = shapes.map { it to path(it.outline) }

        for ((shape, pa) in paths) {
            if (shape.filled) {
                g.color = hex(color)
                g.fill(pa)
            } else {
                // AN UNFILLED SHAPE STILL NEEDS A READABLE EDGE.
                //
                // The engraved passes alone are not enough. They are tuned for a
                // guilloche pattern, where subtlety is the point — on a bare
                // outline they produced a skeleton hand too faint to read the
                // time from. Every geometry test passed; the sheet showed it at
                // a glance.
                //
                // A real skeleton hand is solid metal with the middle cut away,
                // so it has a genuine edge. This draws that edge in the ink and
                // the passes below give it the same relief as a filled style.
                g.color = hex(color)
                g.stroke = BasicStroke(OUTLINE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(pa)
            }
        }

        // The dial's own passes, so the edge relief matches the pattern exactly.
        for (pass in EngravedStroke.passes(p)) {
            val old = g.transform
            g.translate(pass.dx, pass.dy)
            g.color = Color(pass.argb, true)
            g.stroke = BasicStroke(pass.width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for ((_, pa) in paths) g.draw(pa)
            g.transform = old
        }
    }

    /**
     * The chapter ring, drawn INTO the dial rather than as a rotating overlay.
     *
     * Indices do not move, so they belong in `dial_bg.png` with the pattern.
     * Emitting them as a separate image would cost a `PartImage` and a second
     * full-size PNG over Bluetooth for something that never changes.
     *
     * Inboard of the rim on purpose: `RingSource` keeps the outer track, so an
     * analog face still shows steps, battery or rain. See
     * `docs/specs/analog-hands.md` section 4.
     */
    fun drawIndices(g: java.awt.Graphics2D, p: DialParams, style: HandStyle) =
        drawShapes(g, p, Hands.indices(style), p.inkColor)

    /**
     * Centre-crop an imported image to fill the dial, preserving aspect ratio.
     *
     * Cover, not contain: a dial with letterboxing on it is not a watch face.
     * [DialParams.contrast] fades it toward the dial colour so imported artwork
     * can be pushed back far enough for the time to stay readable, which is the
     * thing most photos get wrong on a watch.
     */
    private fun drawTexture(g: java.awt.Graphics2D, tex: BufferedImage, p: DialParams) {
        val scale = maxOf(DIAL_SIZE.toDouble() / tex.width, DIAL_SIZE.toDouble() / tex.height)
        val w = tex.width * scale
        val h = tex.height * scale
        val at = AffineTransform.getTranslateInstance((DIAL_SIZE - w) / 2, (DIAL_SIZE - h) / 2)
        at.scale(scale, scale)
        g.drawImage(tex, at, null)

        // contrast 100 = image as-is; lower fades it into the dial colour so the
        // numerals stay legible over it.
        val fade = (1.0 - (p.contrast / 100.0)).coerceIn(0.0, 1.0)
        if (fade > 0.0) {
            g.color = alpha(hex(p.dialColor), (fade * 235).toInt())
            g.fillRect(0, 0, DIAL_SIZE, DIAL_SIZE)
        }
        drawSheen(g, p.copy(sheen = p.sheen * 0.5))
    }

    /**
     * Shade a generated height field into the dial.
     *
     * The decisions moved to [ProceduralDial]; what is left is turning its
     * pixels into an image and putting them on the canvas.
     */
    private fun drawProcedural(
        g: java.awt.Graphics2D,
        kind: TextureField.Kind,
        p: DialParams,
        dial: Color,
        size: Int
    ) {
        // The lighting model lives in :generator so the Android renderer gets
        // the identical surface rather than a second copy of the arithmetic.
        // This is now only the blit.
        val px = ProceduralDial.pixels(kind, p, dial.rgb and 0xFFFFFF, size)
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, size, size, px, 0, size)

        val old = g.transform
        g.transform = AffineTransform()   // px are already at render scale
        g.clip(Ellipse2D.Double(0.0, 0.0, size.toDouble(), size.toDouble()))
        g.drawImage(img, 0, 0, null)
        g.transform = old
        drawSheen(g, p)
    }

    /** Soft directional lustre, as if light falls across brushed metal. */
    private fun drawSheen(g: java.awt.Graphics2D, params: DialParams) {
        // Described in :generator so the Android renderer gets the identical
        // gradient rather than a second copy of these constants.
        val spec = DialShading.sheen(params) ?: return
        g.paint = java.awt.LinearGradientPaint(
            Point2D.Double(spec.fromX, spec.fromY),
            Point2D.Double(spec.toX, spec.toY),
            spec.stops.map { it.at }.toFloatArray(),
            spec.stops.map { Color(it.argb, true) }.toTypedArray()
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

        // The passes themselves come from :generator, so the Android renderer
        // gets the identical description rather than a second copy of this
        // arithmetic. This class now only knows how to EXECUTE a pass in AWT.
        val cap = BasicStroke.CAP_ROUND
        val join = BasicStroke.JOIN_ROUND
        for (pass in EngravedStroke.passes(p)) {
            val old = g.transform
            g.translate(pass.dx, pass.dy)
            g.color = Color(pass.argb, true)
            g.stroke = BasicStroke(pass.width.toFloat(), cap, join)
            for (pa in paths) g.draw(pa)
            g.transform = old
        }

        if (p.lens) {
            // The lens reuses the highlight and shadow colours, so it reads the
            // same passes rather than recomputing them.
            val passes = EngravedStroke.passes(p)
            drawLens(g, p, paths, Color(passes[0].argb, true), Color(passes[1].argb, true))
        }
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
    private fun drawVignette(g: java.awt.Graphics2D, params: DialParams) {
        val spec = DialShading.vignette(params) ?: return
        g.paint = RadialGradientPaint(
            Point2D.Double(spec.centerX, spec.centerY),
            spec.radius.toFloat(),
            spec.stops.map { it.at }.toFloatArray(),
            spec.stops.map { Color(it.argb, true) }.toTypedArray(),
            MultipleGradientPaint.CycleMethod.NO_CYCLE
        )
        g.fillRect(0, 0, DIAL_SIZE, DIAL_SIZE)
    }
}
