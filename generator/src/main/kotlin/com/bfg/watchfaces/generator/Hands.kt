package com.bfg.watchfaces.generator

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Which hands a face wears.
 *
 * A style owns its hands AND its indices together, deliberately. Letting people
 * mix any hand with any chapter ring gives more combinations and more ways to
 * assemble something that does not look like a watch — and the app already has
 * a lot of controls. Picking "Baton" gets baton hands and baton indices, which
 * agree because one person decided they should.
 */
enum class HandStyle(val label: String) {
    /** Plain rectangles, square ends. Reads at a glance; the workhorse. */
    BATON("Baton"),

    /** Faceted, tapering to a point. The dress-watch hand. */
    DAUPHINE("Dauphine"),

    /** Slim stem opening into a hollow lozenge near the tip. */
    SYRINGE("Syringe"),

    /** Outline only, so the dial pattern shows through the hand. */
    SKELETON("Skeleton")
}

/**
 * Hand and index geometry. Pure functions: style in, polylines out.
 *
 * Shaped exactly like [PatternEngines], and for the same reasons: no Canvas, no
 * Graphics2D, no Android, so it runs in CI in milliseconds and the same code
 * drives the live preview and the shipped PNG. See `docs/specs/analog-hands.md`.
 *
 * ## Everything points at twelve
 *
 * Every shape here is emitted pointing straight UP from the dial centre, and
 * nothing in this file knows what time it is. Watch Face Format rotates the
 * images itself — `HourHand` turns 360° in twelve hours — so a hand that
 * arrived pre-rotated would be rotated twice.
 *
 * ## Why outlines rather than filled shapes
 *
 * Renderers here stroke polylines; that is what makes the engraved look, and it
 * is the only drawing primitive the pipeline has. So a hand is described as its
 * OUTLINE, closed, and [HandShape.filled] says whether the renderer should also
 * fill it. Keeping that decision here rather than in the renderer matters for
 * the same reason [SlotGeometry] exists: two renderers deciding independently
 * is two faces from one definition.
 *
 * [HandStyle.SKELETON] is the case that proves the flag earns its keep — it is
 * exactly the same outlines with `filled = false`.
 */
object Hands {

    /** Which hand, since each is a different length and weight. */
    enum class Hand { HOUR, MINUTE, SECOND }

    /**
     * One closed outline, and whether it is filled.
     *
     * @param outline closed: the last point equals the first, so a renderer can
     *   stroke it without special-casing the join.
     * @param filled whether to fill before stroking. False leaves the dial
     *   pattern visible through the hand.
     */
    data class HandShape(val outline: Polyline, val filled: Boolean) {
        init {
            require(outline.size >= 4) { "an outline needs at least a triangle plus its close" }
            require(outline.first() == outline.last()) { "outline is not closed" }
        }
    }

    /**
     * How long each hand runs, as a fraction of the dial RADIUS.
     *
     * The minute hand reaches the chapter ring and the hour hand stops well
     * short of it, because that difference is the whole of how the two are told
     * apart at a glance — far more than width. The second hand is longest.
     *
     * Not configurable. These are proportions people read without knowing they
     * are reading them, and a control that lets someone make the hour hand
     * longer than the minute hand produces a watch nobody can tell the time on.
     */
    private fun reach(hand: Hand): Double = when (hand) {
        Hand.HOUR -> 0.52
        Hand.MINUTE -> 0.76
        Hand.SECOND -> 0.84
    }

    /**
     * The tail behind the pivot, as a fraction of radius.
     *
     * Every real hand has one — it is what makes a hand look balanced on its
     * pivot rather than stuck to it. The second hand's is longest and is the
     * counterweight people actually notice.
     */
    private fun tail(hand: Hand): Double = when (hand) {
        Hand.HOUR -> 0.10
        Hand.MINUTE -> 0.12
        Hand.SECOND -> 0.20
    }

    /** Half-width at the widest point, as a fraction of radius. */
    private fun halfWidth(hand: Hand): Double = when (hand) {
        Hand.HOUR -> 0.038
        Hand.MINUTE -> 0.028
        Hand.SECOND -> 0.011
    }

