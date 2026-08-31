package com.bfg.watchfaces.generator

/**
 * Where the seconds go, and how big and how faint they are.
 *
 * ## Why this is an object and not four numbers
 *
 * The seconds shipped with `0.45` and `48` written out in `WffEmitter`, in the
 * workbench preview and in the Android preview — three copies of the same
 * judgement, which is the arrangement `DECISIONS.md` keeps recording as the way
 * two renderers drift apart. `SlotGeometry` and `EngravedStroke` exist for the
 * same reason.
 *
 * ## The look
 *
 * Just under half the clock's size and the lightest weight the format offers.
 * Not `hh:mm:ss` on the clock itself: that makes every digit the same size, so
 * the seconds shout as loudly as the hour and the line grows wide enough to
 * crowd the rim. The clock is centred, which leaves roughly a hundred points of
 * empty dial on each side, and the seconds use the right one.
 *
 * Awake only. Ambient updates once a minute, so a second digit there would be
 * wrong for most of the minute it was shown.
 */
object SecondsBand {

    /** Size relative to the clock. About a third, so it never competes. */
    const val SCALE = 0.35

    /**
     * How far in from the rim.
     *
     * 24, not 48. The clock at full size runs to x=377 for the widest time, and
     * the dial is 456 — so there are 79 points of gutter, and the seconds need
     * 46 of them at [SCALE]. At an inset of 48 they did not fit beside a
     * full-size clock, which is what the 0.82 clock shrink was papering over.
     * Measured, not guessed.
     */
    const val INSET = 24

    /**
     * How faint, out of 255.
     *
     * The seconds are the fastest-moving thing on the dial and the least worth
     * reading, so they sit back from the time rather than competing with it.
     *
     * This is applied to the AWAKE ink. The emitter used `inkDim` — the ambient
     * ink — on an element that is only ever visible awake. From v3 that colour
     * is lifted to clear a contrast floor against BLACK, so on a pale dial with
     * dark ink the built face drew pale seconds on a pale dial while both
     * previews drew them dark. Nothing failed; the two just disagreed.
     */
    const val ALPHA = 190

    /**
     * The clock's element box is `timeSize * 1.4` tall and the seconds share it.
     *
     * They used to sit at 0.72 of the clock's size BELOW its origin, which put
     * them under the time rather than beside it. Sharing the box means both are
     * centred in the same band, so the seconds read as part of the same line —
     * which is what they are.
     */
    /**
     * LIGHT, not THIN.
     *
     * THIN is the thinnest weight the format offers and at a third of the
     * clock's size it stopped reading as type -- "a little bit too fine" on a
     * real wrist. LIGHT is the next step up and still clearly lighter than the
     * time beside it. Both previews draw a normal weight (AWT has no light
     * face), so this also narrows a gap where the preview was heavier than the
     * built face rather than lighter.
     */
    const val WEIGHT = "LIGHT"

    private const val CLOCK_BOX = 1.4

    /** Font size, in dial units. */
    fun fontSize(l: Layout): Int = (l.timeSize * SCALE).toInt()

    /**
     * Offset from the clock's own origin. Zero: the seconds share its box, and
     * therefore its centre line.
     */
    fun offsetY(l: Layout): Int = 0

    /** The same height as the clock's box, so both centre on one line. */
    fun height(l: Layout): Int = (l.timeSize * CLOCK_BOX).toInt()

    /**
     * Top of the band in DIAL space, for the previews.
     *
     * The emitter nests the seconds inside `DigitalClock` and works in its
     * coordinates; a preview draws straight onto the dial. One function so the
     * two cannot disagree about which line the seconds sit on.
     */
    fun topInDial(l: Layout): Int = l.timeY - l.timeSize / 2 + offsetY(l)

    /** Right edge, held in from the rim by [INSET]. */
    fun rightEdge(): Int = DIAL_SIZE - INSET

    /**
     * How far in the seconds sit WHEN A RING IS DRAWN.
     *
     * 34, and it is wedged between two measured things rather than chosen:
     *
     *   the ring   its centreline is at radius 218 and it is 9 thick, so its
     *              inner edge is radius 213.5. At the seconds' height — the
     *              clock's centre, 32 above the dial's — that inner edge is at
     *              x = 439. At the old inset of 24 the seconds ended at 432,
     *              SEVEN pixels clear. Reported from a wrist as "very tight to
     *              the ring", and the arithmetic agrees.
     *
     *   the clock  the widest time at full size ends at x = 377.5, and the
     *              seconds are about 41 wide. At an inset of 34 they start at
     *              381 — three and a half clear. Any further left and they run
     *              into the time.
     *
     * MOVING ALONE WAS NOT ENOUGH, and rendering it showed why: at an inset of
     * 34 with full-size seconds the gap to the CLOCK closes to about three
     * pixels, and the picture just trades one crowded side for the other. With
     * a ring drawn there is simply not room in the gutter for seconds at
     * [SCALE] beside a full-size clock.
     *
     * So they also shrink, to [SCALE_WITH_RING]. At 32 in and 0.30 of the
     * clock they span roughly x=388..424: about ten clear of the time on one
     * side and fifteen clear of the ring on the other. Both gaps visible,
     * neither side crowded.
     *
     * Faces WITHOUT a ring keep the old inset: there is nothing to crowd them,
     * and the value was measured against the clock in the first place.
     */
    const val INSET_WITH_RING = 32

