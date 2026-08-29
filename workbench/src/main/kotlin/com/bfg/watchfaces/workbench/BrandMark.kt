package com.bfg.watchfaces.workbench

import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The BFG Watch Faces app icon, described once.
 *
 * ## Why this is data and not four hand-drawn files
 *
 * The mark has to exist as an Android adaptive icon (two vector drawables on a
 * 108dp canvas), as a 512px PNG for Play, and as an SVG for the docs site. Drawn
 * separately those are four chances for the crown to sit at a different angle,
 * and nobody would notice until they were side by side on a phone. So the shapes
 * live here as [Part]s and each output is an executor -- the same arrangement
 * `ComplicationGlyphs` uses, and for the same reason.
 *
 * ## The geometry
 *
 * Mark space is 96x96, which is the grid the design was chosen on. The DIAL
 * centre is (43.5, 48), not (48, 48): the crown hangs off the right, and on a
 * centred dial the whole mark reads as sliding out of its frame. Shifting the
 * dial 4.5 units left puts the OPTICAL centre back in the middle. That offset is
 * the reason [enclosingCircle] exists rather than an assumption that the middle
 * of the canvas is the middle of the artwork.
 *
 * Angles are degrees CLOCKWISE FROM TWELVE, because that is how a watch is
 * described. Both executors convert; nothing here is in anyone else's convention.
 *
 * This is "H6 with the onion crown", chosen 2026-08-29. See `DECISIONS.md`.
 */
object BrandMark {

    const val GRID = 96.0

    /** The dial centre. Not the canvas centre -- see the class note. */
    const val CX = 43.5
    const val CY = 48.0

    /** The corner radius of the standalone tile, as a fraction of [GRID]. */
    const val TILE_RADIUS = 22.0

    data class Palette(val ground: String, val mark: String, val hand: String) {
        companion object {
            /** The launcher icon. Chosen over dark: a near-black tile disappears into a dark wallpaper. */
            val LIGHT = Palette(ground = "#F4E6EB", mark = "#80475C", hand = "#80475C")
            val DARK = Palette(ground = "#1C181A", mark = "#D09AAB", hand = "#F2DCE3")
        }
    }

    /** Which of the palette's two inks a part is drawn in. */
    enum class Ink { MARK, HAND }

    sealed interface Part {
        val ink: Ink

        /**
         * A stroked arc about the dial centre, running CLOCKWISE from [fromDeg]
         * to [toDeg]. Round caps, always: the open ends are the whole point of
         * the H6 dial and a butt cap makes them look sawn off.
         */
        data class Arc(
            val radius: Double,
            val fromDeg: Double,
            val toDeg: Double,
            val width: Double,
            val alpha: Double = 1.0,
            override val ink: Ink = Ink.MARK
        ) : Part

        /** A stroked segment with round caps. */
        data class Line(
            val x1: Double, val y1: Double,
            val x2: Double, val y2: Double,
            val width: Double,
            override val ink: Ink = Ink.MARK
        ) : Part

        /** A filled circle. */
        data class Dot(
            val cx: Double, val cy: Double, val radius: Double,
            override val ink: Ink = Ink.MARK
        ) : Part
    }

    /** A point on the mark at [radius] from the dial centre, [deg] clockwise from twelve. */
    fun polar(radius: Double, deg: Double): Pair<Double, Double> {
        val a = Math.toRadians(deg - 90.0)
        return (CX + radius * cos(a)) to (CY + radius * sin(a))
    }

    // The crown: a domed "onion" on a slim stem at two o'clock. Set at 75 rather
    // than 90 so it clears the minute hand, which points at 45.
    private const val CROWN_DEG = 75.0
    private const val OUTER_RING = 30.5

    // The stem starts ON the outer ring, not inside it. Started inside, the ring
    // covers the stem and the crown reads as a balloon on a string -- which is
    // exactly what the first render looked like at 512px and not at 96.
    private const val CROWN_STEM_OUTER = 37.2
    private const val CROWN_BALL = 3.6

