package com.bfg.watchfaces.generator

/**
 * The complication icons, described once as geometry.
 *
 * Same reasoning as [EngravedStroke] and [DialShading], and the same division
 * of labour: WHAT to draw is decided here and testable on the JVM; each
 * platform only executes it. AWT and Android Canvas disagree about almost every
 * spelling — `Arc2D` versus `drawArc`, `RoundRectangle2D` versus `drawRoundRect`
 * — and none of those differences should be able to change what an icon looks
 * like.
 *
 * Before this, the icons existed only in the workbench as AWT calls. Porting
 * them to Android meant transcribing ninety lines of coordinates by hand into a
 * second renderer, which is exactly the shape that "start identical and drift"
 * describes. `preview.png` ships, so a drifted icon would go out in every APK.
 *
 * ## The grid
 *
 * Everything is authored on a 24x24 box, the Material outlined convention, and
 * scaled by the caller. [STROKE_WIDTH] is the outline weight on that grid.
 */
object ComplicationGlyphs {

    /** Icons are authored on a 24x24 box and scaled to the slot. */
    const val GRID = 24.0

    /** 1.8 on a 24-grid is the Material outlined weight. */
    const val STROKE_WIDTH = 1.8

    /** A point on the grid. */
    data class Pt(val x: Double, val y: Double)

    /** One cubic segment: two control points and an end point. */
    data class Cubic(val c1: Pt, val c2: Pt, val to: Pt)

    /**
     * One primitive.
     *
     * [Shape.fill] distinguishes a solid from an outline. Anything not filled is
     * stroked at [STROKE_WIDTH] with round caps and joins.
     */
    sealed interface Shape {
        val fill: Boolean

        data class Oval(
            val x: Double, val y: Double, val w: Double, val h: Double,
            override val fill: Boolean
        ) : Shape

        data class RoundRect(
            val x: Double, val y: Double, val w: Double, val h: Double,
            val rx: Double, val ry: Double,
            override val fill: Boolean
        ) : Shape

        data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : Shape {
            override val fill: Boolean get() = false
        }

        /**
         * An open arc, in AWT's convention: degrees counter-clockwise from 3
         * o'clock, on a y-down box.
         *
         * Stated because the two platforms differ and a renderer has to convert.
         * Android's `drawArc` measures clockwise, so it negates both angles. The
         * convention is AWT's only because that is where these shapes were
         * authored and the preview they produce is pinned byte-for-byte.
         */
        data class Arc(
            val x: Double, val y: Double, val w: Double, val h: Double,
            val startDeg: Double, val extentDeg: Double
        ) : Shape {
            override val fill: Boolean get() = false
        }

        data class Curve(
            val start: Pt, val segments: List<Cubic>,
            val closed: Boolean, override val fill: Boolean
        ) : Shape

        /** A group rotated about a point — only the steps glyph needs it. */
        data class Rotated(
            val cx: Double, val cy: Double, val radians: Double,
            val of: List<Shape>
        ) : Shape {
            override val fill: Boolean get() = false
        }
    }