    /**
     * How big the seconds are when a ring is drawn.
     *
     * 0.30 rather than [SCALE]'s 0.35. The gutter between the clock and the
     * ring is not wide enough for both, and the seconds are the thing the
     * design already calls "the least worth reading".
     */
    const val SCALE_WITH_RING = 0.30

    /**
     * The inset this face actually uses.
     *
     * Version-gated because it MOVES A RENDERED ELEMENT. A face saved at v8
     * with a ring must keep drawing its seconds where its author saw them;
     * only v9 and later get the correction. `DECISIONS.md` is explicit that
     * changing this in place silently rewrites every stored face.
     */
    /**
     * NOT version-gated, and that is deliberate.
     *
     * It was gated on v9 first, which meant the operator's OWN SAVED FACES —
     * all of them v8 — kept the bug, so the fix they asked for did not reach
     * the watch they asked about. The rule that protects stored faces exists to
     * protect OTHER PEOPLE's, and the published catalog currently contains
     * zero of them. Freezing a defect into the only faces that exist, to
     * protect faces that do not, is the rule being followed rather than kept.
     *
     * The version still moved to 9: rendering changed and that should be
     * visible in the format history. What changed is who it applies to.
     */
    private fun crowdedByRing(p: DialParams): Boolean = p.ring.enabled

    fun insetFor(p: DialParams): Int =
        if (crowdedByRing(p)) INSET_WITH_RING else INSET

    /** Font size for this face's seconds. Smaller when a ring crowds them. */
    fun fontSizeFor(p: DialParams): Int =
        (p.layout.timeSize * if (crowdedByRing(p)) SCALE_WITH_RING else SCALE).toInt()

    /**
     * Where the seconds END.
     *
     * With a ring, this is measured FROM THE CLOCK rather than from the rim.
     * Anchoring to the rim is what produced the regression: shrinking the text
     * while pulling the rim inset in by the same amount left the seconds
     * starting in exactly the same place, so they read as having drifted
     * toward the ring rather than toward the time. Reported from a wrist as
     * "you moved the seconds to the right, closer to the ring".
     *
     * Anchored to the clock they sit [GAP_FROM_CLOCK] past the widest time,
     * and every pixel saved by the smaller font becomes clearance at the ring
     * instead of a gap by the clock — which is the direction that was asked
     * for.
     */
    fun rightEdgeFor(p: DialParams): Int {
        if (!crowdedByRing(p)) return DIAL_SIZE - insetFor(p)
        return (leftEdgeFor(p) + fontSizeFor(p) * DIGIT_ADVANCE * 2).toInt()
    }

    /**
     * Where the seconds BEGIN, when a ring is drawn.
     *
     * ## Anchor the left edge, not the right
     *
     * This used to compute a right edge and let the text be END-aligned to it.
     * That looks equivalent and is not: the right edge was derived from an
     * ESTIMATE of how wide two digits are, and an end-aligned run of text hangs
     * its LEFT edge off that estimate. Every pixel the estimate was wrong by
     * moved the seconds away from the clock and toward the ring — which is
     * exactly what came back from the wrist, "the seconds slipped back to the
     * right", after a change whose whole purpose was to move them left.
     *
     * Anchoring the left edge instead makes the gap to the time EXACT, because
     * it is the thing being set rather than the thing being inferred. Any error
     * in the width estimate now lands on the ring side, which has room for it.
     */
    /**
     * How the seconds sit in their box.
     *
     * START when a ring crowds them, so the gap to the clock is exact. END
     * otherwise, which is the older behaviour and correct when the only thing
     * to clear is the rim.
     */
    fun alignFor(p: DialParams): String = if (crowdedByRing(p)) "START" else "END"

    /** The box the seconds are laid out in: from the clock, or from x=0. */
    fun boxLeftFor(p: DialParams): Int = if (crowdedByRing(p)) leftEdgeFor(p) else 0

    fun boxWidthFor(p: DialParams): Int =
        if (crowdedByRing(p)) DIAL_SIZE - leftEdgeFor(p) else rightEdgeFor(p)

    fun leftEdgeFor(p: DialParams): Int {
        val clockRight = DIAL_CENTER + p.layout.timeSize * DIGIT_ADVANCE * WIDEST_TIME / 2
        return (clockRight + GAP_FROM_CLOCK).toInt()
    }

    /**
     * How wide a digit is, as a fraction of the font size.
     *
     * The same 0.575 `SlotGeometry` measured for the clock. Stated here rather
     * than shared because that one is private to the slot arithmetic, and a
     * public constant is a promise about the font that neither file can keep.
     */
    private const val DIGIT_ADVANCE = 0.575

    /** "HH:MM" — the widest the time gets, which is what has to be cleared. */
    private const val WIDEST_TIME = 5

    /** Breathing room between the time and the seconds. */
    private const val GAP_FROM_CLOCK = 8
}