    // Ten past ten. The pose is not decoration: hands within a few degrees of
    // symmetric, at nearly equal length, stop reading as a watch and start
    // reading as the letter V. The hour hand is deliberately two thirds of the
    // minute hand and heavier, which is what separates them at 48px.
    private const val HOUR_DEG = 315.0
    private const val HOUR_LEN = 11.5
    private const val MINUTE_DEG = 45.0
    private const val MINUTE_LEN = 18.5

    val parts: List<Part> = buildList {
        // The dial: two open arcs, the gap toward the lower left.
        // Heavier than the artboards, which were judged at 120px. Inside a 60dp
        // keyline a 2.6-unit stroke lands on 1.4 device pixels at a 48px launcher
        // icon and the whole mark goes grey. These weights hold at 48 and still
        // read as fine engraving at 512.
        add(Part.Arc(radius = OUTER_RING, fromDeg = 200.0, toDeg = 130.0, width = 3.4, alpha = 0.85))
        add(Part.Arc(radius = 22.0, fromDeg = 210.0, toDeg = 120.0, width = 2.4, alpha = 0.85))

        val (sx, sy) = polar(OUTER_RING, CROWN_DEG)
        val (bx, by) = polar(CROWN_STEM_OUTER, CROWN_DEG)
        add(Part.Line(sx, sy, bx, by, width = 3.0))
        add(Part.Dot(bx, by, CROWN_BALL))

        val (hx, hy) = polar(HOUR_LEN, HOUR_DEG)
        val (mx, my) = polar(MINUTE_LEN, MINUTE_DEG)
        add(Part.Line(CX, CY, hx, hy, width = 4.2, ink = Ink.HAND))
        add(Part.Line(CX, CY, mx, my, width = 3.0, ink = Ink.HAND))
        add(Part.Dot(CX, CY, 3.0, ink = Ink.HAND))
    }

    /**
     * A point the artwork actually covers, and how far the ink spreads past it.
     *
     * Stroke width is half the story of where a mark ENDS, and a bounding box
     * built from centrelines is wrong by up to 1.8 units here -- enough to push
     * the crown outside the adaptive-icon safe zone without any test noticing.
     */
    data class Sample(val x: Double, val y: Double, val pad: Double)

    /** Every part, sampled densely enough that an arc is not mistaken for its chord. */
    fun samples(): List<Sample> = buildList {
        for (part in parts) when (part) {
            is Part.Arc -> {
                val sweep = sweepOf(part.fromDeg, part.toDeg)
                val steps = maxOf(8, (sweep / 3.0).toInt())
                for (i in 0..steps) {
                    val (x, y) = polar(part.radius, part.fromDeg + sweep * i / steps)
                    add(Sample(x, y, part.width / 2))
                }
            }

            is Part.Line -> {
                add(Sample(part.x1, part.y1, part.width / 2))
                add(Sample(part.x2, part.y2, part.width / 2))
            }

            is Part.Dot -> add(Sample(part.cx, part.cy, part.radius))
        }
    }

    /** Degrees swept clockwise from [fromDeg] to [toDeg], in `(0, 360]`. */
    fun sweepOf(fromDeg: Double, toDeg: Double): Double {
        val d = ((toDeg - fromDeg) % 360.0 + 360.0) % 360.0
        return if (d == 0.0) 360.0 else d
    }

    data class Circle(val cx: Double, val cy: Double, val radius: Double)

    /**
     * The smallest circle containing every stroke of the mark.
     *
     * Every output centres on THIS rather than on the canvas, so a launcher's
     * circular mask crops the artwork evenly instead of clipping the crown.
     *
     * Approximated by moving the centre toward whichever point currently sticks
     * out furthest, with a shrinking step -- converges to within a fraction of a
     * unit in far fewer lines than an exact minimal-enclosing-circle, and
     * `BrandMarkTest` checks the result covers everything rather than trusting it.
     */
    fun enclosingCircle(): Circle {
        val pts = samples()
        var cx = pts.sumOf { it.x } / pts.size
        var cy = pts.sumOf { it.y } / pts.size
        for (i in 1..600) {
            val far = pts.maxBy { hypot(it.x - cx, it.y - cy) + it.pad }
            val step = 1.0 / (i + 1)
            cx += (far.x - cx) * step
            cy += (far.y - cy) * step
        }
        val r = pts.maxOf { hypot(it.x - cx, it.y - cy) + it.pad }
        return Circle(cx, cy, r)
    }

