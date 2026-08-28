package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.DIAL_CENTER
import com.bfg.watchfaces.generator.DIAL_RADIUS
import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.EngravedStroke
import com.bfg.watchfaces.generator.PatternEngines
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
        else drawSheen(g, dial, p.sheen)

        if (texture == null && procedural == null) drawPattern(g, p, dial, ink)
        drawVignette(g, p.vignette)

        g.dispose()
        return img
    }

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
        drawSheen(g, hex(p.dialColor), p.sheen * 0.5)
    }

    /**
     * Shade a generated height field into the dial.
     *
     * The field is built into an array ONCE and the surface normal comes from
     * neighbouring cells. Re-sampling the field for each gradient would be four
     * extra fBm evaluations per pixel -- roughly five times the work for the
     * same picture, and a preview that stutters while a slider moves.
     *
     * Lighting is a cheap directional bump: the field's slope brightens or
     * darkens the dial colour. That is what makes brushed metal look brushed
     * rather than merely noisy, and it is the same top-left key light the
     * stroked engines emboss against, so the two families sit together.
     */
    private fun drawProcedural(
        g: java.awt.Graphics2D,
        kind: TextureField.Kind,
        p: DialParams,
        dial: Color,
        size: Int
    ) {
        val s = size.toDouble() / DIAL_SIZE
        val w = size
        val field = DoubleArray(w * w)
        for (y in 0 until w) {
            val dy = y / s
            for (x in 0 until w) {
                field[y * w + x] = TextureField.sample(kind, x / s, dy, p)
            }
        }

        val amp = (p.contrast / 100.0).coerceIn(0.0, 1.0)
        val relief = (p.relief / 6.0).coerceIn(0.0, 1.0)
        val img = BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB)
        val px = IntArray(w * w)

        for (y in 0 until w) {
            for (x in 0 until w) {
                val i = y * w + x
                val h = field[i]
                // Central differences, clamped at the edges.
                val l = field[i - if (x > 0) 1 else 0]
                val r = field[i + if (x < w - 1) 1 else 0]
                val u = field[i - if (y > 0) w else 0]
                val d = field[i + if (y < w - 1) w else 0]
                val slope = ((l - r) + (u - d)) * 0.5

                // Height gives the base tone, slope gives the lit edge.
                val t = (h - 0.5) * amp * 0.55 + slope * relief * 6.0
                px[i] = 0xFF shl 24 or
                    (shift(dial.red, t) shl 16) or (shift(dial.green, t) shl 8) or shift(dial.blue, t)
            }
        }
        img.setRGB(0, 0, w, w, px, 0, w)

        val old = g.transform
        g.transform = AffineTransform()   // px are already at render scale
        g.clip(Ellipse2D.Double(0.0, 0.0, size.toDouble(), size.toDouble()))
        g.drawImage(img, 0, 0, null)
        g.transform = old
        drawSheen(g, dial, p.sheen)
    }

    /** Lighten or darken a channel by t in roughly [-1, 1]. */
    private fun shift(c: Int, t: Double): Int {
        // Toward white when lifting, toward black when darkening, so the same t
        // reads as the same amount of light on any dial colour.
        val room = if (t >= 0) (255 - c).toDouble() else c.toDouble()
        return (c + t * room).toInt().coerceIn(0, 255)
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
