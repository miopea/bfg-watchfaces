package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.ComplicationSource
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

/**
 * The monochrome glyph a complication shows above its value.
 *
 * On the watch this is `[COMPLICATION.MONOCHROMATIC_IMAGE]` -- the icon the
 * SYSTEM PROVIDER supplies, drawn by the WFF runtime. These are stand-ins with
 * the same silhouette and weight as the Wear/Material icons for the same data,
 * so the preview shows the right shape in the right place at the right size.
 *
 * They are not the shipped assets and never reach the APK: nothing here is
 * baked into dial_bg.png. Like the sample text, their job is to answer "does
 * the layout survive an icon of about this size", which is the only question a
 * preview can honestly answer about a provider that is not running.
 *
 * Authored on a 24x24 grid and scaled to the slot, the same way Material icons
 * are, so weights stay consistent across sizes.
 */
object ComplicationIcons {

    fun draw(g: Graphics2D, source: ComplicationSource, x: Double, y: Double, size: Double, color: Color) {
        if (!source.enabled) return
        val old = g.transform
        val oldStroke = g.stroke
        g.translate(x, y)
        g.scale(size / 24.0, size / 24.0)
        g.color = color
        // 1.8 on a 24-grid is the Material outlined weight.
        g.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        when (source) {
            ComplicationSource.STEP_COUNT -> steps(g)
            ComplicationSource.HEART_RATE -> heart(g)
            ComplicationSource.WATCH_BATTERY -> battery(g)
            ComplicationSource.DAY_AND_DATE, ComplicationSource.DATE,
            ComplicationSource.DAY_OF_WEEK -> calendar(g, dot = false)
            ComplicationSource.NEXT_EVENT -> calendar(g, dot = true)
            ComplicationSource.WORLD_CLOCK -> clock(g)
            ComplicationSource.SUNRISE_SUNSET -> sunrise(g)
            ComplicationSource.UNREAD_NOTIFICATION_COUNT -> bell(g)
            ComplicationSource.APP_SHORTCUT -> apps(g)
            ComplicationSource.FAVORITE_CONTACT -> person(g)
            ComplicationSource.NONE -> {}
        }

        g.transform = old
        g.stroke = oldStroke
    }

    /** Two footprints, offset, like the Wear steps glyph. */
    private fun steps(g: Graphics2D) {
        fun foot(cx: Double, cy: Double, lean: Double) {
            val t = g.transform
            g.translate(cx, cy); g.rotate(lean)
            g.fill(Ellipse2D.Double(-2.6, -4.0, 5.2, 7.2))
            g.fill(Ellipse2D.Double(-2.2, 3.0, 4.4, 3.0))
            g.transform = t
        }
        foot(8.0, 8.5, -0.18)
        foot(16.0, 14.0, 0.18)
    }

    private fun heart(g: Graphics2D) {
        val p = Path2D.Double()
        p.moveTo(12.0, 20.0)
        p.curveTo(2.0, 13.5, 3.0, 6.0, 7.8, 5.0)
        p.curveTo(10.2, 4.5, 11.6, 6.2, 12.0, 7.4)
        p.curveTo(12.4, 6.2, 13.8, 4.5, 16.2, 5.0)
        p.curveTo(21.0, 6.0, 22.0, 13.5, 12.0, 20.0)
        p.closePath()
        g.fill(p)
    }

    private fun battery(g: Graphics2D) {
        g.draw(RoundRectangle2D.Double(3.0, 8.0, 16.0, 8.0, 2.2, 2.2))
        g.fill(RoundRectangle2D.Double(20.0, 10.4, 1.8, 3.2, 0.9, 0.9))
        g.fill(RoundRectangle2D.Double(4.6, 9.6, 9.0, 4.8, 1.2, 1.2))
    }

    private fun calendar(g: Graphics2D, dot: Boolean) {
        g.draw(RoundRectangle2D.Double(3.5, 5.0, 17.0, 15.5, 2.4, 2.4))
        g.draw(java.awt.geom.Line2D.Double(3.5, 9.6, 20.5, 9.6))
        g.draw(java.awt.geom.Line2D.Double(8.0, 3.0, 8.0, 6.4))
        g.draw(java.awt.geom.Line2D.Double(16.0, 3.0, 16.0, 6.4))
        if (dot) g.fill(Ellipse2D.Double(10.6, 13.0, 3.0, 3.0))
    }

    private fun clock(g: Graphics2D) {
        g.draw(Ellipse2D.Double(3.2, 3.2, 17.6, 17.6))
        g.draw(java.awt.geom.Line2D.Double(12.0, 7.4, 12.0, 12.0))
        g.draw(java.awt.geom.Line2D.Double(12.0, 12.0, 15.6, 14.2))
    }

    private fun sunrise(g: Graphics2D) {
        g.draw(Arc2D.Double(6.5, 8.0, 11.0, 11.0, 0.0, 180.0, Arc2D.OPEN))
        g.draw(java.awt.geom.Line2D.Double(2.5, 19.0, 21.5, 19.0))
        // rays
        g.draw(java.awt.geom.Line2D.Double(12.0, 2.6, 12.0, 5.2))
        g.draw(java.awt.geom.Line2D.Double(4.6, 6.0, 6.4, 7.6))
        g.draw(java.awt.geom.Line2D.Double(19.4, 6.0, 17.6, 7.6))
    }

    private fun bell(g: Graphics2D) {
        val p = Path2D.Double()
        p.moveTo(6.0, 16.5)
        p.curveTo(6.0, 12.0, 6.2, 7.0, 12.0, 7.0)
        p.curveTo(17.8, 7.0, 18.0, 12.0, 18.0, 16.5)
        p.closePath()
        g.draw(p)
        g.draw(java.awt.geom.Line2D.Double(4.4, 16.5, 19.6, 16.5))
        g.draw(java.awt.geom.Line2D.Double(12.0, 4.4, 12.0, 6.6))
        g.draw(Arc2D.Double(10.0, 17.2, 4.0, 3.4, 200.0, 140.0, Arc2D.OPEN))
    }

    private fun apps(g: Graphics2D) {
        for (cx in listOf(7.0, 15.0)) for (cy in listOf(7.0, 15.0)) {
            g.draw(RoundRectangle2D.Double(cx - 3.2, cy - 3.2, 6.4, 6.4, 1.8, 1.8))
        }
    }

    private fun person(g: Graphics2D) {
        g.draw(Ellipse2D.Double(8.2, 4.0, 7.6, 7.6))
        g.draw(Arc2D.Double(4.4, 13.0, 15.2, 14.0, 0.0, 180.0, Arc2D.OPEN))
    }
}