    /** Mark space -> output space: scale about the mark's own centre, then translate. */
    data class Fit(val scale: Double, val dx: Double, val dy: Double) {
        fun x(v: Double) = v * scale + dx
        fun y(v: Double) = v * scale + dy
        fun len(v: Double) = v * scale
    }

    /**
     * Place the mark on a [canvas]-unit square so that its enclosing circle has
     * exactly [diameter] units and sits in the middle.
     */
    fun fitInto(canvas: Double, diameter: Double): Fit {
        val c = enclosingCircle()
        val scale = diameter / (c.radius * 2)
        return Fit(scale, dx = canvas / 2 - c.cx * scale, dy = canvas / 2 - c.cy * scale)
    }

    /** Place the mark's enclosing circle at ([cx], [cy]) with the given [diameter]. */
    fun fitAt(cx: Double, cy: Double, diameter: Double): Fit {
        val c = enclosingCircle()
        val scale = diameter / (c.radius * 2)
        return Fit(scale, dx = cx - c.cx * scale, dy = cy - c.cy * scale)
    }

    /** The mark at its authored size and position -- what the standalone tile uses. */
    val NATIVE = Fit(1.0, 0.0, 0.0)

    // ---- path data, shared by SVG and Android VectorDrawable ----------------
    //
    // The two formats disagree about elements (VectorDrawable has no <circle>
    // and no <line>) but agree exactly about path data, so everything becomes a
    // path and only the wrapper differs.

    fun pathData(part: Part, fit: Fit): String = when (part) {
        is Part.Arc -> {
            val (x0, y0) = polar(part.radius, part.fromDeg)
            val (x1, y1) = polar(part.radius, part.toDeg)
            val large = if (sweepOf(part.fromDeg, part.toDeg) > 180.0) 1 else 0
            val r = fit.len(part.radius)
            "M${n(fit.x(x0))} ${n(fit.y(y0))} A${n(r)} ${n(r)} 0 $large 1 ${n(fit.x(x1))} ${n(fit.y(y1))}"
        }

        is Part.Line ->
            "M${n(fit.x(part.x1))} ${n(fit.y(part.y1))} L${n(fit.x(part.x2))} ${n(fit.y(part.y2))}"

        // A circle is two half-arcs. `Z` matters: an unclosed fill is undefined
        // in VectorDrawable and renders as a wedge on some devices.
        is Part.Dot -> {
            val cx = fit.x(part.cx)
            val cy = fit.y(part.cy)
            val r = fit.len(part.radius)
            "M${n(cx - r)} ${n(cy)} A${n(r)} ${n(r)} 0 1 0 ${n(cx + r)} ${n(cy)} " +
                "A${n(r)} ${n(r)} 0 1 0 ${n(cx - r)} ${n(cy)} Z"
        }
    }

    fun isFilled(part: Part) = part is Part.Dot

    fun strokeWidth(part: Part, fit: Fit): Double = when (part) {
        is Part.Arc -> fit.len(part.width)
        is Part.Line -> fit.len(part.width)
        is Part.Dot -> 0.0
    }

    fun alphaOf(part: Part): Double = if (part is Part.Arc) part.alpha else 1.0

    fun colorOf(part: Part, palette: Palette): String =
        if (part.ink == Ink.HAND) palette.hand else palette.mark

    /** Two decimals, always with a '.', because a Turkish locale writes `2,60` and aapt2 rejects it. */
    fun n(v: Double): String {
        var s = String.format(Locale.ROOT, "%.2f", v)
        // Only inside the fraction: "10.00" trimmed blindly of zeros becomes "1".
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }
}
