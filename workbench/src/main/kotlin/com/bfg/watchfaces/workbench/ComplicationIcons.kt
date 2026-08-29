package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.ComplicationGlyphs
import com.bfg.watchfaces.generator.ComplicationSource
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D

/**
 * Draws the glyph a complication shows above its value, in AWT.
 *
 * On the watch this is `[COMPLICATION.MONOCHROMATIC_IMAGE]` -- the icon the
 * SYSTEM PROVIDER supplies, drawn by the WFF runtime. These are stand-ins with
 * the same silhouette and weight as the Wear/Material icons for the same data,
 * so the preview shows the right shape in the right place at the right size.
 *
 * They are not the shipped icons -- the watch draws the provider's own -- but
 * they DO reach the APK, because `preview.png` is built from this and
 * `watch_face_info.xml` requires it. (This file used to claim they never reach
 * the APK. That was true of `dial_bg.png` and false of the preview.) Their job
 * is to answer "does the layout survive an icon of about this size", which is
 * the only question a preview can honestly answer about a provider that is not
 * running.
 *
 * ## The shapes are not here any more
 *
 * They live in [ComplicationGlyphs], in `:generator`, for the same reason
 * `EngravedStroke` and `DialShading` do: `:mobile` draws these too, and a
 * second hand-transcribed copy of ninety coordinates would drift. This object
 * is now only the AWT executor.
 */
object ComplicationIcons {

    fun draw(g: Graphics2D, source: ComplicationSource, x: Double, y: Double, size: Double, color: Color) {
        if (!source.enabled) return
        val old = g.transform
        val oldStroke = g.stroke
        g.translate(x, y)
        g.scale(size / ComplicationGlyphs.GRID, size / ComplicationGlyphs.GRID)
        g.color = color
        g.stroke = BasicStroke(
            ComplicationGlyphs.STROKE_WIDTH.toFloat(),
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND
        )

        for (shape in ComplicationGlyphs.shapes(source)) render(g, shape)

        g.transform = old
        g.stroke = oldStroke
    }

    private fun render(g: Graphics2D, shape: ComplicationGlyphs.Shape) {
        when (shape) {
            is ComplicationGlyphs.Shape.Oval -> {
                val e = Ellipse2D.Double(shape.x, shape.y, shape.w, shape.h)
                if (shape.fill) g.fill(e) else g.draw(e)
            }

            is ComplicationGlyphs.Shape.RoundRect -> {
                val r = RoundRectangle2D.Double(shape.x, shape.y, shape.w, shape.h, shape.rx, shape.ry)
                if (shape.fill) g.fill(r) else g.draw(r)
            }

            is ComplicationGlyphs.Shape.Line ->
                g.draw(Line2D.Double(shape.x1, shape.y1, shape.x2, shape.y2))

            // The glyphs are authored in AWT's angle convention, so this passes
            // them straight through. Android's drawArc negates both.
            is ComplicationGlyphs.Shape.Arc ->
                g.draw(Arc2D.Double(shape.x, shape.y, shape.w, shape.h, shape.startDeg, shape.extentDeg, Arc2D.OPEN))

            is ComplicationGlyphs.Shape.Curve -> {
                val p = Path2D.Double()
                p.moveTo(shape.start.x, shape.start.y)
                for (s in shape.segments) p.curveTo(s.c1.x, s.c1.y, s.c2.x, s.c2.y, s.to.x, s.to.y)
                if (shape.closed) p.closePath()
                if (shape.fill) g.fill(p) else g.draw(p)
            }

            is ComplicationGlyphs.Shape.Rotated -> {
                val t = g.transform
                g.translate(shape.cx, shape.cy)
                g.rotate(shape.radians)
                for (inner in shape.of) render(g, inner)
                g.transform = t
            }
        }
    }
}