    /**
     * The outline of one hand, pointing at twelve, in dial space.
     *
     * Returns a list because a style may need more than one closed shape —
     * [HandStyle.SYRINGE]'s hollow is a second outline inside the first.
     */
    fun shapes(style: HandStyle, hand: Hand): List<HandShape> = when (style) {
        HandStyle.BATON -> baton(hand, filled = true)
        HandStyle.SKELETON -> baton(hand, filled = false)
        // Not yet drawn. Named here rather than defaulted so that adding a style
        // to the enum without drawing it fails loudly in a test instead of
        // silently rendering a baton -- the same trap Presentation.UNOFFERED
        // exists to close.
        HandStyle.DAUPHINE, HandStyle.SYRINGE ->
            error("$style has no geometry yet; see docs/specs/analog-hands.md step 8")
    }

    /**
     * A plain rectangle from tail to tip, with the corners squared off.
     *
     * The second hand keeps its stem thin and gains a round counterweight,
     * which is what stops a long thin rectangle reading as a scratch on the
     * dial.
     */
    private fun baton(hand: Hand, filled: Boolean): List<HandShape> {
        val w = halfWidth(hand) * DIAL_RADIUS
        val tip = -reach(hand) * DIAL_RADIUS      // negative y is UP in dial space
        val back = tail(hand) * DIAL_RADIUS
        val body = HandShape(
            listOf(
                Pt(DIAL_CENTER - w, DIAL_CENTER + back),
                Pt(DIAL_CENTER - w, DIAL_CENTER + tip),
                Pt(DIAL_CENTER + w, DIAL_CENTER + tip),
                Pt(DIAL_CENTER + w, DIAL_CENTER + back),
                Pt(DIAL_CENTER - w, DIAL_CENTER + back)
            ),
            filled
        )
        if (hand != Hand.SECOND) return listOf(body)
        return listOf(body, circle(DIAL_CENTER, DIAL_CENTER + back * 0.55, w * 3.2, filled))
    }

    /**
     * The chapter ring for a style: indices pointing inward from the rim.
     *
     * Inboard of [RingSource]'s track, which keeps the outer rim, so an analog
     * face gives up none of the step, battery or rain data a digital one has.
     * See `docs/specs/analog-hands.md` §4.
     */
    fun indices(style: HandStyle): List<HandShape> {
        val outer = DIAL_RADIUS * 0.88          // inside the data ring
        val long = DIAL_RADIUS * 0.075
        val short = DIAL_RADIUS * 0.040
        val wide = DIAL_RADIUS * when (style) {
            HandStyle.BATON -> 0.016
            HandStyle.SKELETON -> 0.010
            else -> 0.012
        }
        return (0 until 12).map { i ->
            // Twelve o'clock is straight up, and the hours run clockwise.
            val a = i * PI / 6.0 - PI / 2.0
            val len = if (i % 3 == 0) long else short
            bar(a, outer, len, wide, filled = style != HandStyle.SKELETON)
        }
    }

    /** A radial bar at [angle], running inward from [outer] for [len]. */
    private fun bar(angle: Double, outer: Double, len: Double, half: Double, filled: Boolean): HandShape {
        val cx = cos(angle)
        val cy = sin(angle)
        // Perpendicular, to give the bar its width.
        val px = -cy * half
        val py = cx * half
        fun at(r: Double, sx: Double, sy: Double) =
            Pt(DIAL_CENTER + cx * r + sx, DIAL_CENTER + cy * r + sy)
        return HandShape(
            listOf(
                at(outer, px, py),
                at(outer - len, px, py),
                at(outer - len, -px, -py),
                at(outer, -px, -py),
                at(outer, px, py)
            ),
            filled
        )
    }

    /** The hub the hands turn on, drawn once per face rather than per hand. */
    fun hub(style: HandStyle): HandShape =
        circle(DIAL_CENTER, DIAL_CENTER, DIAL_RADIUS * 0.035, filled = style != HandStyle.SKELETON)

    private fun circle(cx: Double, cy: Double, r: Double, filled: Boolean): HandShape {
        val steps = 32
        val pts = (0..steps).map {
            val a = it * 2.0 * PI / steps
            Pt(cx + cos(a) * r, cy + sin(a) * r)
        }
        return HandShape(pts, filled)
    }
}