    /** What to draw for a source. Empty when the slot shows nothing. */
    fun shapes(source: ComplicationSource): List<Shape> = when (source) {
        // A drawn source has no glyph: the icons come from the provider's
        // MONOCHROMATIC_IMAGE and a drawn source has no provider. The value is
        // centred in its box instead.
        ComplicationSource.NONE,
        ComplicationSource.WEATHER_TEMPERATURE,
        ComplicationSource.WEATHER_CONDITION,
        ComplicationSource.WEATHER_TEMP_CONDITION -> emptyList()
        ComplicationSource.STEP_COUNT -> steps()
        ComplicationSource.HEART_RATE -> heart()
        ComplicationSource.WATCH_BATTERY -> battery()
        ComplicationSource.DAY_AND_DATE,
        ComplicationSource.DATE,
        ComplicationSource.DAY_OF_WEEK -> calendar(dot = false)
        ComplicationSource.NEXT_EVENT -> calendar(dot = true)
        ComplicationSource.WORLD_CLOCK,
        ComplicationSource.TIME_AND_DATE -> clock()
        ComplicationSource.SUNRISE_SUNSET -> sunrise()
        ComplicationSource.UNREAD_NOTIFICATION_COUNT -> bell()
        ComplicationSource.APP_SHORTCUT,
        ComplicationSource.SHORTCUT_APP -> apps()
        ComplicationSource.SHORTCUT_MUSIC -> note()
        ComplicationSource.SHORTCUT_ALARM -> alarm()
        ComplicationSource.SHORTCUT_SETTINGS -> gear()
        ComplicationSource.SHORTCUT_PHONE -> handset()
        ComplicationSource.SHORTCUT_CALENDAR -> calendar(dot = false)
        ComplicationSource.SHORTCUT_MESSAGES -> speechBubble()
        ComplicationSource.FAVORITE_CONTACT -> person()
    }

    /** Two footprints, offset, like the Wear steps glyph. */
    private fun steps(): List<Shape> = listOf(
        foot(8.0, 8.5, -0.18),
        foot(16.0, 14.0, 0.18)
    )

    private fun foot(cx: Double, cy: Double, lean: Double) = Shape.Rotated(
        cx, cy, lean,
        listOf(
            Shape.Oval(-2.6, -4.0, 5.2, 7.2, fill = true),
            Shape.Oval(-2.2, 3.0, 4.4, 3.0, fill = true)
        )
    )

    private fun heart(): List<Shape> = listOf(
        Shape.Curve(
            start = Pt(12.0, 20.0),
            segments = listOf(
                Cubic(Pt(2.0, 13.5), Pt(3.0, 6.0), Pt(7.8, 5.0)),
                Cubic(Pt(10.2, 4.5), Pt(11.6, 6.2), Pt(12.0, 7.4)),
                Cubic(Pt(12.4, 6.2), Pt(13.8, 4.5), Pt(16.2, 5.0)),
                Cubic(Pt(21.0, 6.0), Pt(22.0, 13.5), Pt(12.0, 20.0))
            ),
            closed = true, fill = true
        )
    )

    private fun battery(): List<Shape> = listOf(
        Shape.RoundRect(3.0, 8.0, 16.0, 8.0, 2.2, 2.2, fill = false),
        Shape.RoundRect(20.0, 10.4, 1.8, 3.2, 0.9, 0.9, fill = true),
        Shape.RoundRect(4.6, 9.6, 9.0, 4.8, 1.2, 1.2, fill = true)
    )

    private fun calendar(dot: Boolean): List<Shape> = buildList {
        add(Shape.RoundRect(3.5, 5.0, 17.0, 15.5, 2.4, 2.4, fill = false))
        add(Shape.Line(3.5, 9.6, 20.5, 9.6))
        add(Shape.Line(8.0, 3.0, 8.0, 6.4))
        add(Shape.Line(16.0, 3.0, 16.0, 6.4))
        if (dot) add(Shape.Oval(10.6, 13.0, 3.0, 3.0, fill = true))
    }

    private fun clock(): List<Shape> = listOf(
        Shape.Oval(3.2, 3.2, 17.6, 17.6, fill = false),
        Shape.Line(12.0, 7.4, 12.0, 12.0),
        Shape.Line(12.0, 12.0, 15.6, 14.2)
    )

    private fun sunrise(): List<Shape> = listOf(
        Shape.Arc(6.5, 8.0, 11.0, 11.0, 0.0, 180.0),
        Shape.Line(2.5, 19.0, 21.5, 19.0),
        // rays
        Shape.Line(12.0, 2.6, 12.0, 5.2),
        Shape.Line(4.6, 6.0, 6.4, 7.6),
        Shape.Line(19.4, 6.0, 17.6, 7.6)
    )

