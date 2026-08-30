package com.bfg.watchfaces.generator

import kotlin.math.roundToInt

/**
 * A glyph, as Watch Face Format draw primitives.
 *
 * ## Why not a PNG
 *
 * The complication glyphs on this face come from the provider, as
 * `[COMPLICATION.MONOCHROMATIC_IMAGE]`. A SHORTCUT has no provider, so it has no
 * icon — and the obvious answer, baking a PNG per shortcut into the APK, would
 * mean rasterising the same shapes twice more (once in the workbench, once on
 * the phone) and shipping bytes for something the format can draw itself.
 *
 * `PartDraw` has Line, Ellipse, Rectangle, RoundRectangle and Arc. Every shape
 * in [ComplicationGlyphs] except a cubic maps onto one, so the glyphs go over
 * as vectors. That is also why the two shortcut glyphs that wanted curves were
 * redrawn: a shape a watch cannot draw is not a shape.
 *
 * Coordinates are local to the `PartDraw` box, which the caller sizes and
 * places, so this only has to scale the 24-grid the glyphs are authored on.
 */
object GlyphWff {

    /**
     * The draw elements for [shapes], scaled to a [size] box.
     *
     * [color] is 8-digit AARRGGBB, as everywhere else in the emitted file.
     */
    fun elements(shapes: List<ComplicationGlyphs.Shape>, size: Int, color: String): String {
        val k = size / ComplicationGlyphs.GRID
        val thickness = ComplicationGlyphs.STROKE_WIDTH * k
        fun n(v: Double) = ((v * k) * 10).roundToInt() / 10.0
        val stroke = """<Stroke color="$color" thickness="${(thickness * 10).roundToInt() / 10.0}" cap="ROUND"/>"""
        val fill = """<Fill color="$color"/>"""

        return shapes.mapNotNull { shape ->
            when (shape) {
                is ComplicationGlyphs.Shape.Line ->
                    """<Line startX="${n(shape.x1)}" startY="${n(shape.y1)}" """ +
                        """endX="${n(shape.x2)}" endY="${n(shape.y2)}">$stroke</Line>"""

                is ComplicationGlyphs.Shape.Oval ->
                    """<Ellipse x="${n(shape.x)}" y="${n(shape.y)}" """ +
                        """width="${n(shape.w)}" height="${n(shape.h)}">""" +
                        (if (shape.fill) fill else stroke) + "</Ellipse>"

                is ComplicationGlyphs.Shape.RoundRect ->
                    """<RoundRectangle x="${n(shape.x)}" y="${n(shape.y)}" """ +
                        """width="${n(shape.w)}" height="${n(shape.h)}" """ +
                        """cornerRadiusX="${n(shape.rx)}" cornerRadiusY="${n(shape.ry)}">""" +
                        (if (shape.fill) fill else stroke) + "</RoundRectangle>"

                // Arc is the one shape whose geometry differs: the format
                // takes a CENTRE, where the glyphs are authored with a bounding
                // box. And its angles run clockwise from 12 o'clock, where the
                // glyphs use AWT's counter-clockwise from 3 -- see
                // ComplicationGlyphs.Shape.Arc, which says so for exactly this
                // reason.
                is ComplicationGlyphs.Shape.Arc -> {
                    val cx = shape.x + shape.w / 2
                    val cy = shape.y + shape.h / 2
                    val start = 90.0 - shape.startDeg
                    """<Arc centerX="${n(cx)}" centerY="${n(cy)}" """ +
                        """width="${n(shape.w)}" height="${n(shape.h)}" """ +
                        """startAngle="$start" endAngle="${start - shape.extentDeg}">""" +
                        "$stroke</Arc>"
                }

                // A cubic has no equivalent in the format. Dropping it silently
                // would ship a glyph missing a stroke, so nothing that reaches
                // here may contain one -- asserted by a test over every source.
                else -> null
            }
        }.joinToString("\n          ")
    }

    /** True when every shape in [shapes] can be drawn by the format. */
    fun canDraw(shapes: List<ComplicationGlyphs.Shape>): Boolean =
        shapes.none { it is ComplicationGlyphs.Shape.Curve }
}
