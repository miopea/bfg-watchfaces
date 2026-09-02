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
    /**
     * The same hand, drawn for AMBIENT: outlines, never filled.
     *
     * Ambient is a black low-power screen, and a filled hand there is a slab of
     * ink where a watch should show a line. Outlining also costs far fewer lit
     * pixels, which is the whole point of the mode.
     *
     * The geometry is IDENTICAL — same call, fill turned off — so an ambient
     * hand can never be a different shape from the awake one. That was the
     * mistake available here: a second set of coordinates for the dimmed state,
     * drifting quietly because nobody looks at ambient often.
     */
    fun ambientShapes(style: HandStyle, hand: Hand): List<HandShape> =
        shapes(style, hand).map { it.copy(filled = false) }

    fun shapes(style: HandStyle, hand: Hand): List<HandShape> = when (style) {
        HandStyle.BATON -> baton(hand, filled = true)
        HandStyle.SKELETON -> baton(hand, filled = false)
        HandStyle.DAUPHINE -> dauphine(hand)
        HandStyle.SYRINGE -> syringe(hand)
    }

    /**
     * Faceted, tapering to a point: the dress-watch hand.
     *
     * A kite rather than a rectangle — widest about a third of the way out,
     * then drawn to a point. The spine down the middle is what makes it read as
     * FACETED rather than merely pointed, and it is a second closed shape
     * because [EngravedStroke] gives an outline relief and a bare line inside a
     * filled hand would otherwise vanish under the fill.
     *
     * Wider at the shoulder than a baton is anywhere, because a shape that
     * narrows to nothing needs the extra to read at a glance.
     */
    private fun dauphine(hand: Hand): List<HandShape> {
        val w = halfWidth(hand) * DIAL_RADIUS * 1.45
        val tip = -reach(hand) * DIAL_RADIUS
        val back = tail(hand) * DIAL_RADIUS
        val shoulder = tip * 0.32
        val body = HandShape(
            listOf(
                Pt(DIAL_CENTER, DIAL_CENTER + back),
                Pt(DIAL_CENTER - w, DIAL_CENTER + shoulder),
                Pt(DIAL_CENTER, DIAL_CENTER + tip),
                Pt(DIAL_CENTER + w, DIAL_CENTER + shoulder),
                Pt(DIAL_CENTER, DIAL_CENTER + back)
            ),
            filled = true
        )
        // The facet: a closed sliver along the spine, so the relief passes have
        // an edge to cut down the middle of the hand.
        val spine = HandShape(
            listOf(
                Pt(DIAL_CENTER, DIAL_CENTER + back),
                Pt(DIAL_CENTER - w * 0.08, DIAL_CENTER + shoulder),
                Pt(DIAL_CENTER, DIAL_CENTER + tip),
                Pt(DIAL_CENTER + w * 0.08, DIAL_CENTER + shoulder),
                Pt(DIAL_CENTER, DIAL_CENTER + back)
            ),
            filled = false
        )
        if (hand != Hand.SECOND) return listOf(body, spine)
        return listOf(body, spine, circle(DIAL_CENTER, DIAL_CENTER + back * 0.55, w * 2.2, true))
    }

    /**
     * A slim stem opening into a hollow lozenge near the tip.
     *
     * Three shapes: stem, lozenge, and an inner outline within the lozenge.
     *
     * The inner outline is a FACET, not a cutout. It is drawn, not subtracted —
     * an unfilled shape here is stroked over the fill beneath it rather than
     * punching through it, so the dial does not actually read through the
     * lozenge. A true cutout would need an even-odd winding rule applied to a
     * combined path in BOTH renderers, which is a change to [HandShape] rather
     * than to this function, and it is not worth it: the facet is what gives
     * the lozenge its depth, and the depth is the effect.
     *
     * Said explicitly because the first version of this comment claimed the
     * dial showed through, which looked plausible in code and was not true in
     * the render.
     */
    private fun syringe(hand: Hand): List<HandShape> {
        val w = halfWidth(hand) * DIAL_RADIUS
        val tip = -reach(hand) * DIAL_RADIUS
        val back = tail(hand) * DIAL_RADIUS
        val neck = tip * 0.58
        val stem = HandShape(
            listOf(
                Pt(DIAL_CENTER - w * 0.5, DIAL_CENTER + back),
                Pt(DIAL_CENTER - w * 0.5, DIAL_CENTER + neck),
                Pt(DIAL_CENTER + w * 0.5, DIAL_CENTER + neck),
                Pt(DIAL_CENTER + w * 0.5, DIAL_CENTER + back),
                Pt(DIAL_CENTER - w * 0.5, DIAL_CENTER + back)
            ),
            filled = true
        )
        val mid = neck + (tip - neck) * 0.45
        val lozenge = HandShape(
            listOf(
                Pt(DIAL_CENTER, DIAL_CENTER + neck),
                Pt(DIAL_CENTER - w * 1.5, DIAL_CENTER + mid),
                Pt(DIAL_CENTER, DIAL_CENTER + tip),
                Pt(DIAL_CENTER + w * 1.5, DIAL_CENTER + mid),
                Pt(DIAL_CENTER, DIAL_CENTER + neck)
            ),
            filled = true
        )
        val hollowMid = neck + (tip - neck) * 0.45
        val hollow = HandShape(
            listOf(
                Pt(DIAL_CENTER, DIAL_CENTER + neck + (tip - neck) * 0.16),
                Pt(DIAL_CENTER - w * 0.8, DIAL_CENTER + hollowMid),
                Pt(DIAL_CENTER, DIAL_CENTER + tip - (tip - neck) * 0.16),
                Pt(DIAL_CENTER + w * 0.8, DIAL_CENTER + hollowMid),
                Pt(DIAL_CENTER, DIAL_CENTER + neck + (tip - neck) * 0.16)
            ),
            filled = false
        )
        if (hand != Hand.SECOND) return listOf(stem, lozenge, hollow)
        return listOf(stem, lozenge, hollow, circle(DIAL_CENTER, DIAL_CENTER + back * 0.55, w * 3.0, true))
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
        // Each style's indices match its hands, which is the whole reason a
        // style owns both: a fine dress hand beside a heavy baton index reads
        // as two watches.
        val wide = DIAL_RADIUS * when (style) {
            HandStyle.BATON -> 0.016
            HandStyle.SKELETON -> 0.010
            HandStyle.DAUPHINE -> 0.009
            HandStyle.SYRINGE -> 0.011
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