    private fun bell(): List<Shape> = listOf(
        Shape.Curve(
            start = Pt(6.0, 16.5),
            segments = listOf(
                Cubic(Pt(6.0, 12.0), Pt(6.2, 7.0), Pt(12.0, 7.0)),
                Cubic(Pt(17.8, 7.0), Pt(18.0, 12.0), Pt(18.0, 16.5))
            ),
            closed = true, fill = false
        ),
        Shape.Line(4.4, 16.5, 19.6, 16.5),
        Shape.Line(12.0, 4.4, 12.0, 6.6),
        Shape.Arc(10.0, 17.2, 4.0, 3.4, 200.0, 140.0)
    )

    private fun apps(): List<Shape> = buildList {
        for (cx in listOf(7.0, 15.0)) for (cy in listOf(7.0, 15.0)) {
            add(Shape.RoundRect(cx - 3.2, cy - 3.2, 6.4, 6.4, 1.8, 1.8, fill = false))
        }
    }

    /** A quaver: a filled head and a stem with a flag. */
    private fun note(): List<Shape> = listOf(
        Shape.Oval(4.0, 15.0, 6.0, 5.0, fill = true),
        Shape.Line(10.0, 17.5, 10.0, 5.0),
        // Lines and arcs only: these glyphs are also emitted as WFF draw
        // primitives, and the format has Line, Ellipse, Rect, RoundRect and
        // Arc but no cubic. A shape a watch cannot draw is not a shape.
        Shape.Line(10.0, 5.0, 18.0, 7.5),
        Shape.Line(10.0, 9.0, 18.0, 11.5)
    )

    /** A clock face with the two bell feet an alarm has. */
    private fun alarm(): List<Shape> = listOf(
        Shape.Oval(4.5, 5.5, 15.0, 15.0, fill = false),
        Shape.Line(12.0, 9.5, 12.0, 13.0),
        Shape.Line(12.0, 13.0, 14.5, 14.5),
        Shape.Line(3.0, 5.0, 6.5, 2.5),
        Shape.Line(21.0, 5.0, 17.5, 2.5)
    )

    /** A cog: a ring, a hub, and four teeth on the axes. */
    private fun gear(): List<Shape> = buildList {
        add(Shape.Oval(6.0, 6.0, 12.0, 12.0, fill = false))
        add(Shape.Oval(10.0, 10.0, 4.0, 4.0, fill = false))
        add(Shape.Line(12.0, 2.5, 12.0, 5.5))
        add(Shape.Line(12.0, 18.5, 12.0, 21.5))
        add(Shape.Line(2.5, 12.0, 5.5, 12.0))
        add(Shape.Line(18.5, 12.0, 21.5, 12.0))
    }

    /** A handset, drawn as the familiar diagonal. */
    private fun handset(): List<Shape> = listOf(
        Shape.Arc(2.0, 2.0, 20.0, 20.0, 180.0, -110.0),
        Shape.Line(20.0, 15.0, 15.5, 14.0),
        Shape.Line(15.5, 14.0, 13.5, 16.0),
        Shape.Line(9.0, 5.0, 5.0, 4.0),
        Shape.Line(9.0, 5.0, 10.0, 9.5)
    )

    /** A rounded rectangle with a tail: a message. */
    private fun speechBubble(): List<Shape> = listOf(
        Shape.RoundRect(3.5, 5.0, 17.0, 12.0, 3.0, 3.0, fill = false),
        Shape.Line(8.0, 17.0, 8.0, 21.0),
        Shape.Line(8.0, 21.0, 12.5, 17.0)
    )

    private fun person(): List<Shape> = listOf(
        Shape.Oval(8.2, 4.0, 7.6, 7.6, fill = false),
        Shape.Arc(4.4, 13.0, 15.2, 14.0, 0.0, 180.0)
    )
}
